package net.minecraft.world.chunk;

import net.minecraft.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal stand-in for the real 1.12.2 Chunk, used only by {@code verifyEntityGuard}.
 *
 * It reproduces the three methods the guards are placed on, with the same descriptors as the real class:
 * addEntity(Entity)V, removeEntity(Entity)V and removeEntityAtIndex(Entity,int)V.
 */
public class Chunk {

    private final List<Entity> sections = new ArrayList<Entity>();

    public void addEntity(Entity entity) {
        this.sections.add(entity);
    }

    public void removeEntity(Entity entity) {
        this.sections.remove(entity);
    }

    public void removeEntityAtIndex(Entity entity, int index) {
        this.sections.remove(entity);
    }

    public boolean holds(Entity entity) {
        return this.sections.contains(entity);
    }

    public int size() {
        return this.sections.size();
    }
}
