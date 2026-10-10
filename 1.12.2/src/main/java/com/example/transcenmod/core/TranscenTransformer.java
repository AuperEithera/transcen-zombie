package com.example.transcenmod.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The 1.12.2 replacement for the two Sponge Mixins the 1.16.5 build used.
 *
 * <p>1.16.5 shipped {@code PlayerHealthMixin} ({@code @Inject} at the head of {@code LivingEntity#getHealth})
 * and {@code HudSuppressionMixin} ({@code @Inject} at the head of {@code ForgeIngameGui#pre}/{@code post}).
 * 1.12.2's Forge has no Mixin loader and no {@code ForgeIngameGui}, so both are re-expressed here through
 * Forge's own class transformer hook:
 *
 * <ul>
 *   <li>{@code EntityLivingBase#getHealth()F} -- one early return that consults
 *       {@link CoreHooks#getHealthOverride}. This is what actually makes the egg carrier unkillable; no
 *       damage event was ever enough, because the red lightning runs {@code attackEntityFrom} and then
 *       {@code setHealth(0)} on the next line with no tick between them. The method is {@code final} in
 *       vanilla, so {@code META-INF/accesstransformer.cfg} drops the finality for the subclass that also
 *       overrides it -- and that cfg is only read because the jar manifest declares it as {@code FMLAT}.</li>
 *   <li>{@code GuiBossOverlay#renderBossHealth()V} -- one early return that consults
 *       {@link CoreHooks#shouldSuppressBossHealth}. 1.12.2 draws its whole boss bar pass in that single
 *       method, so an early return there is the exact equivalent of the Forge-side suppression the 1.16.5
 *       mixin performed on {@code pre}/{@code post}. The 1.16.5 target ({@code ForgeIngameGui}) does not
 *       exist in this generation at all; the overlay class is what replaced it.</li>
 * </ul>
 *
 * <p>Both injections are one-sided on purpose. Mixin handed its injections a callback object to write a
 * return value into; ASM has no such thing, so the decision is made in {@link CoreHooks} and the injected
 * code is only the branch that consumes it. That also keeps every Minecraft type out of this class, which
 * matters: this runs on the launch classloader before the mod is constructed, and naming a mod-owned class
 * here would be a {@code NoClassDefFoundError} on the first transformed class.
 *
 * <p><b>How the injection is emitted, and why it is done this way.</b> Two rules came out of testing this
 * transformer against stand-in copies of both targets and loading the results (see
 * {@code dev-tests/TransformerHarness}), and both produce a silently dead feature rather than a compile
 * error:
 *
 * <ol>
 *   <li><b>The code is emitted from {@code visitCode}, not from {@code visitMaxs}.</b> Instructions emitted
 *       during {@code visitMaxs} are appended to the method's instruction list, i.e. after the original
 *       body. For {@code renderBossHealth} that is invisible because the original body ends in
 *       {@code RETURN}; for {@code getHealth} it is fatal, because the original body ends in
 *       {@code FRETURN} and everything appended after it is unreachable. The symptom is a constant pool
 *       that mentions the hook while the method never calls it.</li>
 *   <li><b>Every new branch target carries an explicit stack map frame.</b> {@code getHealth} already has
 *       one, so a branch target without a frame is a {@code VerifyError} at class load: "Expecting a
 *       stackmap frame at branch target". {@code F_SAME} is correct here because the injected branch
 *       merges an empty operand stack into the method's entry frame.</li>
 * </ol>
 *
 * <p>The consequence of rule 1 is that the local slot for the boxed override cannot be taken from
 * {@code maxLocals}, which is only known at the end of the write pass. A read pass over the same method
 * therefore runs first, and the slot is allocated above it.
 *
 * <p>Names are matched as a list rather than as one literal. In a development workspace the classes arrive
 * already deobfuscated, so the MCP names resolve; in a production jar they arrive under SRG names.
 * Matching both means one transformer works in either. The SRG names came from the MCP stable_39 mapping
 * table ({@code func_110143_aJ} and {@code func_184051_a}).
 */
public class TranscenTransformer implements IClassTransformer {

    private static final String ENTITY_LIVING_BASE = "net/minecraft/entity/EntityLivingBase";
    private static final String BOSS_OVERLAY = "net/minecraft/client/gui/GuiBossOverlay";
    private static final String HOOKS = "com/example/transcenmod/core/CoreHooks";

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

        boolean healthTarget = ENTITY_LIVING_BASE.equals(internal);
        boolean bossBarTarget = BOSS_OVERLAY.equals(internal);
        if (!healthTarget && !bossBarTarget) {
            return basicClass;
        }

        try {
            ClassReader reader = new ClassReader(basicClass);
            // Frames are recomputed rather than carried over. The injected branch adds a target the
            // original method did not have, and a stale frame table would describe the wrong offsets. The
            // merges involved are empty-stack-into-empty-stack, so the recomputation never has to resolve
            // a Minecraft class hierarchy.
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = healthTarget
                    ? new HealthVisitor(writer, freeSlot(reader, GET_HEALTH_NAMES, GET_HEALTH_DESC))
                    : bossBarVisitor(writer);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Throwable failure) {
            // A transformer that throws takes the whole game down at class load, with a stack trace the
            // player cannot act on. Leaving the class untouched degrades to "this one feature is off".
            System.err.println("[transcenmod] coremod transform failed for " + internal + ": " + failure);
            failure.printStackTrace();
            return basicClass;
        }
    }

    /**
     * A local slot the target method does not use: one past everything it declares.
     *
     * <p>Read in a separate pass because {@code maxLocals} is only reported at the end of a write pass, and
     * the injection has to be emitted at the start of one. The reader passed in is only walked, never
     * mutated, so it does not disturb the write pass that follows.
     */
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

    // ---------------------------------------------------------------------------------------------
    // EntityLivingBase#getHealth()F
    // ---------------------------------------------------------------------------------------------

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
        /** Slot for the boxed override, allocated one past the target method's own frame size. */
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

        /**
         * Emitted at the head of {@code getHealth}:
         *
         * <pre>
         * Float override = CoreHooks.getHealthOverride(this.getUniqueID());
         * if (override != null) {
         *     return override.floatValue();
         * }
         * </pre>
         *
         * <p>The value goes into a local rather than being duplicated on the stack. {@code DUP} is the
         * obvious writing and it produces unverifiable bytecode: the branch target would then be reached
         * with one operand stack slot the fall-through path does not have, and the verifier rejects the
         * method with "Current frame's stack size doesn't match stackmap". A local has the same value on
         * both paths.
         *
         * <p>The null test is the whole reason this returns a boxed value instead of a primitive with a
         * sentinel. An entity that is not one of this mod's protected players has to keep answering with
         * vanilla's own stored field, and returning from the head of the method is the only way to leave
         * that path completely alone.
         */
        private void emitGetHealthOverride() {
            Label vanilla = new Label();

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY_LIVING_BASE, "getUniqueID",
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
            // method's entry locals, which is exactly what "same frame" means.
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GuiBossOverlay#renderBossHealth()V
    // ---------------------------------------------------------------------------------------------

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

    /**
     * Emitted at the head of {@code renderBossHealth}:
     *
     * <pre>
     * if (CoreHooks.shouldSuppressBossHealth()) {
     *     return;
     * }
     * </pre>
     *
     * <p>This one needs no explicit frame -- unlike the health injection -- because its branch target is
     * the method's own {@code RETURN}, which vanilla already frames. Adding one here would add a second
     * frame for the same offset.
     */
    private static void emitBossBarSuppression(MethodVisitor mv) {
        Label draw = new Label();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "shouldSuppressBossHealth", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, draw);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(draw);
    }

    private static boolean matches(String methodName, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(methodName)) {
                return true;
            }
        }
        return false;
    }
}
