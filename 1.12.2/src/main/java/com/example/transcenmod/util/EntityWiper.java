package com.example.transcenmod.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// setDead only flips a flag. The entity stays in World.loadedEntityList and in the chunk's per-section set,
// so the tick loop keeps running it and the renderer keeps drawing it. One entry in the forge list, a screen
// full of copies. This class takes it out for real.
//
// The removal walks all the way down rather than calling ClassInheritanceMultiMap#remove:
//
//     ClassInheritanceMultiMap            mc wrapper, holds knownKeys and map
//       -> Map<Class<?>, List<T>> map     one bucket per class the entity is assignable to
//            -> ArrayList elementData     java, and the array is the bottom of it
//
// The mc fields go through ObfuscationReflectionHelper, not raw reflection: after reobfuscation they are
// field_180216_b and field_180218_a, so a literal "knownKeys" lookup would work in the dev workspace and
// fail in the shipped jar. The helper remaps the name per environment. The ArrayList fields are java.util
// and are never renamed, so plain reflection is correct for those.
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

        world.loadedEntityList.remove(entity);

        try {
            Chunk chunk = world.getChunk(entity.getPosition());
            if (chunk == null) {
                return;
            }
            ClassInheritanceMultiMap<Entity>[] sections = chunk.getEntityLists();
            if (sections == null) {
                return;
            }
            for (ClassInheritanceMultiMap<Entity> section : sections) {
                if (section != null) {
                    removeFromSection(section, entity);
                }
            }
            // vanilla marks the chunk dirty after every entity removal. Without it the removal stays in
            // memory only and the entity is back on the next load.
            chunk.markDirty();
        } catch (Throwable failure) {
            System.err.println("[transcenmod] entity removal failed for " + entity + ": " + failure);
        }
    }

    /**
     * The mc level. knownKeys is every class this section has been asked about, and an entity sits in one
     * bucket per class it is assignable to, so every matching bucket has to lose it. Missing one leaves the
     * entity reachable through getByClass, which is what the chunk walk and the collision queries read.
     */
    @SuppressWarnings("unchecked")
    private static void removeFromSection(ClassInheritanceMultiMap<Entity> section, Entity entity) {
        Set<Class<?>> knownKeys = (Set<Class<?>>) mcpField(ClassInheritanceMultiMap.class, section, "knownKeys");
        Map<Class<?>, List<Entity>> buckets =
                (Map<Class<?>, List<Entity>>) mcpField(ClassInheritanceMultiMap.class, section, "map");
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

    /**
     * The java level, down to the array. The bucket is an ArrayList, so the entity is in its elementData and
     * nowhere else. ArrayList#remove would find it; this does the arraycopy by hand - shift the tail down,
     * null the unused last slot, drop size, and bump modCount the way ArrayList does.
     */
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
            // by reference or a same-looking one elsewhere in the list would be taken out instead
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object mcpField(Class owner, Object instance, String name) {
        return ObfuscationReflectionHelper.getPrivateValue(owner, instance, name);
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

    // Field#get and #set declare a checked IllegalAccessException. Wrapping it here keeps the callers free of
    // try blocks without ever hiding the failure: an unreachable field is still an error, not a fallback.
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
