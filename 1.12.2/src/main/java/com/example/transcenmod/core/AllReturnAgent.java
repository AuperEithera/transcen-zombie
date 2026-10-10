package com.example.transcenmod.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * AllReturn's agent: the rewrite, the ClassFileTransformer that applies it, and the code that gets the
 * whole thing into the running JVM.
 *
 * It is one file with static nested classes rather than four, and it has one hard rule that shapes all of
 * it: every class in here is also loaded by the SYSTEM classloader out of a generated jar, where no
 * minecraft class and no other class of this mod exists. So this file may name nothing but the JDK and
 * ASM. The state it needs from the mod arrives through a system property
 * (AllReturn.PROPERTY), and the only thing the injected guard touches is AllReturn.allReturn, which is on
 * LaunchClassLoader's search path because every class it rewrites is loaded by that loader.
 *
 * WHY AN AGENT AT ALL. 1.12.2 has no agent mechanism, so this is the whole of it: Loader writes a jar to
 * the temp directory from the class files that already ship in this mod, and asks the JDK's attach API to
 * load it. Attaching needs com.sun.tools.attach, which lives in tools.jar - present on a JDK, absent on a
 * JRE - and it is done reflectively so a JRE fails to attach rather than fails to load this class.
 *
 * LOWEST PRIORITY. The transformer is added with canRetransform=false. Instrumentation keeps its
 * transformers in an array and calls them in the order they were added, so a transformer appended at
 * runtime runs after every transformer that was already there, and whatever it returns replaces what all
 * of them produced. That is the "lowest priority" the feature is specified as.
 *
 * The rewrite inserts {@code if (AllReturn.allReturn) return <default>;} at the head of every method of every
 * class this transform is handed.
 */
public final class AllReturnAgent {

    private static final String FLAG_OWNER = "com/example/transcenmod/core/AllReturn";
    private static final String FLAG_NAME = "allReturn";
    private static final String FLAG_DESC = "Z";
    private static final String AGENT_CLASS = "com.example.transcenmod.core.AllReturnAgent";
    private static final String STATUS_PROPERTY = "transcenmod.allReturn.agent";
    private static final String LAUNCH_CLASS_LOADER = "net.minecraft.launchwrapper.LaunchClassLoader";

    // Not a literal "everything except minecraft, forge, this mod and java". It also adds the
    // rest of the JVM's own support libraries: log4j, guava, fastutil and ASM are what the transformer
    // stack, the logger and the crash reporter are built from. Returning null from LogManager.getLogger or
    // ImmutableList.of() does not degrade the game, it ends it on the first line of output - which would
    // break the rule that a failure must never take the game down. Mod jars from elsewhere are NOT in this
    // list and are still rewritten, which is the point of the feature.
    private static final String[] EXCLUDED = {
            "java/", "javax/", "jdk/", "sun/", "com/sun/", "org/w3c/", "org/xml/",
            "net/minecraft/", "net/minecraftforge/", "com/mojang/",
            "com/example/transcenmod/",
            "org/objectweb/asm/", "org/ow2/", "cpw/mods/",
            "org/apache/", "com/google/", "it/unimi/", "io/netty/",
            "org/lwjgl/", "org/newdawn/", "paulscode/", "scala/", "org/jline/", "com/typesafe/",
            "lzma/", "java3d/", "net/sf/", "org/scala-lang/"
    };

    private static final Object LOCK = new Object();

    private static volatile boolean attempted;

    private static volatile String status = "not attempted";

    private AllReturnAgent() {}

    // ---------------------------------------------------------------------------------------------
    // the rewrite
    // ---------------------------------------------------------------------------------------------

    // the same two rules as in AllReturn, deliberately duplicated: see the class comment on why a second
    // class of this mod may not appear in this file.
    private static final String[] PICTURE_PACKAGES = {
            "/renderer/", "/model/", "/client/render", "/client/model"
    };

    private static final String[] PICTURE_TYPES = { "Render", "Model", "Layer" };

    private static final String[] PICTURE_METHODS = {
            "doRender", "func_76986_a", "render", "renderName", "renderNameTag", "getEntityTexture",
            "func_110775_a", "getTexture", "getRenderType", "shouldRender", "preRenderCallback",
            "getColorMultiplier", "setRotationAngles", "func_78087_a", "setLivingAnimations",
            "applyRotations", "rotateCorpse", "doRenderLayer", "renderTileEntityAt", "getSwingProgress"
    };

