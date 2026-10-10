import java.util.HashSet;
import java.util.Set;

/**
 * Stand-in for "qx", the obfuscated net.minecraft.util.ClassInheritanceMultiMap.
 *
 *   MD: qx/add (Ljava/lang/Object;)Z net/minecraft/util/ClassInheritanceMultiMap/add (Ljava/lang/Object;)Z
 *   MD: qx/remove (Ljava/lang/Object;)Z net/minecraft/util/ClassInheritanceMultiMap/remove (Ljava/lang/Object;)Z
 *
 * add and remove are the only two members of this class in the deobfuscation data at all, which is why the
 * table names them without a func_ alternative.
 */
public class qx {

    private final Set entries = new HashSet();

    public boolean add(Object value) {
        return this.entries.add(value);
    }

    public boolean remove(Object value) {
        return this.entries.remove(value);
    }

    public boolean contains(Object value) {
        return this.entries.contains(value);
    }

    public int size() {
        return this.entries.size();
    }
}
