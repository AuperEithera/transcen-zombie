package com.example.transcenmod.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

public final class EntityWiper {

    private EntityWiper() {}

    public static void wipe(Entity entity) {
        if (entity == null) {
            return;
        }
        World world = entity.getEntityWorld();
        if (world == null) {
            return;
        }

        // removeEntityDangerously is the removal. it runs vanilla's own order - chunk entry, world list,
        // then onEntityRemoved - and everything below the isDead flag belongs to it, so nothing here sets or
        // works around that flag.
        try {
            world.removeEntityDangerously(entity);
        } catch (Throwable failure) {
            System.err.println("[transcenmod] removeEntityDangerously failed for " + entity + ": " + failure);
        }

        // the three things onEntityRemoved does, by hand and not through it. done as well as the call above
        // rather than instead of it, because removeEntityDangerously throws before reaching onEntityRemoved
        // if its chunk step fails, and then all three would be skipped.

        // Entity:2974. isAddedToWorld is private. It is a Forge addition, so it is absent from both mapping
        // tables and keeps its literal name after reobfuscation - no remapping wanted or needed here.
        try {
            setMcField(entity, Boolean.FALSE, "isAddedToWorld");
        } catch (Throwable failure) {
            System.err.println("[transcenmod] isAddedToWorld not cleared for " + entity + ": " + failure);
        }

        // World:99 is a protected List<IWorldEventListener>. RenderGlobal's implementation is an empty body
        // (RenderGlobal:2218) so it costs nothing, and it cannot be skipped without asking each listener
        // what type it is - which is exactly the dispatch this is here to avoid. a second pass is harmless:
        // WorldClient keeps both of its sets in hash sets, so a repeat add is a no-op.
        try {
            Object listeners = mcField(world, "eventListeners", "field_73021_x");
            if (listeners instanceof List) {
                for (Object listener : new ArrayList<Object>((List<?>) listeners)) {
                    if (listener instanceof IWorldEventListener) {
                        ((IWorldEventListener) listener).onEntityRemoved(entity);
                    }
                }
            }
        } catch (Throwable failure) {
            System.err.println("[transcenmod] listener notify failed for " + entity + ": " + failure);
        }

        // WorldServer:1129-1130. a second call is harmless: entitiesById is a fastutil Int2ObjectMap whose
        // remove returns null for a missing key, entitiesByUuid is a HashMap that does the same.
        if (world instanceof WorldServer) {
            try {
                Object byId = mcField(world, "entitiesById", "field_175729_l");
                if (byId instanceof Int2ObjectMap) {
                    ((Int2ObjectMap<Entity>) byId).remove(entity.getEntityId());
                }
            } catch (Throwable failure) {
                System.err.println("[transcenmod] entitiesById not cleared for " + entity + ": " + failure);
            }
            try {
                Object byUuid = mcField(world, "entitiesByUuid", "field_175741_N");
                if (byUuid instanceof Map) {
                    ((Map<UUID, Entity>) byUuid).remove(entity.getUniqueID());
                }
            } catch (Throwable failure) {
                System.err.println("[transcenmod] entitiesByUuid not cleared for " + entity + ": " + failure);
            }
        }

        // the containers the call above does not reach. weatherEffects is a separate list it never touches,
        // and it clears one chunk section - by the entity's own chunkCoordY, so a stale value lands on the
        // wrong section while the render loop, which reads the sections directly, keeps drawing the entity.
        world.weatherEffects.remove(entity);
        world.loadedEntityList.remove(entity);

        try {
            Chunk chunk = world.getChunk(entity.chunkCoordX, entity.chunkCoordZ);
            ClassInheritanceMultiMap<Entity>[] sections = chunk == null ? null : chunk.getEntityLists();
            if (sections != null) {
                for (ClassInheritanceMultiMap<Entity> section : sections) {
                    if (section != null) {
                        removeFromSection(section, entity);
                    }
                }
                // vanilla marks the chunk dirty after every entity removal. without it the removal stays in
                // memory only and the entity is back on the next load.
                chunk.markDirty();
            }
        } catch (Throwable failure) {
            System.err.println("[transcenmod] chunk removal failed for " + entity + ": " + failure);
        }
    }