    private static final Set<String> CALLED_FROM_GAME = ConcurrentHashMap.newKeySet();
    private static final Map<String, Boolean> MOD_CLASS = new ConcurrentHashMap<String, Boolean>();

    private static boolean isGameNamespace(String internalName) {
        return internalName.startsWith("net/minecraft/") || internalName.startsWith("net/minecraftforge/")
                || internalName.startsWith("paulscode/") || internalName.startsWith("com/jcraft/");
    }

    /** Remembers every member of a foreign class that the game's own code calls: the game consumes its value. */
    static void noteVanillaFacing(byte[] basicClass, ClassLoader loader) {
        try {
            new ClassReader(basicClass).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override
                public MethodVisitor visitMethod(int access, String methodName, String desc,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM5) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String callName,
                                                    String callDesc, boolean isInterface) {
                            if (isModClass(owner, loader)) {
                                CALLED_FROM_GAME.add(owner + "." + callName + callDesc);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable failure) {
            // a class that cannot be read records nothing; the name list covers the rest
        }
    }

    private static boolean isModClass(String internalName, ClassLoader loader) {
        if (internalName == null || isExcluded(internalName)) {
            return false;
        }
        Boolean known = MOD_CLASS.get(internalName);
        if (known != null) {
            return known.booleanValue();
        }
        boolean foreign = isForeignJarClass(internalName, loader);
        MOD_CLASS.put(internalName, Boolean.valueOf(foreign));
        return foreign;
    }

    private static boolean isPicture(String internalName) {
        if (internalName == null) {
            return false;
        }
        String lower = internalName.toLowerCase(Locale.ENGLISH);
        for (String part : PICTURE_PACKAGES) {
            if (lower.contains(part)) {
                return true;
            }
        }
        int slash = internalName.lastIndexOf('/');
        String simple = slash < 0 ? internalName : internalName.substring(slash + 1);
        for (String type : PICTURE_TYPES) {
            if (simple.startsWith(type)) {
                return true;
            }
        }
        return simple.endsWith("Renderer");
    }

    public static boolean isExcluded(String internalName) {
        if (internalName == null) {
            return true;
        }
        for (String prefix : EXCLUDED) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // the mcp name and the srg name of every method whose value the game reads to decide whether
    // something is alive. a coremod may have replaced the body with a delegate into its own class, so
    // zeroing that one method kills the player; a source test cannot see it, a name test can.
    private static final String[] NEVER = {
            "getHealth", "func_110143_aJ",
            "getMaxHealth", "func_110138_aP",
            "onUpdate", "func_70071_h_", "func_189213_a",
            "onLivingUpdate", "func_70636_d",
            "onDeath", "func_70645_a",
            "updateEntities", "func_72939_s",
            "updateEntity", "func_72870_g"
    };

    private static final Map<String, Boolean> SOURCE_VERDICTS = new ConcurrentHashMap<String, Boolean>();

    /**
     * True when the class's bytes come out of a jar that is not this mod's.
     *
     * The origin of the bytes is asked of the loader that is defining the class, so java, minecraft,
     * forge, the libraries the game ships and this mod are all let through, and so is anything the loader
     * cannot name. Only a foreign class is guarded: missing one leaves it working, guarding one class too
     * many is what took the game down. A copy of the same test lives in AllReturn, deliberately - see the
     * class comment on why a second class of this mod may not appear in this file.
     */
    public static boolean isForeignJarClass(String internalName, ClassLoader loader) {
        try {
            if (loader == null || internalName == null) {
                return false;
            }
            String path = internalName + ".class";
            URL resource = loader instanceof URLClassLoader
                    ? ((URLClassLoader) loader).findResource(path)
                    : null;
            if (resource == null) {
                resource = loader.getResource(path);
            }
            return resource != null && isModJar(resource.toString());
        } catch (Throwable failure) {
            return false;
        }
    }

    private static boolean isModJar(String resource) {
        String text = resource.replace('\\', '/');
        int separator = text.indexOf('!');
        String jar = (separator < 0 ? text : text.substring(0, separator)).toLowerCase(Locale.ENGLISH);
        if (!jar.endsWith(".jar")) {
            // a directory on the classpath: the development workspace, or the jdk
            return false;
        }
        Boolean known = SOURCE_VERDICTS.get(jar);
        if (known != null) {
            return known.booleanValue();
        }
        // a mod lives in a mods folder. that is the whole test, and it is a physical one
        boolean foreign = jar.contains("/mods/") || jar.contains("/coremods/");
        if (jar.contains("transcenmod")) {
            foreign = false;
        }
        SOURCE_VERDICTS.put(jar, Boolean.valueOf(foreign));
        return foreign;
    }

    /**
     * Inserts {@code if (AllReturn.allReturn) return <default>;} at the head of every method of a class.
     *
     * @return the rewritten class, or null when nothing changed - the ClassFileTransformer contract for
     *         "leave this class alone".
     */
    public static byte[] rewrite(String internalName, byte[] basicClass) {
        if (basicClass == null || isExcluded(internalName)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            // flag 0, not EXPAND_FRAMES: the existing stack map frames stay compressed, which is what the
            // F_SAME this adds is. Reading with EXPAND_FRAMES turns them all into full frames and the two
            // forms do not mix - ASM then writes a stack map table with offsets past the end of the code.
            reader.accept(node, 0);

            boolean picture = isPicture(node.superName) || isPicture(node.name);
            for (String each : node.interfaces) {
                picture = picture || isPicture(each);
            }

            boolean changed = false;
            List<MethodNode> methods = new ArrayList<MethodNode>(node.methods);
            for (MethodNode method : methods) {
                if (guard(internalName, method, node.version, picture)) {
                    changed = true;
                }
            }
            if (!changed) {
                return null;
            }

            // COMPUTE_MAXS, not COMPUTE_FRAMES: the existing frames are kept and only shifted, so ASM never
            // has to resolve a supertype through a classloader that is busy loading this very class.
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable failure) {
            // returning null leaves the class untouched: the feature degrades to "off" instead of a crash
            return null;
        }
    }

    private static boolean guard(String owner, MethodNode method, int version, boolean picture) {
        int access = method.access;
        // no code body to guard
        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return false;
        }
        // the picture first: a renderer or a model is data for the screen, and the screen has to work
        if (picture) {
            return false;
        }
        for (String only : PICTURE_METHODS) {
            if (only.equals(method.name)) {
                return false;
            }
        }
        // a member the game's own code calls: the game consumes its value, and a default is not a value
        if (CALLED_FROM_GAME.contains(owner + "." + method.name + method.desc)) {
            return false;
        }
        if (method.instructions == null || method.instructions.size() == 0) {
            return false;
        }
        // a constructor cannot return before the super call, and a class initialiser returning early would
        // leave the static fields null
        if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
            return false;
        }
        for (String never : NEVER) {
            if (never.equals(method.name)) {
                return false;
            }
        }
        // Idempotent, and it has to be: the launchwrapper chain guards a class and this agent guards the
        // same class again when it is defined. A second guard in front of the first is dead code the
        // verifier rejects, which is why this test is here as well as in AllReturn.
        for (AbstractInsnNode cursor = method.instructions.getFirst(); cursor != null;
                cursor = cursor.getNext()) {
            if (cursor.getOpcode() < 0) {
                continue;
            }
            if (cursor instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) cursor;
                if (field.getOpcode() == Opcodes.GETSTATIC
                        && FLAG_OWNER.equals(field.owner) && FLAG_NAME.equals(field.name)) {
                    return false;
                }
            }
            break;
        }

