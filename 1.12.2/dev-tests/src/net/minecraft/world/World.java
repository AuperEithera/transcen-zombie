package net.minecraft.world;

import net.minecraft.entity.Entity;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Minimal stand-in for the real 1.12.2 World, used only by {@code verifyEntityGuard}.
 *
 * The bodies are shaped after the real ones (World:1182 spawnEntity, World:1218 onEntityAdded,
 * World:1227 onEntityRemoved, World:1236 removeEntity, World:1258 removeEntityDangerously,
 * World:1672 updateEntities, World:3154 loadEntities, World:3166 unloadEntities,
 * World:3540 joinEntityInSurroundings) because the point of the transformation is what those bodies
 * call - a stand-in that did something else would be testing the wrong thing.
 *
 * The exact descriptors matter as much as the names: the transformer matches them through the
 * deobfuscation remapper, and a wrong one is a silent no-op.
 */
public class World {

    public final List<Entity> loadedEntityList = new ArrayList<Entity>();
    public final List<Entity> unloadedEntityList = new ArrayList<Entity>();
    public final Chunk chunk = new Chunk();

    public boolean spawnEntity(Entity entity) {
        this.chunk.addEntity(entity);
        this.loadedEntityList.add(entity);
        this.onEntityAdded(entity);
        return true;
    }

    public void onEntityAdded(Entity entity) {
    }

    public void onEntityRemoved(Entity entity) {
    }

    public void removeEntity(Entity entity) {
        entity.setDead();
        this.loadedEntityList.remove(entity);
    }

    public void removeEntityDangerously(Entity entity) {
        entity.setDead();
        this.chunk.removeEntity(entity);
        this.loadedEntityList.remove(entity);
        this.onEntityRemoved(entity);
    }

    public boolean addWeatherEffect(Entity entity) {
        return this.loadedEntityList.add(entity);
    }

    public void joinEntityInSurroundings(Entity entity) {
        if (!this.loadedEntityList.contains(entity)) {
            this.loadedEntityList.add(entity);
        }
    }

    public void loadEntities(Collection<Entity> entities) {
        this.loadedEntityList.addAll(entities);
    }

    public void unloadEntities(Collection<Entity> entities) {
        this.unloadedEntityList.addAll(entities);
    }

    /** World:1672. The dead branch is the one that takes a dead entity out of the world list. */
    public void updateEntities() {
        this.loadedEntityList.removeAll(this.unloadedEntityList);
        this.unloadedEntityList.clear();

        for (int i = 0; i < this.loadedEntityList.size(); ++i) {
            Entity entity = this.loadedEntityList.get(i);
            if (entity.isDead) {
                this.chunk.removeEntity(entity);
                this.loadedEntityList.remove(i--);
                this.onEntityRemoved(entity);
            }
        }
    }
}
