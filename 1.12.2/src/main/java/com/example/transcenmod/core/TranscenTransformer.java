package com.example.transcenmod.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The coremod's bytecode transformer. One file, three jobs, because they are all the same job: rewriting
 * classes before the game loads them.
 *
 *   1. EntityLivingBase#getHealth()F - the health override the mod pushes through CoreHooks.
 *   2. GuiBossOverlay#renderBossHealth()V - boss bar suppression during an encounter.
 *   3. The entity guards: a guard at the head of every entry point that takes an entity out of, or puts an
 *      entity into, a world or a chunk, plus a rewrite of the calls minecraft itself makes on its entity
 *      lists.
 *
 * (3) is two features and is described where it is implemented. The shape of what it injects is a label, the
 * test, the default return, the join point, and a compressed F_SAME frame node.
 *
 * NAME MATCHING, which is the part that fails silently. A coremod transformer runs before Forge's
 * DeobfuscationTransformer in 1.12.2, so in production the names inside the bytecode are still the
 * obfuscated ones: World.removeEntity is amu.e(Lvg;)V in the 1.12.2 client. The class name handed to
 * transform() is already the SRG form, because LaunchClassLoader pipes it through
 * FMLDeobfuscatingRemapper.remapClassName, and every owner/name/descriptor in the tables below was read
 * out of the pairs in Forge's own deobfuscation_data-1.12.2.lzma. Members are matched through that same
 * remapper as well, so one table also works after deobfuscation and in a development workspace, where the
 * plain names are already correct. Both names are tried in every entry, following the pattern the two
 * original injections already used.
 */
public class TranscenTransformer implements IClassTransformer {

    private static final String ENTITY_LIVING_BASE = "net/minecraft/entity/EntityLivingBase";
    private static final String BOSS_OVERLAY = "net/minecraft/client/gui/GuiBossOverlay";
    private static final String HOOKS = "com/example/transcenmod/core/CoreHooks";
    private static final String MC_PREFIX = "net/minecraft/";

    private static final String GET_HEALTH_DESC = "()F";
    private static final String RENDER_BOSS_HEALTH_DESC = "()V";

    private static final String[] GET_HEALTH_NAMES = {
            "getHealth",  // MCP name, development workspace
            "func_110143_aJ"  // SRG name, production jar
    };

