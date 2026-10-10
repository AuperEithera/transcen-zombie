import com.example.transcenmod.core.AllReturn;
import com.example.transcenmod.core.AllReturnAgent;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Standalone harness for the AllReturn rewrite and for the transformer that drives it.
 *
 * <p>What it has to prove, in order of how badly it fails in game:
 *
 * <ol>
 *   <li>a transformer may not cause a class load, because it runs inside the class loader's transformer
 *       loop. The first version of AllReturn$Transformer read AllReturn.allReturn and the game died with
 *       ClassCircularityError on the first class Forge loaded. Section "circularity" below reproduces that
 *       exact failure with a positive control, and the scan at the end asserts it statically;
 *   <li>the rewritten class still verifies - the injected branch needs a stack map frame at its join
 *       point, and a missing one is a VerifyError on whatever class happens to load next;
 *   <li>the guard is inert while AllReturn.allReturn is false, so an unused feature costs nothing and
 *       changes no behaviour;
 *   <li>once the flag is set, every return sort is answered with a correct default;
 *   <li>the classes the feature must not touch - minecraft, forge, this mod, java - are left byte for byte
 *       alone.
 * </ol>
 *
 * <p>Run with: gradlew verifyAllReturn
 */
public final class AllReturnHarness {

    private static final String SAMPLE = "foreign.Sample";
    private static final String FLAG_PROPERTY = "transcenmod.allReturn";
    private static final String FORGE_FIRST_CLASS = "net.minecraftforge.fml.common.Loader";

    private static int failures = 0;
    private static File modClassesDir;

