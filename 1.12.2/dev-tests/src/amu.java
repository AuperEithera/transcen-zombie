import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Stand-in for "amu", the obfuscated net.minecraft.world.World, with the obfuscated member names and the
 * obfuscated descriptors. Every name here is a line of Forge's deobfuscation_data-1.12.2.lzma:
 *
 *   MD: amu/a (Lvg;)Z net/minecraft/world/World/func_72838_d (Lnet/minecraft/entity/Entity;)Z
 *   MD: amu/b (Lvg;)V net/minecraft/world/World/func_72923_a (Lnet/minecraft/entity/Entity;)V
 *   MD: amu/c (Lvg;)V net/minecraft/world/World/func_72847_b (Lnet/minecraft/entity/Entity;)V
 *   MD: amu/e (Lvg;)V net/minecraft/world/World/func_72900_e (Lnet/minecraft/entity/Entity;)V
 *   MD: amu/f (Lvg;)V net/minecraft/world/World/func_72973_f (Lnet/minecraft/entity/Entity;)V
 *   MD: amu/i (Lvg;)V net/minecraft/world/World/func_72897_h (Lnet/minecraft/entity/Entity;)V
 *   MD: amu/d (Lvg;)Z net/minecraft/world/World/func_72942_c (Lnet/minecraft/entity/Entity;)Z
 *   MD: amu/a (Ljava/util/Collection;)V net/minecraft/world/World/func_175650_b (Ljava/util/Collection;)V
 *   MD: amu/c (Ljava/util/Collection;)V net/minecraft/world/World/func_175681_c (Ljava/util/Collection;)V
 *   MD: amu/k ()V net/minecraft/world/World/func_72939_s ()V
 */
public class amu {

    public final List loadedEntityList = new ArrayList();
    public final List unloadedEntityList = new ArrayList();
    public final axw chunk = new axw();

    public boolean a(vg entity) {
        this.chunk.a(entity);
        this.loadedEntityList.add(entity);
        this.b(entity);
        return true;
    }

    public void b(vg entity) {
    }

    public void c(vg entity) {
    }

    public void e(vg entity) {
        entity.X();
        this.loadedEntityList.remove(entity);
    }

    public void f(vg entity) {
        entity.X();
        this.chunk.b(entity);
        this.loadedEntityList.remove(entity);
        this.c(entity);
    }

    public boolean d(vg entity) {
        return this.loadedEntityList.add(entity);
    }

    public void i(vg entity) {
        if (!this.loadedEntityList.contains(entity)) {
            this.loadedEntityList.add(entity);
        }
    }

    public void a(Collection entities) {
        this.loadedEntityList.addAll(entities);
    }

    public void c(Collection entities) {
        this.unloadedEntityList.addAll(entities);
    }

    public void k() {
        this.loadedEntityList.removeAll(this.unloadedEntityList);
        this.unloadedEntityList.clear();

        for (int i = 0; i < this.loadedEntityList.size(); ++i) {
            vg entity = (vg) this.loadedEntityList.get(i);
            if (entity.isDead) {
                this.chunk.b(entity);
                this.loadedEntityList.remove(i--);
                this.c(entity);
            }
        }
    }
}