        // Objects and arrays are never given a default: null is not a value any caller can use, and the
        // classes that hand values out - models, textures, gui data, ActionResult - are the ones the game
        // still needs. primitives below are still zeroed, which is where the behaviour that has to be
        // neutralised lives.
        int sort = Type.getReturnType(method.desc).getSort();
        if (sort == Type.OBJECT || sort == Type.ARRAY) {
            return false;
        }
        Type returnType = Type.getReturnType(method.desc);
        InsnList injected = new InsnList();
        // a label, the flag test, the default return, the join point, a frame
        injected.add(new LabelNode());
        injected.add(new FieldInsnNode(Opcodes.GETSTATIC, FLAG_OWNER, FLAG_NAME, FLAG_DESC));
        LabelNode join = new LabelNode();
        injected.add(new JumpInsnNode(Opcodes.IFEQ, join));

        switch (returnType.getSort()) {
            case Type.VOID:
                injected.add(new InsnNode(Opcodes.RETURN));
                break;
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                injected.add(new InsnNode(Opcodes.ICONST_0));
                injected.add(new InsnNode(Opcodes.IRETURN));
                break;
            case Type.FLOAT:
                injected.add(new InsnNode(Opcodes.FCONST_0));
                injected.add(new InsnNode(Opcodes.FRETURN));
                break;
            case Type.LONG:
                injected.add(new InsnNode(Opcodes.LCONST_0));
                injected.add(new InsnNode(Opcodes.LRETURN));
                break;
            case Type.DOUBLE:
                injected.add(new InsnNode(Opcodes.DCONST_0));
                injected.add(new InsnNode(Opcodes.DRETURN));
                break;
            case Type.ARRAY:
            case Type.OBJECT:
                injected.add(new InsnNode(Opcodes.ACONST_NULL));
                injected.add(new InsnNode(Opcodes.ARETURN));
                break;
            default:
                // Type.METHOD never reaches a class file as a return type
                return false;
        }