    public static void main(String[] args) throws Exception {
        File classesDir = new File(args[0]);
        File outDir = new File(args[1]);
        modClassesDir = new File(args[2]);
        outDir.mkdirs();

        // 1 and 4: what is rewritten, and what is left alone
        byte[] sample = Files.readAllBytes(new File(classesDir, SAMPLE.replace('.', '/') + ".class").toPath());
        byte[] rewritten = AllReturnAgent.rewrite("foreign/Sample", sample);
        check("a class from a mod jar is rewritten", rewritten != null && rewritten.length != sample.length);

        leaveAlone("net/minecraft/world/World");
        leaveAlone("net/minecraftforge/fml/common/Loader");
        leaveAlone("com/example/transcenmod/core/CoreHooks");
        leaveAlone("com/example/transcenmod/entity/TranscenZombie");
        leaveAlone("java/util/ArrayList");
        leaveAlone("org/objectweb/asm/ClassReader");
        leaveAlone("com/google/common/collect/ImmutableList");
        leaveAlone("it/unimi/dsi/fastutil/ints/Int2ObjectMap");
        leaveAlone("org/apache/logging/log4j/LogManager");
        leaveAlone("io/netty/channel/Channel");

        File target = new File(outDir, SAMPLE.replace('.', '/') + ".class");
        target.getParentFile().mkdirs();
        OutputStream out = new FileOutputStream(target);
        try {
            out.write(rewritten);
        } finally {
            out.close();
        }

        // 1: this is the load that makes the verifier rule on the rewritten bytecode
        Class<?> type = new Loader(outDir).loadClass(SAMPLE);
        java.lang.reflect.Field callsField = type.getField("calls");
        Object instance = type.getDeclaredConstructor().newInstance();
        check("<init> still runs on the rewritten class", instance != null);

        // 2: inert while the flag is down
        AllReturn.allReturn = false;
        callsField.setInt(null, 0);
        check("voidMethod runs normally while AllReturn is off",
                invoke(type, instance, "voidMethod") == null && callsField.getInt(null) == 1);
        check("intMethod returns its own value while AllReturn is off",
                Integer.valueOf(7).equals(invoke(type, instance, "intMethod")));
        check("longMethod returns its own value while AllReturn is off",
                Long.valueOf(19L).equals(invoke(type, instance, "longMethod", long.class, 10L)));
        check("objectMethod returns its own value while AllReturn is off",
                "original".equals(invoke(type, instance, "objectMethod")));

        // 3: and answers with defaults once it is up
        AllReturn.allReturn = true;
        callsField.setInt(null, 0);
        invoke(type, instance, "voidMethod");
        check("voidMethod returns immediately while AllReturn is on", callsField.getInt(null) == 0);
        check("intMethod answers 0 while AllReturn is on",
                Integer.valueOf(0).equals(invoke(type, instance, "intMethod")));
        check("longMethod answers 0L while AllReturn is on",
                Long.valueOf(0L).equals(invoke(type, instance, "longMethod", long.class, 10L)));
        check("doubleMethod answers 0.0 while AllReturn is on",
                Double.valueOf(0.0D).equals(invoke(type, instance, "doubleMethod")));
        check("floatMethod answers 0.0F while AllReturn is on",
                Float.valueOf(0.0F).equals(invoke(type, instance, "floatMethod")));
        check("boolMethod answers false while AllReturn is on",
                Boolean.FALSE.equals(invoke(type, instance, "boolMethod")));
        check("objectMethod keeps its own value: a default object is null and no caller can use it",
                "original".equals(invoke(type, instance, "objectMethod")));
        check("staticMethod answers 0 while AllReturn is on",
                Integer.valueOf(0).equals(type.getMethod("staticMethod").invoke(null)));
        // objectMethod is deliberately not guarded any more: a null default is not a value a caller can use.
        check("only the object-returning method was allowed to run its body", callsField.getInt(null) == 1);

        // back down, and the same instances go back to normal
        AllReturn.allReturn = false;
        callsField.setInt(null, 0);
        invoke(type, instance, "voidMethod");
        check("turning AllReturn off restores normal behaviour without a reload", callsField.getInt(null) == 1);

        // 5: transformers, which is where the launch died
        verifyTransformerInjectsRegardlessOfFlag(classesDir, modClassesDir);
        // Naming the transformer's own class is fine: it can only appear in an instruction that
        // runs after that class is loaded. Any other class of this mod is a class load started from inside
        // the loader's transformer loop, which is the crash. TranscenTransformer is allowed its own nested
        // classes for the same reason - they are all preloaded during its own initialisation.
        verifyNoModClassInInstructions(modClassesDir, "com/example/transcenmod/core/AllReturn$Transformer",
                new String[] { "com/example/transcenmod/core/AllReturn$Transformer" });
        verifyNoModClassInInstructions(modClassesDir, "com/example/transcenmod/core/TranscenTransformer",
                new String[] { "com/example/transcenmod/core/TranscenTransformer" });
        verifyNoCircularity(classesDir);

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL ALLRETURN CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. the transformer. it runs inside the class loader's transformer loop, so it may not cause a class
    //    load, and it may not name a class of this mod on that path.
    // ---------------------------------------------------------------------------------------------

    /**
     * The coremod side transformer injects the guard unconditionally, from the first class of the session.
     *
     * It used to rewrite only when the flag was already set, and that is exactly what made the feature do
     * nothing: by the time the player can press the key, every foreign class is loaded already,
     * so there was nothing left to rewrite. The guard is inert until the flag flips, so injecting it early
     * costs nothing - and it is the only route that works on a JRE, where there is no agent to attach.
     */
    private static void verifyTransformerInjectsRegardlessOfFlag(File classesDir, File modClassesDir)
            throws Exception {
        AllReturn.Transformer transformer = new AllReturn.Transformer();
        byte[] original = Files.readAllBytes(new File(classesDir, SAMPLE.replace('.', '/') + ".class").toPath());

        // The transformer guards a class only when its bytes come out of a mod jar, and it asks
        // Launch.classLoader where they came from. The stand-ins live in directories, which that same
        // lookup reads as "not a mod", so the two jars below are the shape the game has: one under a mods
        // folder holding the stand-ins, one under libraries holding a class that has to be left alone.
        File mods = new File(classesDir, "mods");
        File libraries = new File(classesDir, "libraries");
        pack(classesDir, new File(mods, "harness-standins.jar"), null);
        pack(classesDir, new File(libraries, "harness-library.jar"), "foreign/Lib");
        net.minecraft.launchwrapper.Launch.classLoader = new net.minecraft.launchwrapper.LaunchClassLoader(
                new URL[] {new File(mods, "harness-standins.jar").toURI().toURL(),
                        new File(libraries, "harness-library.jar").toURI().toURL()});

        System.setProperty(FLAG_PROPERTY, "false");
        AllReturn.allReturn = false;
        byte[] off = transformer.transform(SAMPLE, SAMPLE, original);
        check("the coremod transformer injects the guard while the flag is off",
                !same(original, off) && off.length > original.length);

        System.setProperty(FLAG_PROPERTY, "true");
        AllReturn.allReturn = true;
        byte[] on = transformer.transform(SAMPLE, SAMPLE, original);
        check("a class loaded while the switch is on is left alone", same(original, on));
        check("the rewrite itself does not read the flag", same(off, AllReturnAgent.rewrite(SAMPLE, original)));

        byte[] own = transformer.transform("com.example.transcenmod.core.CoreHooks",
                "com.example.transcenmod.core.CoreHooks", original);
        check("the coremod transformer leaves this mod's own classes alone", same(original, own));

        // the two copies of the rewrite exist because neither transformer may name the other's class; this
        // is what keeps them from drifting apart
        byte[] agentCopy = AllReturnAgent.rewrite(SAMPLE, original);
        check("the coremod copy and the agent copy of the rewrite agree byte for byte",
                same(off, agentCopy));

        // and the source rule itself, which is what keeps minecraft, forge and the libraries out of it
        check("a class from a mod jar is guarded",
                AllReturn.Transformer.isForeignJarClass(SAMPLE.replace('.', '/')));
        check("a class from a library jar is left alone",
                !AllReturn.Transformer.isForeignJarClass("foreign/Lib"));
        check("a class the loader cannot name is left alone",
                !AllReturn.Transformer.isForeignJarClass("java/lang/String"));

        System.setProperty(FLAG_PROPERTY, "false");
        AllReturn.allReturn = false;
    }

    /** Packs the stand-ins into a jar, so the source lookup finds them where a mod's classes are. */
    private static void pack(File classesDir, File into, String onlyName) throws Exception {
        into.getParentFile().mkdirs();
        JarOutputStream out = new JarOutputStream(new FileOutputStream(into));
        if (onlyName == null) {
            add(out, classesDir, classesDir);
        } else {
            // one entry holding a stand-in's bytes: the lookup only has to find the class in this jar
            byte[] bytes = Files.readAllBytes(new File(classesDir, SAMPLE.replace('.', '/') + ".class").toPath());
            out.putNextEntry(new JarEntry(onlyName + ".class"));
            out.write(bytes);
            out.closeEntry();
        }
        out.close();
    }

    private static void add(JarOutputStream out, File root, File directory) throws Exception {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                // the two jars being built live under this root and must not be packed into themselves
                if (child.equals(new File(root, "mods")) || child.equals(new File(root, "libraries"))) {
                    continue;
                }
                add(out, root, child);
                continue;
            }
            String name = child.getPath().substring(root.getPath().length() + 1).replace('\\', '/');
            out.putNextEntry(new JarEntry(name));
            out.write(Files.readAllBytes(child.toPath()));
            out.closeEntry();
        }
    }

    /**
     * Every instruction that names a class of this mod. A class named on the transform path is resolved
     * when that instruction executes, and executing it inside the transformer loop is the crash.
     */
    private static void verifyNoModClassInInstructions(File root, String internalName, String[] allowedPrefixes)
            throws Exception {
        File file = new File(root, internalName + ".class");
        if (!file.isFile()) {
            check(internalName + " is present to scan", false);
            return;
        }
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(file.toPath()))
                .accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        Set<String> found = new TreeSet<String>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    collect(found, call.owner);
                    collectDescriptor(found, call.desc);
                } else if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    collect(found, field.owner);
                    collect(found, field.desc);
                } else if (instruction instanceof TypeInsnNode) {
                    collect(found, ((TypeInsnNode) instruction).desc);
                } else if (instruction instanceof LdcInsnNode) {
                    Object value = ((LdcInsnNode) instruction).cst;
                    // a String constant that happens to spell a class name does not name a class - that
                    // is how the injected hops are written - so only real Type constants count
                    if (value instanceof org.objectweb.asm.Type) {
                        collect(found, ((org.objectweb.asm.Type) value).getInternalName());
                    }
                }
            }
        }

        List<String> bad = new ArrayList<String>();
        for (String name : found) {
            if (!name.startsWith("com/example/transcenmod/")) {
                continue;
            }
            boolean allowed = false;
            for (String prefix : allowedPrefixes) {
                if (name.startsWith(prefix)) {
                    allowed = true;
                }
            }
            if (!allowed) {
                bad.add(name);
            }
        }
        check(internalName + " names no class of this mod on the transform path", bad.isEmpty(), bad.toString());
    }

    private static void collect(Set<String> found, String type) {
        if (type == null) {
            return;
        }
        String name = type;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.startsWith("com/example/transcenmod/")) {
            found.add(name);
        }
    }

    private static void collectDescriptor(Set<String> found, String descriptor) {
        for (org.objectweb.asm.Type argument : org.objectweb.asm.Type.getArgumentTypes(descriptor)) {
            collect(found, argument.getDescriptor());
        }
        collect(found, org.objectweb.asm.Type.getReturnType(descriptor).getDescriptor());
    }

    /**
     * The real failure, reproduced. A mini launch loader runs the transformer chain itself while defining a
     * class - the same shape as LaunchClassLoader#runTransformers - and child-first for anything it can see
     * locally, so a class of this mod is only reachable through it.
     *
     * <p>CircularityProbe reads AllReturn.allReturn from transform(), which is the mistake the first
     * version made; it must throw. Then the real transformer must not.
     */
    private static void verifyNoCircularity(File classesDir) throws Exception {
        System.setProperty(FLAG_PROPERTY, "true");

        boolean probeThrew = false;
        try {
            MiniLaunchLoader probeLoader = new MiniLaunchLoader(classesDir, modClassesDir);
            probeLoader.register(
                    (IClassTransformer) probeLoader.loadClass("CircularityProbe").newInstance());
            probeLoader.loadClass(FORGE_FIRST_CLASS, false);
        } catch (Throwable expected) {
            probeThrew = expected instanceof ClassCircularityError || expected instanceof LinkageError;
            if (!probeThrew) {
                System.out.println("      probe threw " + expected);
            }
        }
        check("the circularity test reproduces the crash (positive control)", probeThrew);

        boolean realThrew = false;
        String detail = "";
        try {
            MiniLaunchLoader loader = new MiniLaunchLoader(classesDir, modClassesDir);
            // both real transformers, loaded the way the wrapper loads them, then registered
            loader.register((IClassTransformer) loader
                    .loadClass("com.example.transcenmod.core.AllReturn$Transformer").newInstance());
            loader.register((IClassTransformer) loader
                    .loadClass("com.example.transcenmod.core.TranscenTransformer").newInstance());
            Class<?> first = loader.loadClass(FORGE_FIRST_CLASS, false);
            detail = String.valueOf(first);
        } catch (Throwable failure) {
            realThrew = true;
            detail = String.valueOf(failure);
        }
        check("the real transformer chain defines a class without a circularity error", !realThrew, detail);

        System.setProperty(FLAG_PROPERTY, "false");
    }

    private static boolean same(byte[] before, byte[] after) {
        if (before == null || after == null || before.length != after.length) {
            return false;
        }
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * A minimal LaunchClassLoader: it runs the transformer chain while defining a class, and it takes any
     * class it can find in its own roots before asking the parent, so a class of this mod is reachable only
     * through it. Everything else - ASM, launchwrapper, java - comes from the parent exactly as it does in
     * the game, because the real loader is parent first for those too.
     */
    private static final class MiniLaunchLoader extends ClassLoader {

        private final File[] roots;
        private final List<IClassTransformer> transformers = new ArrayList<IClassTransformer>();

        MiniLaunchLoader(File... roots) {
            super(AllReturnHarness.class.getClassLoader());
            this.roots = roots;
        }

        void register(IClassTransformer transformer) {
            transformers.add(transformer);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    byte[] bytes = bytesOf(name);
                    if (bytes == null) {
                        return super.loadClass(name, resolve);
                    }
                    found = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }

        private byte[] bytesOf(String name) {
            for (File root : roots) {
                if (root == null) {
                    continue;
                }
                File file = new File(root, name.replace('.', '/') + ".class");
                if (!file.isFile()) {
                    continue;
                }
                try {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    // the loop the real loader runs, in the same place: while the class is being defined
                    for (IClassTransformer transformer : transformers) {
                        bytes = transformer.transform(name, name, bytes);
                    }
                    return bytes;
                } catch (java.io.IOException failure) {
                    throw new RuntimeException(failure);
                }
            }
            return null;
        }
    }

    private static void leaveAlone(String internalName) throws Exception {
        // the bytes do not matter - the rewrite has to bail out on the name before it looks at them
        byte[] any = new byte[] {1, 2, 3};
        check(internalName + " is left alone", AllReturnAgent.rewrite(internalName, any) == null
                && AllReturnAgent.isExcluded(internalName));
    }

    private static Object invoke(Class<?> type, Object instance, String name) throws Exception {
        return type.getMethod(name).invoke(instance);
    }

    private static Object invoke(Class<?> type, Object instance, String name, Class<?> paramType, Object value)
            throws Exception {
        return type.getMethod(name, paramType).invoke(instance, value);
    }

    private static void check(String label, boolean ok) {
        check(label, ok, "");
    }

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            System.out.println("ok   " + label);
        } else {
            System.out.println("FAIL " + label + (detail.isEmpty() ? "" : " (" + detail + ")"));
            failures++;
        }
    }

    private static final class Loader extends ClassLoader {
        private final File root;

        Loader(File root) {
            super(Loader.class.getClassLoader());
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