    private static final String[] RENDER_BOSS_HEALTH_NAMES = {
            "renderBossHealth",  // MCP name, development workspace
            "func_184051_a"  // SRG name, production jar
    };

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }

        // transformedName is the authority; `name` is whatever the caller passed and is blank on the
        // direct classloader path.
        String internal = transformedName != null ? transformedName.replace('.', '/') : null;
        if (internal == null) {
            internal = name == null ? null : name.replace('.', '/');
        }
        if (internal == null) {
            return basicClass;
        }

        if (ENTITY_LIVING_BASE.equals(internal)) {
            return injectHealth(basicClass, internal);
        }
        if (BOSS_OVERLAY.equals(internal)) {
            return injectBossBarSuppression(basicClass, internal);
        }
        if (internal.startsWith(MC_PREFIX)) {
            return guardEntities(basicClass, internal);
        }
        return basicClass;
    }

    private static void report(String internal, Throwable failure) {
        // A transformer that throws takes the whole game down at class load, with a stack trace the player
        // cannot act on. Leaving the class untouched degrades to "this one feature is off".
        System.err.println("[transcenmod] coremod transform failed for " + internal + ": " + failure);
        failure.printStackTrace();
    }

    // =============================================================================================
    // 1. EntityLivingBase#getHealth()F
    // =============================================================================================

    private static byte[] injectHealth(byte[] basicClass, String internal) {
        try {
            ClassReader reader = new ClassReader(basicClass);
            // Frames are recomputed rather than carried over. The injected branch adds a target the
            // original method did not have, and a stale frame table would describe the wrong offsets. The
            // merges involved are empty-stack-into-empty-stack, so the recomputation never has to resolve
            // a Minecraft class hierarchy.
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new HealthVisitor(writer, freeSlot(reader, GET_HEALTH_NAMES, GET_HEALTH_DESC));
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Throwable failure) {
            report(internal, failure);
            return basicClass;
        }
    }

    private static int freeSlot(ClassReader reader, final String[] names, final String descriptor) {
        final int[] found = new int[] { -1 };
        reader.accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String desc,
                                             String signature, String[] exceptions) {
                if (!descriptor.equals(desc) || !matches(methodName, names)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        found[0] = maxLocals;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return found[0] < 0 ? 1 : found[0];
    }

    private static final class HealthVisitor extends ClassVisitor {
        private final int slot;

        HealthVisitor(ClassVisitor next, int slot) {
            super(Opcodes.ASM5, next);
            this.slot = slot;
        }

        @Override
        public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, methodName, descriptor, signature, exceptions);
            if (mv == null || !GET_HEALTH_DESC.equals(descriptor)
                    || !matches(methodName, GET_HEALTH_NAMES)) {
                return mv;
            }
            return new HealthInjector(mv, slot);
        }
    }

    private static final class HealthInjector extends MethodVisitor {
        private final int slot;

        HealthInjector(MethodVisitor next, int slot) {
            super(Opcodes.ASM5, next);
            this.slot = slot;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitGetHealthOverride();
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // The injected prefix pushes two operands at its deepest (the UUID, then the boxed Float) and
            // stores one local. Both sizes are folded in here so the method's declared frame is always at
            // least what the injected code needs.
            super.visitMaxs(Math.max(maxStack, 2), Math.max(maxLocals, slot + 1));
        }

        private void emitGetHealthOverride() {
            Label vanilla = new Label();

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY_LIVING_BASE,
                    runtimeName(ENTITY_LIVING_BASE, "getUniqueID", "func_110124_au"),
                    "()Ljava/util/UUID;", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "getHealthOverride",
                    "(Ljava/util/UUID;)Ljava/lang/Float;", false);
            mv.visitVarInsn(Opcodes.ASTORE, slot);
            mv.visitVarInsn(Opcodes.ALOAD, slot);
            mv.visitJumpInsn(Opcodes.IFNULL, vanilla);
            mv.visitVarInsn(Opcodes.ALOAD, slot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
            mv.visitInsn(Opcodes.FRETURN);
            mv.visitLabel(vanilla);
            // F_SAME: the branch and the fall-through both arrive with an empty operand stack and the
            // method's entry locals, which is exactly what the F_SAME frame records.
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
    }

    // =============================================================================================
    // 2. GuiBossOverlay#renderBossHealth()V
    // =============================================================================================

    private static byte[] injectBossBarSuppression(byte[] basicClass, String internal) {
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            reader.accept(bossBarVisitor(writer), ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Throwable failure) {
            report(internal, failure);
            return basicClass;
        }
    }

    private static ClassVisitor bossBarVisitor(ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM5, next) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, methodName, descriptor, signature, exceptions);
                if (mv == null || !RENDER_BOSS_HEALTH_DESC.equals(descriptor)
                        || !matches(methodName, RENDER_BOSS_HEALTH_NAMES)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        emitBossBarSuppression(this);
                    }
                };
            }
        };
    }

    private static void emitBossBarSuppression(MethodVisitor mv) {
        Label draw = new Label();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "shouldSuppressBossHealth", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, draw);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(draw);
    }

    private static boolean matches(String methodName, String[] candidates) {
        return methodName != null && Arrays.asList(candidates).contains(methodName);
    }

    // =============================================================================================
    // 3. the entity guards
    //
    // Two features, one mechanism:
    //
    //   removal - once an attempt is on the protected entity, it is refused. The guard goes on the entry
    //             points rather than on the call sites, because a call-site rewrite only covers the
    //             classes it is applied to, and a direct call into World.removeEntityDangerously from
    //             anywhere else has to be stopped too.
    //   addition - once an attempt is on a class in TranscenMod.BANNED_CLASSES, it is refused. The class
    //             list already existed and was enforced at EntityJoinWorldEvent; a spawn that skipped the
    //             event bypassed it, and this is what closes that.
    //
    // Plus a rewrite of the calls minecraft itself makes on its entity lists (List.remove, Set.remove,
    // Map.remove, List.remove(int), removeAll, add, addAll), re-pointed at CoreHooks. Those lists are plain
    // java.util collections: rewriting ArrayList would change it for the whole process, so what
    // changes is the call sites minecraft itself uses. That is also why this stops at net.minecraft - the
    // same substitution applied to every class in the process would be a change to untestable code.
    //
    // All decisions are taken at run time by CoreHooks, so a class is never rewritten differently depending
    // on when it happened to load.
    // =============================================================================================

    private static final String OBJECT_DESC = "Ljava/lang/Object;";

    // The descriptor of the method, not of its argument. These were argument descriptors in the first
    // draft, and the result was a transformer that matched nothing at all; verifyEntityGuard caught it.
    private static final String ENTITY_VOID = "(Lnet/minecraft/entity/Entity;)V";
    private static final String ENTITY_BOOL = "(Lnet/minecraft/entity/Entity;)Z";
    private static final String ENTITY_INDEX_VOID = "(Lnet/minecraft/entity/Entity;I)V";
    private static final String OBJECT_BOOL = "(Ljava/lang/Object;)Z";

    private static final int KIND_VOID = 0;
    private static final int KIND_FALSE = 1;

    private static final class HeadGuard {
        final String owner;
        final String[] names;
        final String desc;
        final String hook;
        final int kind;

        HeadGuard(String owner, String desc, String hook, int kind, String... names) {
            this.owner = owner;
            this.names = names;
            this.desc = desc;
            this.hook = hook;
            this.kind = kind;
        }
    }

    // The removal entry points. Each is a place where an entity stops existing as far as the world is
    // concerned, and each is reachable without going through EventBus, which is why the injection is here.
    //
    // Every owner/name/descriptor below was checked against the pairs in Forge's own
    // deobfuscation_data-1.12.2.lzma. The line numbers are positions in the decompiled 14.23.5.2860 source
    // tree.
    private static final HeadGuard[] REMOVALS = {
            // World:1236 - sets isDead plus the two dismount paths. Entity#setDead is not guarded: this
            // mod's zombie already overrides it as a no-op, and guarding it would also block the mod's own
            // forceRemove() escape hatch.
            new HeadGuard("net/minecraft/world/World", ENTITY_VOID, "refuseRemoval", KIND_VOID,
                    "removeEntity", "func_72900_e"),
            // World:1258 - the one the mod's own EntityWiper uses, and the one a forced removal from
            // outside comes through.
            new HeadGuard("net/minecraft/world/World", ENTITY_VOID, "refuseRemoval", KIND_VOID,
                    "removeEntityDangerously", "func_72973_f"),
            // World:1227 - detaches the entity from every IWorldEventListener, which on the server is
            // ServerWorldEventHandler (entity tracker + scoreboard) and on the client is RenderGlobal.
            new HeadGuard("net/minecraft/world/World", ENTITY_VOID, "refuseRemoval", KIND_VOID,
                    "onEntityRemoved", "func_72847_b"),
            // WorldServer:1126 - also drops entitiesById and entitiesByUuid. Guarding this is what keeps the
            // zombie resolvable by id and by UUID.
            new HeadGuard("net/minecraft/world/WorldServer", ENTITY_VOID, "refuseRemoval", KIND_VOID,
                    "onEntityRemoved", "func_72847_b"),
            // WorldClient:214 / WorldClient:230 - the client's own entityList is trimmed here.
            new HeadGuard("net/minecraft/client/multiplayer/WorldClient", ENTITY_VOID, "refuseRemoval", KIND_VOID,
                    "removeEntity", "func_72900_e"),
            new HeadGuard("net/minecraft/client/multiplayer/WorldClient", ENTITY_VOID, "refuseRemoval", KIND_VOID,
                    "onEntityRemoved", "func_72847_b"),
            // Chunk:748 - the chunk section is the second place the render loop reads from, and the first
            // place World.updateEntities' dead branch removes from.
            new HeadGuard("net/minecraft/world/chunk/Chunk", ENTITY_VOID, "refuseRemoval", KIND_VOID,
                    "removeEntity", "func_76622_b"),
            // Chunk:753 - what World.updateEntity calls when an entity crosses a section boundary.
            new HeadGuard("net/minecraft/world/chunk/Chunk", ENTITY_INDEX_VOID, "refuseRemoval", KIND_VOID,
                    "removeEntityAtIndex", "func_76608_a"),
            // EntityPlayerMP:677 region / EntityTrackerEntry:357,366,467,669 - this is how each client is
            // told to discard the entity. Blocking it keeps the client's copy, the one the player sees.
            new HeadGuard("net/minecraft/entity/player/EntityPlayerMP", ENTITY_VOID, "refuseRemoval", KIND_VOID,
                    "removeEntity", "func_152339_d"),
            // EntityTracker:265 - the server side track entry. Kept alive so the zombie keeps being sent.
            new HeadGuard("net/minecraft/entity/EntityTracker", ENTITY_VOID, "refuseRemoval", KIND_VOID,
                    "untrack", "func_72790_b"),
            // ClassInheritanceMultiMap, which is what a chunk section actually is. add and remove were never
            // obfuscated on this class - the deobf data spells both names out.
            new HeadGuard("net/minecraft/util/ClassInheritanceMultiMap", OBJECT_BOOL, "refuseRemoval", KIND_FALSE,
                    "remove")
    };

    // The addition entry points. Everything that puts a banned type into a world, a chunk or a chunk
    // section passes through one of these, whichever route the caller took.
    private static final HeadGuard[] ADDITIONS = {
            // World:1182 - the route every /summon, spawn egg and spawner uses, and the only one that fires
            // EntityJoinWorldEvent. The event handler alone can be bypassed; this cannot.
            new HeadGuard("net/minecraft/world/World", ENTITY_BOOL, "refuseAdd", KIND_FALSE,
                    "spawnEntity", "func_72838_d"),
            // World:1218 - the notification half of the same thing.
            new HeadGuard("net/minecraft/world/World", ENTITY_VOID, "refuseAdd", KIND_VOID,
                    "onEntityAdded", "func_72923_a"),
            // World:3540 region - the silent loadedEntityList.add that skips the event entirely.
            new HeadGuard("net/minecraft/world/World", ENTITY_VOID, "refuseAdd", KIND_VOID,
                    "joinEntityInSurroundings", "func_72897_h"),
            new HeadGuard("net/minecraft/world/World", ENTITY_BOOL, "refuseAdd", KIND_FALSE,
                    "addWeatherEffect", "func_72942_c"),
            new HeadGuard("net/minecraft/world/WorldServer", ENTITY_BOOL, "refuseAdd", KIND_FALSE,
                    "spawnEntity", "func_72838_d"),
            new HeadGuard("net/minecraft/world/WorldServer", ENTITY_VOID, "refuseAdd", KIND_VOID,
                    "onEntityAdded", "func_72923_a"),
            new HeadGuard("net/minecraft/client/multiplayer/WorldClient", ENTITY_BOOL, "refuseAdd", KIND_FALSE,
                    "spawnEntity", "func_72838_d"),
            new HeadGuard("net/minecraft/client/multiplayer/WorldClient", ENTITY_VOID, "refuseAdd", KIND_VOID,
                    "onEntityAdded", "func_72923_a"),
            // Chunk:715 - a banned type must not even reach a chunk section.
            new HeadGuard("net/minecraft/world/chunk/Chunk", ENTITY_VOID, "refuseAdd", KIND_VOID,
                    "addEntity", "func_76612_a"),
            new HeadGuard("net/minecraft/util/ClassInheritanceMultiMap", OBJECT_BOOL, "refuseAdd", KIND_FALSE,
                    "add")
    };

    private static byte[] guardEntities(byte[] basicClass, String internal) {
        HeadGuard[] guards = guardsFor(internal);
        // Resolved once per class and only for the handful of classes that have guards: the remapper reads
        // a class file off disk the first time it is asked about an owner, and no other class needs it.
        String obfOwner = guards.length == 0 ? null : Names.unmap(internal);
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            // flag 0, deliberately: the existing stack map frames stay in their compressed form
            // (F_SAME/F_APPEND/F_CHOP/F_SAME1/F_FULL) so the F_SAME this adds is the same kind of thing.
            // Reading with EXPAND_FRAMES instead turns them all into full frames, and the two forms do not
            // mix - ASM then writes a stack map table whose offsets run past the end of the code and the
            // verifier rejects the class.
            reader.accept(node, 0);

            boolean changed = false;
            List<MethodNode> methods = new ArrayList<MethodNode>(node.methods);
            for (MethodNode method : methods) {
                if (redirectCalls(method)) {
                    changed = true;
                }
                if (guardHead(method, find(guards, obfOwner, method.name, method.desc))) {
                    changed = true;
                }
            }
            if (!changed) {
                return basicClass;
            }

            // COMPUTE_MAXS, not COMPUTE_FRAMES. COMPUTE_FRAMES would recompute every frame in the method
            // and would have to resolve supertypes through a classloader that is busy loading this very
            // class; keeping the frames and only inserting one avoids that entirely.
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable failure) {
            report(internal, failure);
            return basicClass;
        }
    }

    private static HeadGuard[] guardsFor(String internal) {
        List<HeadGuard> found = new ArrayList<HeadGuard>(2);
        for (HeadGuard guard : REMOVALS) {
            if (guard.owner.equals(internal)) {
                found.add(guard);
            }
        }
        for (HeadGuard guard : ADDITIONS) {
            if (guard.owner.equals(internal)) {
                found.add(guard);
            }
        }
        return found.toArray(new HeadGuard[found.size()]);
    }

    private static HeadGuard find(HeadGuard[] guards, String obfOwner, String methodName, String descriptor) {
        if (guards.length == 0) {
            return null;
        }
        // first the plain comparison, which is right after deobfuscation and in a development workspace
        for (HeadGuard guard : guards) {
            if (guard.desc.equals(descriptor) && matches(methodName, guard.names)) {
                return guard;
            }
        }
        // then the same question with both members put through the remapper, which is right before
        // deobfuscation, where "removeEntity(Entity)" is still "e(Lvg;)V"
        String mappedName = Names.methodName(obfOwner, methodName, descriptor);
        String mappedDesc = Names.methodDesc(descriptor);
        if (mappedName == null || mappedDesc == null) {
            return null;
        }
        for (HeadGuard guard : guards) {
            if (guard.desc.equals(mappedDesc) && matches(mappedName, guard.names)) {
                return guard;
            }
        }
        return null;
    }

    private static boolean guardHead(MethodNode method, HeadGuard guard) {
        if (guard == null || method.instructions == null || method.instructions.size() == 0) {
            return false;
        }
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return false;
        }

        InsnList injected = new InsnList();
        injected.add(new LabelNode());
        // every target is an instance method, so the entity (or the collection element) is in slot 1
        injected.add(new VarInsnNode(Opcodes.ALOAD, 1));
        injected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, guard.hook,
                "(" + OBJECT_DESC + ")Z", false));
        LabelNode join = new LabelNode();
        injected.add(new JumpInsnNode(Opcodes.IFEQ, join));
        if (guard.kind == KIND_FALSE) {
            injected.add(new InsnNode(Opcodes.ICONST_0));
            injected.add(new InsnNode(Opcodes.IRETURN));
        } else {
            injected.add(new InsnNode(Opcodes.RETURN));
        }
        injected.add(join);
        // the join arrives with the method's entry locals and an empty stack, which is what an F_SAME
        // frame records
        injected.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(injected);
        return true;
    }

    private static boolean redirectCalls(MethodNode method) {
        if (method.instructions == null) {
            return false;
        }
        boolean changed = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKEINTERFACE) {
                continue;
            }
            String hook = redirectHook(call.owner, call.name, call.desc);
            if (hook == null) {
                continue;
            }
            // the receiver is already on the stack underneath the arguments, so it becomes the static
            // call's first parameter
            method.instructions.set(call,
                    new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, hook, staticDescriptor(hook), false));
            changed = true;
        }
        return changed;
    }

    private static String redirectHook(String owner, String methodName, String descriptor) {
        // Only java.util receivers. An mc class that implements Collection (ClassInheritanceMultiMap) has
        // its own guard at the head of add/remove instead, and fastutil's Int2ObjectMap.remove(int) is left
        // alone because an integer key says nothing about which entity it belongs to.
        if (owner == null || !owner.startsWith("java/util/") || methodName == null) {
            return null;
        }
        if ("remove".equals(methodName)) {
            if (("(" + OBJECT_DESC + ")Z").equals(descriptor)) {
                return "removeFromCollection";
            }
            if (("(" + OBJECT_DESC + ")" + OBJECT_DESC).equals(descriptor)) {
                return "removeFromMap";
            }
            if (("(I)" + OBJECT_DESC).equals(descriptor)) {
                return "removeFromListAt";
            }
        } else if ("removeAll".equals(methodName) && "(Ljava/util/Collection;)Z".equals(descriptor)) {
            return "removeAllFromCollection";
        } else if ("add".equals(methodName) && ("(" + OBJECT_DESC + ")Z").equals(descriptor)) {
            return "addToCollection";
        } else if ("addAll".equals(methodName) && "(Ljava/util/Collection;)Z".equals(descriptor)) {
            return "addAllToCollection";
        }
        return null;
    }

    private static String staticDescriptor(String hook) {
        if ("removeFromMap".equals(hook)) {
            return "(Ljava/util/Map;" + OBJECT_DESC + ")" + OBJECT_DESC;
        }
        if ("removeFromListAt".equals(hook)) {
            return "(Ljava/util/List;I)" + OBJECT_DESC;
        }
        if ("removeAllFromCollection".equals(hook) || "addAllToCollection".equals(hook)) {
            return "(Ljava/util/Collection;Ljava/util/Collection;)Z";
        }
        return "(Ljava/util/Collection;" + OBJECT_DESC + ")Z";
    }

    /**
     * FMLDeobfuscatingRemapper, wrapped so that a Forge without it degrades to "compare the names as they
     * are" instead of failing the transform. This mirrors Forge's own coremod transformers
     * (FMLRemappingAdapter:115 calls mapMethodName on the unmapped name and descriptor).
     */
    /**
     * The name a vanilla method has at runtime, chosen by asking Forge's remapper whether it knows the
     * class, rather than guessed from the name the transformer was handed.
     *
     * Two earlier versions were wrong. The first hardcoded MCP's getUniqueID, which does not exist in the
     * obfuscated jar. The second tested !name.equals(transformedName) and got false in production:
     * LaunchClassLoader computes transformedName with IClassNameTransformer#renameClass, and
     * DeobfuscationTransformer#renameClass is FMLDeobfuscatingRemapper#map, which maps obfuscated to SRG. A
     * class named by its SRG name therefore arrives with both strings equal - and that is the case the mod
     * injects into, so the test said "development" while the game was obfuscated.
     *
     * mapMethodName cannot answer it: FMLDeobfuscatingRemapper#parseMethod keys that table with the
     * obfuscated member name, so an MCP name is never a key and the lookup returns its input unchanged.
     * unmap is the honest question - it returns the obfuscated name in production and the argument unchanged
     * when no mapping table was loaded, which is the development workspace.
     */
    private static String runtimeName(String owner, String mcpName, String srgName) {
        return runtimeNameFor(Names.unmap(owner), owner, mcpName, srgName);
    }

    /**
     * The decision itself, split out so the harness can drive both branches. unmap only answers for the
     * environment the game is actually in, so a test that goes through it can only ever exercise one of the
     * two - and the one that matters is the production branch, which cannot be reached from a development
     * workspace. unmap returning something other than its argument means a mapping table was loaded, which
     * is production; returning the argument unchanged means the development workspace, where the MCP name
     * already is the runtime name.
     */
    public static String runtimeNameFor(String unmappedOwner, String owner, String mcpName, String srgName) {
        return unmappedOwner != null && !unmappedOwner.equals(owner) ? srgName : mcpName;
    }

    private static final class Names {

        private static final Object REMAPPER = resolve();

        private static Object resolve() {
            try {
                return net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper.INSTANCE;
            } catch (Throwable failure) {
                return null;
            }
        }

        static String unmap(String typeName) {
            Object remapper = REMAPPER;
            if (remapper == null) {
                return typeName;
            }
            try {
                return ((net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper) remapper)
                        .unmap(typeName);
            } catch (Throwable failure) {
                return typeName;
            }
        }

        static String methodName(String owner, String methodName, String descriptor) {
            Object remapper = REMAPPER;
            if (remapper == null) {
                return null;
            }
            try {
                return ((net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper) remapper)
                        .mapMethodName(owner, methodName, descriptor);
            } catch (Throwable failure) {
                return null;
            }
        }

        static String methodDesc(String descriptor) {
            Object remapper = REMAPPER;
            if (remapper == null) {
                return null;
            }
            try {
                // mapMethodDesc is inherited from org.objectweb.asm.commons.Remapper
                return ((net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper) remapper)
                        .mapMethodDesc(descriptor);
            } catch (Throwable failure) {
                return null;
            }
        }
    }
}
