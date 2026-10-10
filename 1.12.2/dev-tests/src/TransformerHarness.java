import com.example.transcenmod.core.CoreHooks;
import com.example.transcenmod.core.TranscenTransformer;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Standalone harness for the coremod transformer.
 *
 * <p>Runs the real {@link TranscenTransformer} over stand-in copies of the two target classes, loads the
 * results through a fresh classloader -- which is what actually forces the JVM verifier to accept or reject
 * the rewritten bytecode -- and then checks that both injections do what they claim.
 *
 * <p>A transformer that emits unverifiable bytecode, or that appends its injection after the original
 * return, is the one failure mode of a coremod that a compile cannot catch. It surfaces in game as a
 * VerifyError at class load or as a feature that silently does nothing.
 */
public final class TransformerHarness {

    private static final String LIVING_BASE = "net.minecraft.entity.EntityLivingBase";
    private static final String BOSS_OVERLAY = "net.minecraft.client.gui.GuiBossOverlay";

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        File classesDir = new File(args[0]);
        File outDir = new File(args[1]);
        outDir.mkdirs();

        TranscenTransformer transformer = new TranscenTransformer();

        rewrite(transformer, classesDir, outDir, LIVING_BASE);
        rewrite(transformer, classesDir, outDir, BOSS_OVERLAY);

        // -----------------------------------------------------------------------------------------
        // Health
        // -----------------------------------------------------------------------------------------
        Class<?> living = new ByteLoader(outDir).loadClass(LIVING_BASE);
        Object plain = living.getDeclaredConstructor().newInstance();

        float vanilla = (Float) living.getMethod("getHealth").invoke(plain);
        check("an unprotected entity keeps its own stored health", vanilla == 7.5F, "got " + vanilla);

        UUID plainId = (UUID) living.getMethod("getUniqueID").invoke(plain);
        check("getUniqueID is still reachable after injection", plainId != null, "null uuid");

        Object protectedEntity = living.getDeclaredConstructor().newInstance();
        UUID protectedId = (UUID) living.getMethod("getUniqueID").invoke(protectedEntity);
        CoreHooks.setProtected(protectedId, true);
        float pin = (Float) living.getMethod("getHealth").invoke(protectedEntity);
        check("a protected entity reports 20", pin == 20.0F, "got " + pin);

        // The override is a read, not a write: the stored field stays where the entity put it, and the hook
        // still answers over the top of a direct setHealth(0) -- which is the whole mechanism, because the
        // red lightning calls attackEntityFrom and then setHealth(0) with no tick between them.
        living.getMethod("setHealthDirectly", float.class).invoke(protectedEntity, 0.0F);
        float afterZeroWrite = (Float) living.getMethod("getHealth").invoke(protectedEntity);
        check("the override survives a direct setHealth(0)", afterZeroWrite == 20.0F,
                "got " + afterZeroWrite);

        CoreHooks.setProtected(protectedId, false);
        float unpinned = (Float) living.getMethod("getHealth").invoke(protectedEntity);
        check("clearing protection restores the stored value", unpinned == 0.0F, "got " + unpinned);

        // Retaliation outranks the egg.
        Object retaliating = living.getDeclaredConstructor().newInstance();
        UUID retaliatingId = (UUID) living.getMethod("getUniqueID").invoke(retaliating);
        CoreHooks.setProtected(retaliatingId, true);
        CoreHooks.setRetaliating(retaliatingId, true);
        float retaliated = (Float) living.getMethod("getHealth").invoke(retaliating);
        check("retaliation outranks the egg and reports 0", retaliated == 0.0F, "got " + retaliated);

        CoreHooks.clear(retaliatingId);
        float cleared = (Float) living.getMethod("getHealth").invoke(retaliating);
        check("clear() drops both sets", cleared == 7.5F, "got " + cleared);

        // -----------------------------------------------------------------------------------------
        // Boss bar
        // -----------------------------------------------------------------------------------------
        Class<?> overlayClass = new ByteLoader(outDir).loadClass(BOSS_OVERLAY);
        Object overlay = overlayClass.getDeclaredConstructor().newInstance();

        CoreHooks.setEncounterPresent(false);
        overlayClass.getMethod("renderBossHealth").invoke(overlay);
        int afterAllowed = (Integer) overlayClass.getField("drawCount").get(null);
        check("boss health draws when no encounter is running", afterAllowed == 1,
                "got " + afterAllowed);

        CoreHooks.setEncounterPresent(true);
        overlayClass.getMethod("renderBossHealth").invoke(overlay);
        int afterSuppressed = (Integer) overlayClass.getField("drawCount").get(null);
        check("boss health is suppressed during an encounter", afterSuppressed == 1,
                "got " + afterSuppressed);

        CoreHooks.setEncounterPresent(false);
        overlayClass.getMethod("renderBossHealth").invoke(overlay);
        int afterResumed = (Integer) overlayClass.getField("drawCount").get(null);
        check("boss health resumes after the encounter", afterResumed == 2,
                "got " + afterResumed);

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CORE TRANSFORMER CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    private static void rewrite(TranscenTransformer transformer, File classesDir, File outDir,
                                String className) throws Exception {
        String path = className.replace('.', '/') + ".class";
        byte[] original = Files.readAllBytes(new File(classesDir, path).toPath());
        byte[] transformed = transformer.transform(className, className, original);

        // A byte-for-byte comparison, not an identity test: returning a modified copy is the expected path,
        // but returning the input untouched on a name mismatch is the failure this is checking for.
        boolean changed = transformed.length != original.length;
        if (!changed) {
            for (int i = 0; i < original.length; i++) {
                if (original[i] != transformed[i]) {
                    changed = true;
                    break;
                }
            }
        }
        check(className + " bytecode changed", changed, "identical output");
        System.out.println("   " + className + ": " + original.length + " -> " + transformed.length);

        File target = new File(outDir, path);
        target.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(target);
        try {
            out.write(transformed);
        } finally {
            out.close();
        }
    }

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            System.out.println("ok   " + label);
        } else {
            System.out.println("FAIL " + label + " (" + detail + ")");
            failures++;
        }
    }

    /**
     * Loads the rewritten classes in isolation, so the JVM verifier has to pass them.
     *
     * <p>{@code loadClass} is overridden rather than {@code findClass} on purpose. The stand-in copies the
     * transformer was run against are also on the application classpath, and the default parent-first
     * delegation would hand back those untransformed copies -- the test would then pass while proving
     * nothing. Checking the rewritten directory first is what actually exercises the rewritten bytecode.
     */
    private static final class ByteLoader extends ClassLoader {
        private final File root;

        ByteLoader(File root) {
            super(TransformerHarness.class.getClassLoader());
            this.root = root;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    File file = new File(root, name.replace('.', '/') + ".class");
                    if (file.isFile()) {
                        try {
                            byte[] bytes = Files.readAllBytes(file.toPath());
                            found = defineClass(name, bytes, 0, bytes.length);
                        } catch (Exception failure) {
                            throw new ClassNotFoundException(name, failure);
                        }
                    } else {
                        return super.loadClass(name, resolve);
                    }
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }
    }
}
