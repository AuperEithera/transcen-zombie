package $wrapper.foreign;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A stand-in for a coremod transformer, in one pass over one class it widens a private field so its own
 * code can read it, and it replaces a method body to change behaviour.
 *
 * The package is named {@code $wrapper.} because that is how Forge's ASMTransformerWrapper presents a
 * coremod's transformer to LaunchClassLoader, and it is the only thing FullPowerFilter can use to attribute
 * a transformer to its owner.
 */
public class Bad implements IClassTransformer {

    protected String getParentClass() {
        return "foreign.core.BadCore";
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !"net.minecraft.entity.Payload".equals(transformedName)) {
            return basicClass;
        }
        ClassNode node = new ClassNode();
        new ClassReader(basicClass).accept(node, 0);

        for (int i = 0; i < node.fields.size(); i++) {
            FieldNode field = node.fields.get(i);
            if ("hidden".equals(field.name)) {
                field.access = Opcodes.ACC_PUBLIC;
            }
        }
        for (int i = 0; i < node.methods.size(); i++) {
            MethodNode method = node.methods.get(i);
            if ("value".equals(method.name)) {
                method.instructions.clear();
                method.instructions.add(new IntInsnNode(Opcodes.SIPUSH, 999));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
                method.maxStack = 1;
                method.maxLocals = 1;
            }
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
