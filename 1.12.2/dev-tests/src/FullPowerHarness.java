import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodNode;

import com.example.transcenmod.core.TranscenCorePlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Verification for TranscendenceZombieFullPower.
 *
 * The thing under test cannot be checked by compiling it. Its failure mode is that a class comes back
 * unchanged and the game starts fine, so a build that does nothing at all looks exactly like a build that
 * works. Every assertion here therefore has to be able to fail, and the run carries a positive control: the
 * same class is measured before the filter, where the injected body must still be present. Without that
 * control a filter that returned its input would pass every check below.
 *
 * The shape being modelled is a real coremod's, and it is modelled rather than described: one transform()
 * that widens a field for its own renderer and replaces a method body to change behaviour. The filter has
 * to keep the widening and drop the body.
 */
public class FullPowerHarness {

    private static final String TARGET = "net.minecraft.entity.Payload";

    private static int failures = 0;
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        File classesDir = new File(args[0]);
        System.setProperty("transcenmod.fullpower", "true");

        byte[] raw = raw(TARGET);
        check("the stand-in target is readable as an untouched class file", raw != null);

        // The real transformer list, in the order a launch builds it: nothing wrapped, then a coremod. The
        // stand-ins are instantiated directly rather than through this loader: LaunchClassLoader would try
        // to resolve their ASM imports from its own URLs, and only their class name matters to the filter.
        LaunchClassLoader loader = new LaunchClassLoader(
                new java.net.URL[]{classesDir.toURI().toURL()});
        Launch.classLoader = loader;
        List<Object> transformers = transformersOf(loader);

        IClassTransformer good = (IClassTransformer) Class.forName("othermod.Good").newInstance();
        IClassTransformer bad = (IClassTransformer) Class.forName("$wrapper.othermod.Bad").newInstance();
        IClassTransformer filter = new TranscenCorePlugin.FullPowerFilter();
        transformers.add(good);
        transformers.add(bad);
        transformers.add(filter);

        check("the coremod transformer is attributed to its owner and not replayed",
                !replayedByFilter(filter, bad));
        check("the plain transformer is replayed", replayedByFilter(filter, good));

        byte[] incoming = good.transform(TARGET, TARGET, bad.transform(TARGET, TARGET, raw));

        // positive control: the injection is really there before the filter runs
        check("CONTROL the injected body is present before filtering", returnsConstant(incoming, 999));
        check("CONTROL the injected widening is present before filtering", isPublic(incoming, "hidden"));

        byte[] filtered = filter.transform(TARGET, TARGET, incoming);

        check("the injected body is gone", !returnsConstant(filtered, 999));
        check("the injected widening is kept", isPublic(filtered, "hidden"));
        check("the replayed transformer's widening is kept", isPublic(filtered, "alsoHidden"));
        check("the class still has its methods", hasMethod(filtered, "value"));

        // A class the coremod never touched must come back as the same bytes, not as a rebuilt copy.
        byte[] untouched = filter.transform("net.minecraft.entity.Payload", "com.example.other.Thing",
                new byte[]{1, 2, 3});
        check("a class outside the target is returned untouched",
                untouched != null && untouched.length == 3 && untouched[0] == 1);

        // With the feature off the filter must be inert even for the target.
        System.setProperty("transcenmod.fullpower", "false");
        byte[] off = filter.transform(TARGET, TARGET, incoming);
        check("CONTROL with the feature off the injection survives", returnsConstant(off, 999));
        System.setProperty("transcenmod.fullpower", "true");

        // The transformer may not name a class of this mod on the class load path. It is a nested class, so
        // the outer class is in its InnerClasses attribute - that is metadata and starts no load - but a
        // call to one of the outer class's private statics makes javac emit a synthetic access$NNN method on
        // the outer class and call it from here, and that is a real instruction naming a real class. The
        // scan looks at instructions only, which is exactly the line between the harmless case and the one
        // that killed the launch with ClassCircularityError.
        File modClasses = new File(args[1]);
        check("the filter names no class of this mod on the transform path",
                namesNoModClass(modClasses, "com/example/transcenmod/core/TranscenCorePlugin$FullPowerFilter"));

