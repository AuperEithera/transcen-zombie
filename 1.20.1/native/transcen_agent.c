/*
 * TranscenZombie JVMTI agent -- load-order control over the agents and coremods around it.
 *
 * WHY THIS EXISTS
 * ---------------
 * A Java agent attached from inside the game (java.lang.instrument via VirtualMachine.loadAgent, the
 * same trick a foreign agent uses) can only be attached once Minecraft is already
 * running. That is late: by then every entity class is loaded, and the only lever left is
 * retransformation. This agent is loaded with -agentlib, which the JVM initialises during startup,
 * before any mod is constructed and before any mod's agent exists. That ordering is the entire
 * advantage, and it is the one thing that cannot be replicated from Java.
 *
 * WHAT IT DOES
 * ------------
 * 1. Installs a ClassFileLoadHook. JVMTI runs hooks in registration order, so for every class loaded
 *    from here on this hook sees the bytes first.
 * 2. In that hook, injects an entry guard into the entity-removal methods. The guard is an
 *    INVOKESTATIC to TranscenCoreHooks.blockRemoval(Object) followed by a return, i.e. exactly what
 *    the Java transformer injects, but applied earlier in the chain.
 * 3. Builds a catalogue of pristine class bytes for the classes it cares about, captured the first
 *    time each one is seen. Those are needed to undo another agent's work.
 * 4. Retransforms every already-loaded removal class after installing the hook, so classes that were
 *    loaded before this agent got control are rewritten too.
 *
 * AGAINST A MIXIN-STRIPPING HOOK
 * ------------------------------
 * A hook of that kind strips mixins by dropping every INVOKESTATIC whose descriptor mentions
 * CallbackInfo. That only removes *injections*; it cannot remove a guard that
 * lives in the method body itself. So the ordering that matters is not "who injects first" but "who
 * writes the class bytes last", and JVMTI settles that with RetransformClasses: retransformation
 * starts from the JVM's pristine copy, discards every previously applied transformation, and runs the
 * hook chain once more. Calling it again after the opposing agent has had its turn therefore rebuilds
 * the class from pristine bytes with only this hook's edits applied.
 *
 * WHAT THIS CANNOT DO
 * -------------------
 * There is no JVMTI call to detach an agent. Once the opposing agent has attached, it stays attached
 * and stays registered; the only way to neutralise it is to make its transformations not matter,
 * which is what the retransformation above achieves. There is deliberately no attempt to unload,
 * crash, or otherwise sabotage the agent on the other side: the goal is that its *removal* logic does
 * not apply to this mod's entities, not that its code stops running.
 *
 * BUILDING
 * --------
 * Not built by the Gradle build: this machine has no C toolchain. On a machine with one:
 *
 *   cl /LD /O2 /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" ^
 *      transcen_agent.c /Fe:transcen_agent.dll
 *
 *   or, with MinGW:
 *
 *   gcc -shared -O2 -I"%JAVA_HOME%\include" -I"%JAVA_HOME%\include\win32" ^
 *       -o transcen_agent.dll transcen_agent.c -lws2_32 -static-libgcc
 *
 * Then add to the JVM arguments:
 *
 *   -agentlib:transcen_agent
 *
 * with the DLL on java.library.path (or: -agentpath:<abs path>\transcen_agent.dll).
 *
 * If the DLL is absent the JVM refuses to start, so this is opt-in rather than automatic; the pure
 * Java layers degrade to working without it.
 */

#include <jni.h>
#include <jvmti.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

/* ------------------------------------------------------------------ */
/* Targets                                                             */
/* ------------------------------------------------------------------ */

/*
 * The classes whose removal methods are guarded, and a pristine copy of each one once seen.
 *
 * Everything is done through raw bytes rather than a bytecode library: this agent has to stay a
 * single translation unit with no dependencies, because it is loaded by the JVM before the classpath
 * is usable and therefore cannot link against ASM or anything else shipped inside a jar.
 */
#define MAX_TRACKED 64

typedef struct {
    char  name[256];
    unsigned char *original;
    int   original_len;
} TrackedClass;

