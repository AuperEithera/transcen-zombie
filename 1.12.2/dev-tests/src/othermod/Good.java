package othermod;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * A stand-in for a transformer FullPowerFilter must keep: a plain class, registered directly rather than
 * wrapped, doing access widening on its own field. It widens {@code alsoHidden} and nothing else, so a
 * filtered class that still has that widening proves the baseline replay really ran.
 */
public class Good implements IClassTransformer {

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !"net.minecraft.entity.Payload".equals(transformedName)) {
            return basicClass;
        }
        ClassNode node = new ClassNode();
        new ClassReader(basicClass).accept(node, 0);
        for (int i = 0; i < node.fields.size(); i++) {
            FieldNode field = node.fields.get(i);
            if ("alsoHidden".equals(field.name)) {
                field.access = Opcodes.ACC_PUBLIC;
            }
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