        System.out.println(failures == 0
                ? "ALL FULLPOWER CHECKS PASSED (" + checks + ")"
                : "FULLPOWER CHECKS FAILED: " + failures + " of " + checks);
        if (failures != 0) {
            System.exit(1);
        }
    }

    // Every owner, descriptor and Type constant on every instruction, checked for this mod's package. The
    // class's own name is allowed: its own name on an instruction cannot start a load that is not already
    // happening.
    private static boolean namesNoModClass(File modClasses, String internalName) {
        File file = new File(modClasses, internalName + ".class");
        if (!file.isFile()) {
            System.out.println("      (no class file at " + file + ")");
            return false;
        }
        try {
            ClassNode node = new ClassNode();
            new ClassReader(java.nio.file.Files.readAllBytes(file.toPath()))
                    .accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            List<String> found = new ArrayList<String>();
            for (int i = 0; i < node.methods.size(); i++) {
                MethodNode method = node.methods.get(i);
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode) {
                        org.objectweb.asm.tree.MethodInsnNode call =
                                (org.objectweb.asm.tree.MethodInsnNode) instruction;
                        collect(found, call.owner);
                        collect(found, call.desc);
                    } else if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode) {
                        org.objectweb.asm.tree.FieldInsnNode field =
                                (org.objectweb.asm.tree.FieldInsnNode) instruction;
                        collect(found, field.owner);
                        collect(found, field.desc);
                    } else if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode) {
                        collect(found, ((org.objectweb.asm.tree.TypeInsnNode) instruction).desc);
                    } else if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode) {
                        Object value = ((org.objectweb.asm.tree.LdcInsnNode) instruction).cst;
                        if (value instanceof org.objectweb.asm.Type) {
                            collect(found, ((org.objectweb.asm.Type) value).getInternalName());
                        }
                    }
                }
            }
            for (int i = 0; i < found.size(); i++) {
                System.out.println("      names " + found.get(i));
            }
            return found.isEmpty();
        } catch (Throwable failure) {
            System.out.println("      scan failed: " + failure);
            return false;
        }
    }

    private static void collect(List<String> found, String descriptorOrOwner) {
        if (descriptorOrOwner == null) {
            return;
        }
        if (!descriptorOrOwner.startsWith("com/example/transcenmod/")) {
            return;
        }
        if (descriptorOrOwner.equals("com/example/transcenmod/core/TranscenCorePlugin$FullPowerFilter")) {
            return;
        }
        if (!found.contains(descriptorOrOwner)) {
            found.add(descriptorOrOwner);
        }
    }

    // Mirrors FullPowerFilter.trustworthy: a "$wrapper." entry only gets replayed when its parent is Forge
    // or this mod, and the harness asks the filter itself rather than re-deriving the rule.
    private static boolean replayedByFilter(IClassTransformer filter, IClassTransformer candidate) {
        try {
            java.lang.reflect.Method method = filter.getClass().getDeclaredMethod("trustworthy", Object.class);
            method.setAccessible(true);
            return Boolean.TRUE.equals(method.invoke(null, candidate));
        } catch (Throwable failure) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Object> transformersOf(LaunchClassLoader loader) throws Exception {
        Field field = LaunchClassLoader.class.getDeclaredField("transformers");
        field.setAccessible(true);
        return (List<Object>) field.get(loader);
    }

    private static byte[] raw(String className) throws Exception {
        InputStream stream = FullPowerHarness.class.getClassLoader()
                .getResourceAsStream(className.replace('.', '/') + ".class");
        if (stream == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } finally {
            stream.close();
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean isPublic(byte[] bytes, String fieldName) {
        for (int i = 0; i < node(bytes).fields.size(); i++) {
            FieldNode field = node(bytes).fields.get(i);
            if (fieldName.equals(field.name)) {
                return (field.access & Opcodes.ACC_PUBLIC) != 0;
            }
        }
        return false;
    }

    private static boolean hasMethod(byte[] bytes, String methodName) {
        for (int i = 0; i < node(bytes).methods.size(); i++) {
            MethodNode method = node(bytes).methods.get(i);
            if (methodName.equals(method.name)) {
                return true;
            }
        }
        return false;
    }

    // True when the method body is exactly "push <constant>; ireturn", which is what the stand-in coremod
    // writes and what the untouched class never is.
    private static boolean returnsConstant(byte[] bytes, int constant) {
        for (int i = 0; i < node(bytes).methods.size(); i++) {
            MethodNode method = node(bytes).methods.get(i);
            if (!"value".equals(method.name) || method.instructions == null) {
                continue;
            }
            List<AbstractInsnNode> live = new ArrayList<AbstractInsnNode>();
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction.getOpcode() >= 0) {
                    live.add(instruction);
                }
            }
            if (live.size() != 2) {
                return false;
            }
            if (!(live.get(0) instanceof IntInsnNode)) {
                return false;
            }
            return ((IntInsnNode) live.get(0)).operand == constant
                    && live.get(1).getOpcode() == Opcodes.IRETURN;
        }
        return false;
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "ok   " : "FAIL ") + label);
    }
}