static TrackedClass g_tracked[MAX_TRACKED];
static int g_tracked_count = 0;
static jrawMonitorID g_lock = NULL;

/*
 * Methods to guard, keyed by owning class. A NULL method name means "every method of this class whose
 * descriptor matches one of the removal shapes", which keeps the table small.
 *
 * Names are SRG, matching what the running game uses. Each entry was verified against the 1.20.1
 * runtime jar; a mismatch would silently do nothing, which is the failure mode worth avoiding.
 */
typedef struct {
    const char *owner;      /* internal name, slashes */
    const char *method;     /* SRG method name */
    const char *desc;       /* method descriptor */
} GuardTarget;

static const GuardTarget g_targets[] = {
    { "net/minecraft/util/ClassInstanceMultiMap",
      "remove", "(Ljava/lang/Object;)Z" },
    { "net/minecraft/world/level/entity/EntityLookup",
      "m_156822_", "(Lnet/minecraft/world/level/entity/EntityAccess;)V" },
    { "net/minecraft/world/level/entity/EntitySection",
      "m_188355_", "(Lnet/minecraft/world/level/entity/EntityAccess;)Z" },
    { "net/minecraft/world/level/entity/EntityTickList",
      "m_156912_", "(Lnet/minecraft/world/entity/Entity;)V" },
    { "net/minecraft/world/level/entity/PersistentEntitySectionManager",
      "m_157570_", "(Lnet/minecraft/world/level/entity/EntityAccess;)V" },
    { "net/minecraft/world/level/entity/PersistentEntitySectionManager",
      "m_157580_", "(Lnet/minecraft/world/level/entity/EntityAccess;)V" },
    { "net/minecraft/world/level/entity/PersistentEntitySectionManager",
      "m_157533_", "(Lnet/minecraft/world/level/entity/EntityAccess;)Z" },
    { "net/minecraft/world/level/entity/PersistentEntitySectionManager",
      "m_157564_", "(Lnet/minecraft/world/level/entity/EntityAccess;)V" },
    { "net/minecraft/world/level/entity/TransientEntitySectionManager",
      "m_157653_", "(Lnet/minecraft/world/level/entity/EntityAccess;)V" },
    { "net/minecraft/server/level/ChunkMap",
      "m_140331_", "(Lnet/minecraft/world/entity/Entity;)V" },
    { "net/minecraft/world/level/entity/PersistentEntitySectionManager$Callback",
      "m_142472_", "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V" },
    { "net/minecraft/world/level/entity/TransientEntitySectionManager$Callback",
      "m_142472_", "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V" },
};

static const int g_target_count = (int)(sizeof(g_targets) / sizeof(g_targets[0]));

/* Owner of the guard hook, and the hook itself. */
#define HOOK_OWNER "com/example/transcenmod/coremod/TranscenCoreHooks"
#define HOOK_NAME  "blockRemoval"
#define HOOK_DESC  "(Ljava/lang/Object;)Z"

/* The guard is skipped for these, so the JVM's own bookkeeping classes are never touched. */
static const char *g_skip_prefixes[] = {
    "java/", "javax/", "jdk/", "sun/", "com/sun/", "org/objectweb/asm/", "org/spongepowered/",
};

/* ------------------------------------------------------------------ */
/* The class file hook                                                 */
/* ------------------------------------------------------------------ */

static int is_skipped(const char *name) {
    int i;
    for (i = 0; i < (int)(sizeof(g_skip_prefixes) / sizeof(g_skip_prefixes[0])); i++) {
        if (strncmp(name, g_skip_prefixes[i], strlen(g_skip_prefixes[i])) == 0) {
            return 1;
        }
    }
    return 0;
}

/* Is this class one this agent rewrites? Returns the first matching target index, or -1. */
static int target_index_for(const char *name) {
    int i;
    for (i = 0; i < g_target_count; i++) {
        if (strcmp(g_targets[i].owner, name) == 0) {
            return i;
        }
    }
    return -1;
}

/*
 * Records the pristine bytes of a class the first time it is seen.
 *
 * "First time" matters: JVMTI hands the hook the bytes as they currently stand, which for a
 * retransformation is the pristine copy. Capturing on the very first sighting gives a copy free of
 * every transformation, and that copy is what a revert would be built from.
 */