    public static void wipeAll(World world) {
        if (world == null) {
            return;
        }

        // copies: wipe takes entities out of the very lists this walks, and the listeners it fires are
        // synchronous, one of which touches these lists too.
        for (Entity entity : new ArrayList<Entity>(world.weatherEffects)) {
            if (!(entity instanceof EntityPlayer)) {
                wipe(entity);
            }
        }
        for (Entity entity : new ArrayList<Entity>(world.loadedEntityList)) {
            if (!(entity instanceof EntityPlayer)) {
                wipe(entity);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeFromSection(ClassInheritanceMultiMap<Entity> section, Entity entity) {
        Set<Class<?>> knownKeys = (Set<Class<?>>) mcField(section, "knownKeys", "field_180216_b");
        Map<Class<?>, List<Entity>> buckets =
                (Map<Class<?>, List<Entity>>) mcField(section, "map", "field_180218_a");
        if (knownKeys == null || buckets == null) {
            throw new IllegalStateException("ClassInheritanceMultiMap internals are null");
        }

        Class<?> actual = entity.getClass();
        for (Class<?> key : knownKeys) {
            if (!key.isAssignableFrom(actual)) {
                continue;
            }
            List<Entity> bucket = buckets.get(key);
            if (bucket != null) {
                removeFromList(bucket, entity);
            }
        }
    }

    private static void removeFromList(List<Entity> bucket, Entity entity) {
        if (!(bucket instanceof ArrayList)) {
            throw new IllegalStateException("expected an ArrayList bucket, got " + bucket.getClass().getName());
        }
        Object[] data = (Object[]) read(ELEMENT_DATA, bucket);
        Object rawSize = read(SIZE, bucket);
        if (data == null || !(rawSize instanceof Integer)) {
            throw new IllegalStateException("ArrayList internals are null");
        }

        int size = ((Integer) rawSize).intValue();
        for (int i = 0; i < size; i++) {
            // identity, not equals: Entity does not override equals, and a revived zombie has to be matched
            // as the same object or a same-looking one elsewhere in the list would be taken out instead
            if (data[i] == entity) {
                int moved = size - i - 1;
                if (moved > 0) {
                    System.arraycopy(data, i + 1, data, i, moved);
                }
                data[size - 1] = null;
                write(SIZE, bucket, Integer.valueOf(size - 1));
                Object rawMod = read(MOD_COUNT, bucket);
                if (rawMod instanceof Integer) {
                    write(MOD_COUNT, bucket, Integer.valueOf(((Integer) rawMod).intValue() + 1));
                }
                return;
            }
        }
    }

    /**
     * Reads a minecraft field by any of its names, walking up from the instance's own class.
     *
     * Two things ruled out the obvious helpers. ObfuscationReflectionHelper remaps the name through the
     * class it is handed, so a field declared on a superclass comes back unmapped - entitiesById lives on
     * World, and asking WorldServer for it produced the literal name and a NoSuchFieldException, which is
     * what silently disabled every removal in this class. And getDeclaredField does not walk up at all.
     *
     * Both names are passed because the runtime mapping and the development mapping do not agree
     * everywhere; whichever one is real here is found, and neither costs anything when it is absent.
     */
    private static Object mcField(Object instance, String... names) {
        if (instance == null) {
            return null;
        }
        for (Class<?> current = instance.getClass(); current != null; current = current.getSuperclass()) {
            for (String name : names) {
                try {
                    Field found = current.getDeclaredField(name);
                    found.setAccessible(true);
                    return found.get(instance);
                } catch (Throwable ignored) {
                    // try the next candidate
                }
            }
        }
        return null;
    }

    private static void setMcField(Object instance, Object value, String... names) {
        if (instance == null) {
            return;
        }
        for (Class<?> current = instance.getClass(); current != null; current = current.getSuperclass()) {
            for (String name : names) {
                try {
                    Field found = current.getDeclaredField(name);
                    found.setAccessible(true);
                    found.set(instance, value);
                    return;
                } catch (Throwable ignored) {
                    // try the next candidate
                }
            }
        }
    }

    private static final Field ELEMENT_DATA = field(ArrayList.class, "elementData");
    private static final Field SIZE = field(ArrayList.class, "size");
    // modCount is declared on AbstractList, not on ArrayList. getDeclaredField does not walk up, so asking
    // ArrayList for it is a NoSuchFieldException that takes the whole class down at load.
    private static final Field MOD_COUNT = field(AbstractList.class, "modCount");

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable failure) {
            throw new IllegalStateException(
                    "cannot reach " + owner.getName() + "." + name + " - reflection is unavailable", failure);
        }
    }

    // Field#get and #set declare a checked IllegalAccessException. wrapping it here keeps the callers free
    // of try blocks without ever hiding the failure: an unreachable field is still an error.
    private static Object read(Field f, Object target) {
        try {
            return f.get(target);
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot read " + f.getName(), failure);
        }
    }

    private static void write(Field f, Object target, Object value) {
        try {
            f.set(target, value);
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot write " + f.getName(), failure);
        }
    }
}