        injected.add(join);
        // Frames can only be named in a class file that has a stack map table; below version 50 there is
        // none and the verifier infers them, and asking ASM to write one anyway produces a table ASM
        // cannot read back. That is what broke every class in codecjorbis (version 49) and with it all the
        // music, through forge's TerminalTransformer choking on the result.
        if ((version & 0xFFFF) >= Opcodes.V1_6) {
            injected.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        }

        method.instructions.insert(injected);
        return true;
    }

    public static String getStatus() {
        try {
            return System.getProperty(STATUS_PROPERTY, status);
        } catch (Throwable failure) {
            return status;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // the agent entry points
    // ---------------------------------------------------------------------------------------------

    /** JVM entry point for -javaagent. */
    public static void premain(String arguments, Instrumentation instrumentation) {
        install(instrumentation);
    }

    /** JVM entry point for the runtime attach path. */
    public static void agentmain(String arguments, Instrumentation instrumentation) {
        install(instrumentation);
    }

    private static void install(Instrumentation instrumentation) {
        if (instrumentation == null) {
            report("attach returned no Instrumentation");
            return;
        }
        try {
            instrumentation.addTransformer(new AgentTransformer(), false);
            report("agent transformer registered");
        } catch (Throwable failure) {
            report("addTransformer failed: " + failure);
        }
    }

    /** The outcome travels through a system property: the one channel both classloaders share. */
    static void report(String message) {
        status = message;
        try {
            System.setProperty(STATUS_PROPERTY, message);
        } catch (Throwable ignored) {
            // a security manager refusing the property is not worth failing the attach over
        }
    }

    /**
     * Only classes that go through LaunchClassLoader are rewritten. That is not a limitation, it is the
     * guarantee the injected guard needs: the guard is one GETSTATIC on AllReturn, and AllReturn is on
     * LaunchClassLoader's search path only. A class loaded by any other loader would fail to link.
     */
    public static class AgentTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            try {
                if (classfileBuffer == null || loader == null) {
                    return null;
                }
                if (!LAUNCH_CLASS_LOADER.equals(loader.getClass().getName())) {
                    return null;
                }
                if (Boolean.parseBoolean(System.getProperty("transcenmod.allReturn", "false"))) {
                    return null;
                }
                if (!isForeignJarClass(className, loader)) {
                    return null;
                }
                return rewrite(className, classfileBuffer);
            } catch (Throwable failure) {
                // throwing here aborts the class load and takes the game with it. null means "unchanged".
                return null;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // getting the agent into the JVM
    // ---------------------------------------------------------------------------------------------

    private static final String[] AGENT_CLASSES = {
            AGENT_CLASS,
            AGENT_CLASS + "$AgentTransformer"
    };

    public static void loadAsync() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                load();
            }
        }, "transcenmod-allreturn-agent");
        // must not hold the game open at shutdown
        thread.setDaemon(true);
        thread.start();
    }

    private static void load() {
        synchronized (LOCK) {
            if (attempted) {
                return;
            }
            attempted = true;
        }
        try {
            File jar = writeAgentJar();
            String pid = currentPid();
            if (pid == null) {
                report("could not determine the pid; agent not attached");
                return;
            }
            Class<?> virtualMachine = attachClass();
            if (virtualMachine == null) {
                report("tools.jar not found; agent not attached");
                return;
            }
            Object machine = virtualMachine.getMethod("attach", String.class).invoke(null, pid);
            try {
                virtualMachine.getMethod("loadAgent", String.class).invoke(machine, jar.getAbsolutePath());
                report("agent loaded");
            } finally {
                try {
                    virtualMachine.getMethod("detach").invoke(machine);
                } catch (Throwable ignored) {
                    // detaching is courtesy; failing it leaves the agent loaded, which is the goal
                }
            }
        } catch (Throwable failure) {
            // AllReturn.Transformer carries out the same rewrite, so a missing JDK costs only the
            // "lowest priority" half of the feature
            report("agent not loaded (" + failure + ")");
            System.err.println("[transcenmod] AllReturn agent not loaded: " + failure);
        }
    }

    /**
     * The attach API, from the classpath if it is there and out of tools.jar if it is not.
     *
     * The failure this fixes is not "the game runs on a JRE". It runs on a JDK here, and tools.jar sits in
     * it at 17 MB - but no launcher puts tools.jar on the classpath, so com.sun.tools.attach is simply not
     * findable and the agent silently never attaches. Loading it from disk costs nothing when it is already
     * present and is the only way it can work when it is not.
     *
     * TWO PATHS, and the obvious one is wrong on Java 8. java.home there is the jre subdirectory of the JDK,
     * so java.home/lib/tools.jar is <jdk>/jre/lib/tools.jar, which does not exist; the real file is
     * <jdk>/lib/tools.jar, one level up. Both are tried.
     *
     * jdk.attach also needs attach.dll, which sits at java.home/bin on Windows and is present in this JDK.
     * If it were missing the attach would still fail, which is why the coremod side injects the guard on its
     * own and does not depend on any of this.
     */
    private static Class<?> attachClass() {
        try {
            return Class.forName("com.sun.tools.attach.VirtualMachine");
        } catch (Throwable notOnClasspath) {
            // fall through to tools.jar
        }
        String home = System.getProperty("java.home");
        if (home == null) {
            return null;
        }
        File[] candidates = {
                new File(home, "lib" + File.separator + "tools.jar"),
                new File(new File(home, ".."), "lib" + File.separator + "tools.jar")
        };
        for (File tools : candidates) {
            if (!tools.isFile()) {
                continue;
            }
            try {
                URLClassLoader loader = new URLClassLoader(
                        new URL[]{tools.toURI().toURL()}, ClassLoader.getSystemClassLoader());
                return Class.forName("com.sun.tools.attach.VirtualMachine", true, loader);
            } catch (Throwable ignored) {
                // try the other location
            }
        }
        return null;
    }

    private static File writeAgentJar() throws Exception {
        File directory = new File(System.getProperty("java.io.tmpdir", "."), "transcenmod-agent");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("cannot create " + directory);
        }
        File jar = new File(directory, "transcenmod-allreturn-agent.jar");

        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(new Attributes.Name("Premain-Class"), AGENT_CLASS);
        attributes.put(new Attributes.Name("Agent-Class"), AGENT_CLASS);
        // no retransformation and no redefinition: the guard only has to reach classes that load from now
        // on, and both of these would widen what the agent may do to code that is already running
        attributes.put(new Attributes.Name("Can-Retransform-Classes"), "false");
        attributes.put(new Attributes.Name("Can-Redefine-Classes"), "false");

        JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest);
        try {
            for (String className : AGENT_CLASSES) {
                byte[] bytes = readClass(className);
                out.putNextEntry(new JarEntry(className.replace('.', '/') + ".class"));
                out.write(bytes);
                out.closeEntry();
            }
        } finally {
            out.close();
        }
        return jar;
    }

    private static byte[] readClass(String className) throws Exception {
        String resource = "/" + className.replace('.', '/') + ".class";
        InputStream in = AllReturnAgent.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("missing resource " + resource);
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(16384);
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String currentPid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            String pid = at > 0 ? name.substring(0, at) : name;
            return pid.isEmpty() ? null : pid;
        } catch (Throwable failure) {
            return null;
        }
    }
}