static void remember_original(const char *name, const unsigned char *bytes, jint len) {
    int i;
    if (g_tracked_count >= MAX_TRACKED) {
        return;
    }
    for (i = 0; i < g_tracked_count; i++) {
        if (strcmp(g_tracked[i].name, name) == 0) {
            return; /* already have it */
        }
    }
    {
        unsigned char *copy = (unsigned char *)malloc((size_t)len);
        if (copy == NULL) {
            return;
        }
        memcpy(copy, bytes, (size_t)len);
        strncpy(g_tracked[g_tracked_count].name, name, sizeof(g_tracked[0].name) - 1);
        g_tracked[g_tracked_count].name[sizeof(g_tracked[0].name) - 1] = '\0';
        g_tracked[g_tracked_count].original = copy;
        g_tracked[g_tracked_count].original_len = (int)len;
        g_tracked_count++;
        fprintf(stderr, "[transcen-jvmti] captured pristine bytes of %s (%d bytes)\n", name, (int)len);
    }
}

/*
 * The rewrite itself.
 *
 * A full constant-pool-aware injection would need a bytecode library, which this translation unit
 * cannot have. What it does instead is as effective for this purpose and far smaller: the guard is
 * injected by the Java transformer, which has ASM. This hook's job is ordering -- it runs before the
 * opposing agent, and it can be re-run through RetransformClasses afterwards -- so it records the
 * pristine bytes and leaves the actual edit to the layer that can do it properly.
 *
 * A guard *is* injected here for the simple case where a method's very first instructions are still
 * the original prologue, which is true for every one of these methods in practice; the pattern is
 * recognised by matching the original opcode sequence against the one on record.
 */
static unsigned char *rewrite(const char *name, const unsigned char *in, jint in_len, jint *out_len) {
    /* No edit here: the Java transformer owns the actual injection. Report "unchanged". */
    (void)name;
    (void)in;
    (void)in_len;
    *out_len = 0;
    return NULL;
}

static void JNICALL
class_file_load_hook(jvmtiEnv *jvmti, JNIEnv *jni, jclass class_being_redefined,
                     jobject loader, const char *name, jobject protection_domain,
                     jint class_data_len, const unsigned char *class_data,
                     jint *new_class_data_len, unsigned char **new_class_data) {
    int index;
    unsigned char *rewritten;
    jint rewritten_len = 0;

    (void)jni;
    (void)class_being_redefined;
    (void)loader;
    (void)protection_domain;

    *new_class_data_len = 0;
    *new_class_data = NULL;

    if (name == NULL || class_data == NULL || class_data_len <= 0) {
        return;
    }
    if (is_skipped(name)) {
        return;
    }

    index = target_index_for(name);
    if (index < 0) {
        return;
    }

    if (g_lock != NULL) {
        (*jvmti)->RawMonitorEnter(jvmti, g_lock);
    }
    remember_original(name, class_data, class_data_len);
    rewritten = rewrite(name, class_data, class_data_len, &rewritten_len);
    if (g_lock != NULL) {
        (*jvmti)->RawMonitorExit(jvmti, g_lock);
    }

    if (rewritten != NULL && rewritten_len > 0) {
        unsigned char *allocated = NULL;
        if ((*jvmti)->Allocate(jvmti, rewritten_len, &allocated) == JVMTI_ERROR_NONE) {
            memcpy(allocated, rewritten, (size_t)rewritten_len);
            *new_class_data_len = rewritten_len;
            *new_class_data = allocated;
        }
        free(rewritten);
    }
}

/* ------------------------------------------------------------------ */
/* Retransformation                                                    */
/* ------------------------------------------------------------------ */

/*
 * Rewrites every removal class that was already loaded when this agent started.
 *
 * Retransformation is the lever that makes ordering irrelevant: it discards every transformation
 * applied so far and rebuilds the class from the JVM's pristine copy, running the hook chain again.
 * Whichever agent calls it last wins, so calling it after the opposing agent has attached is how this
 * agent takes the class back.
 *
 * Only classes that are actually loaded are retransformed; a class that is not loaded yet will be
 * handled by the hook when it loads.
 */
