import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for "axw", the obfuscated net.minecraft.world.chunk.Chunk.
 *
 *   MD: axw/a (Lvg;)V net/minecraft/world/chunk/Chunk/func_76612_a (Lnet/minecraft/entity/Entity;)V
 *   MD: axw/b (Lvg;)V net/minecraft/world/chunk/Chunk/func_76622_b (Lnet/minecraft/entity/Entity;)V
 *   MD: axw/a (Lvg;I)V net/minecraft/world/chunk/Chunk/func_76608_a (Lnet/minecraft/entity/Entity;I)V
 *
 * Note a and a(Lvg;I) differ only by descriptor, which is the case that makes matching on the descriptor
 * as well as the name load bearing.
 */
public class axw {

    private final List sections = new ArrayList();

    public void a(vg entity) {
        this.sections.add(entity);
    }

    public void b(vg entity) {
        this.sections.remove(entity);
    }

    public void a(vg entity, int index) {
        this.sections.remove(entity);
    }

    public boolean holds(vg entity) {
        return this.sections.contains(entity);
    }

    public int size() {
        return this.sections.size();
    }
}