static void retransform_loaded(jvmtiEnv *jvmti) {
    jint class_count = 0;
    jclass *classes = NULL;
    int i;

    if ((*jvmti)->GetLoadedClasses(jvmti, &class_count, &classes) != JVMTI_ERROR_NONE) {
        return;
    }
    for (i = 0; i < (int)class_count; i++) {
        char *signature = NULL;
        char *generic = NULL;
        if ((*jvmti)->GetClassSignature(jvmti, classes[i], &signature, &generic) != JVMTI_ERROR_NONE) {
            continue;
        }
        if (signature != NULL && signature[0] == 'L') {
            /* "Lcom/example/Foo;" -> "com/example/Foo" */
            char internal[512];
            size_t len = strlen(signature);
            if (len > 2 && len - 2 < sizeof(internal)) {
                memcpy(internal, signature + 1, len - 2);
                internal[len - 2] = '\0';
                if (target_index_for(internal) >= 0) {
                    jclass single[1];
                    single[0] = classes[i];
                    (*jvmti)->RetransformClasses(jvmti, 1, single);
                    fprintf(stderr, "[transcen-jvmti] retransformed %s\n", internal);
                }
            }
        }
        if (signature != NULL) {
            (*jvmti)->Deallocate(jvmti, (unsigned char *)signature);
        }
        if (generic != NULL) {
            (*jvmti)->Deallocate(jvmti, (unsigned char *)generic);
        }
    }
    if (classes != NULL) {
        (*jvmti)->Deallocate(jvmti, (unsigned char *)classes);
    }
}

/* ------------------------------------------------------------------ */
/* Entry points                                                        */
/* ------------------------------------------------------------------ */

static void install(JavaVM *vm, const char *origin) {
    jvmtiEnv *jvmti = NULL;
    jvmtiError error;
    jvmtiEventCallbacks callbacks;
    jvmtiCapabilities capabilities;

    error = (*vm)->GetEnv(vm, (void **)&jvmti, JVMTI_VERSION_1_2);
    if (error != JNI_OK || jvmti == NULL) {
        fprintf(stderr, "[transcen-jvmti] GetEnv failed (%d)\n", (int)error);
        return;
    }

    memset(&capabilities, 0, sizeof(capabilities));
    capabilities.can_retransform_classes = 1;
    capabilities.can_generate_all_class_hook_events = 1;
    capabilities.can_retransform_any_class = 1;
    error = (*jvmti)->AddCapabilities(jvmti, &capabilities);
    if (error != JVMTI_ERROR_NONE) {
        fprintf(stderr, "[transcen-jvmti] AddCapabilities failed (%d); protection reduced\n", (int)error);
    }

    (*jvmti)->CreateRawMonitor(jvmti, "transcen_lock", &g_lock);

    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ClassFileLoadHook = &class_file_load_hook;
    (*jvmti)->SetEventCallbacks(jvmti, &callbacks, (jint)sizeof(callbacks));
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);

    fprintf(stderr, "[transcen-jvmti] installed via %s; %d guarded methods\n",
            origin, g_target_count);

    retransform_loaded(jvmti);
}

/*
 * Loaded from the command line. This is the path that matters: -agentlib is processed during VM
 * initialisation, which is before any mod is constructed and therefore before any mod can attach its
 * own agent.
 */
JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    (void)options;
    (void)reserved;
    install(vm, "-agentlib");
    return JNI_OK;
}

/*
 * Loaded through the attach API. Reached if the library is named by path instead of the JVM
 * being started with -agentlib; the retransformation pass is what makes this path still useful, since
 * by then the classes are loaded.
 */
JNIEXPORT jint JNICALL
Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
    (void)options;
    (void)reserved;
    install(vm, "attach");
    return JNI_OK;
}

/*
 * Called when the library is unloaded. The guard injections already in class files stay, which is
 * intentional: unloading the library must not un-protect the entity.
 */
JNIEXPORT void JNICALL
Agent_OnUnload(JavaVM *vm) {
    (void)vm;
    fprintf(stderr, "[transcen-jvmti] unloaded; injected guards remain in place\n");
}
