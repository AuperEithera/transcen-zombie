package com.example.transcenmod.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The state bridge between the coremod's transformers and the mod's own code.
 *
 * This class is deliberately free of any minecraft import. The transformer injects calls to it into
 * net.minecraft classes, so it is initialised while minecraft is still half loaded; a single mention
 * to a class that pulls in net.minecraft would turn that into a class-loading cycle. Everything that
 * crosses this boundary is therefore typed as Object/Class/Collection and resolved by name.
 */
public final class CoreHooks {

    private CoreHooks() {}

    private static final Set<UUID> PROTECTED = ConcurrentHashMap.newKeySet();

    private static final Set<UUID> RETALIATING = ConcurrentHashMap.newKeySet();

    private static volatile boolean encounterPresent;

    // ---------------------------------------------------------------------------------------------
    // Health override
    // ---------------------------------------------------------------------------------------------

    public static void setProtected(UUID player, boolean protectedNow) {
        if (protectedNow) {
            PROTECTED.add(player);
        } else {
            PROTECTED.remove(player);
        }
    }

    public static void setRetaliating(UUID player, boolean retaliating) {
        if (retaliating) {
            RETALIATING.add(player);
        } else {
            RETALIATING.remove(player);
        }
    }

    public static Float getHealthOverride(UUID id) {
        if (id == null) {
            return null;
        }
        if (RETALIATING.contains(id)) {
            return Float.valueOf(0.0F);
        }
        if (PROTECTED.contains(id)) {
            return Float.valueOf(20.0F);
        }
        return null;
    }

    public static void clear(UUID player) {
        PROTECTED.remove(player);
        RETALIATING.remove(player);
    }

    // ---------------------------------------------------------------------------------------------
    // Boss bar suppression
    // ---------------------------------------------------------------------------------------------

    public static void setEncounterPresent(boolean present) {
        encounterPresent = present;
    }

    public static boolean shouldSuppressBossHealth() {
        return encounterPresent;
    }

    // ---------------------------------------------------------------------------------------------
    // Entity guard -- the state the injected guards read
    // ---------------------------------------------------------------------------------------------

    /** Every class this mod ships lives under this prefix. */
    public static final String OWN_PREFIX = "com.example.transcenmod.";

    // Explicit rather than a prefix test: the protection is about one entity, and a future second
    // modded entity should have to be named here on purpose instead of inheriting the guard by accident.
    private static final String[] PROTECTED_TYPE_NAMES = {
            "com.example.transcenmod.entity.TranscenZombie"
    };

    private static volatile boolean protectionEnabled = true;

    // The ban list proper lives in the mod (TranscenMod.BANNED_CLASSES); this is the live view of it.
    // A predicate rather than a copied set, so the two sides cannot drift apart.
    private static volatile Predicate<Class<?>> banPredicate;

    public static void setProtectionEnabled(boolean enabled) {
        protectionEnabled = enabled;
    }

    public static boolean isProtectionEnabled() {
        return protectionEnabled;
    }

    public static void setBanPredicate(Predicate<Class<?>> predicate) {
        banPredicate = predicate;
    }

    /** True for a class this mod ships. String test, so it needs no class loading on either side. */
    public static boolean isOwnType(Class<?> type) {
        return type != null && type.getName().startsWith(OWN_PREFIX);
    }

    public static boolean isOwnType(Object instance) {
        return instance != null && isOwnType(instance.getClass());
    }

    /**
     * True when an attempt to take this entity out of the world has to be refused.
     *
     * The superclass chain is walked with Class#getSuperclass, which never loads a class - so a subclass
     * of the protected entity is covered too, and nothing is resolved while minecraft is mid-load.
     *
     * Refusing also clears the entity's isDead flag. That is not cosmetic: World.updateEntities removes a
     * dead entity with loadedEntityList.remove(i--), so a refusal that left the flag set would re-examine
     * the same index for ever - a hang, not a save. The flag is what the next pass reads, so it has to go.
     */
    public static boolean refuseRemoval(Object entity) {
        if (entity == null || !protectionEnabled) {
            return false;
        }
        if (!isProtectedType(entity.getClass())) {
            return false;
        }
        defuse(entity);
        return true;
    }

    private static boolean isProtectedType(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            String name = current.getName();
            for (String protectedName : PROTECTED_TYPE_NAMES) {
                if (protectedName.equals(name)) {
                    return true;
                }
            }
            if (name.startsWith("net.minecraft.")) {
                return false;
            }
        }
        return false;
    }

    // Entity.isDead after reobfuscation is field_70128_L, before it is isDead. Resolved once, by walking up
    // from the entity's own class, because CoreHooks cannot name net.minecraft.entity.Entity.
    private static volatile java.lang.reflect.Field isDeadField;
    private static volatile boolean isDeadResolved;

    private static void defuse(Object entity) {
        try {
            java.lang.reflect.Field field = isDeadField;
            if (!isDeadResolved) {
                isDeadResolved = true;
                for (Class<?> current = entity.getClass(); current != null && current != Object.class;
                        current = current.getSuperclass()) {
                    field = declaredField(current, "isDead");
                    if (field == null) {
                        field = declaredField(current, "field_70128_L");
                    }
                    if (field != null) {
                        break;
                    }
                }
                if (field != null) {
                    field.setAccessible(true);
                }
                isDeadField = field;
            }
            if (field != null && field.getBoolean(entity)) {
                field.setBoolean(entity, false);
            }
        } catch (Throwable failure) {
            // being unable to clear the flag must not turn a refusal into a crash
        }
    }

    private static java.lang.reflect.Field declaredField(Class<?> owner, String name) {
        try {
            return owner.getDeclaredField(name);
        } catch (Throwable missing) {
            return null;
        }
    }

    /**
     * True when an attempt to put this entity into the world has to be refused.
     *
     * This mod's own classes are exempt unconditionally: the ban list is keyed by Class and lives for the
     * whole session, so a Transcendence Zombie that killed another Transcendence Zombie would otherwise
     * ban its own class and the mod could never spawn one again - not even with /summon.
     */
    public static boolean refuseAdd(Object entity) {
        if (entity == null) {
            return false;
        }
        Class<?> type = entity.getClass();
        if (isOwnType(type)) {
            return false;
        }
        Predicate<Class<?>> predicate = banPredicate;
        if (predicate == null) {
            return false;
        }
        try {
            // exact class match, the same question TranscenEvents asks at EntityJoinWorldEvent
            return predicate.test(type);
        } catch (Throwable failure) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Entity guard -- the collection call sites the transformer re-points here
    //
    // net.minecraft's own lists are java.util.ArrayList/HashSet, and rewriting those would change them
    // for the whole process. Only the calls minecraft itself makes are re-pointed, which keeps
    // the change inside the entity bookkeeping.
    // ---------------------------------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean removeFromCollection(Collection collection, Object element) {
        if (collection == null) {
            return false;
        }
        if (refuseRemoval(element)) {
            return false;
        }
        // deliberately not wrapped: the original exception, if any, is part of the original behaviour
        return collection.remove(element);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object removeFromMap(Map map, Object key) {
        if (map == null) {
            return null;
        }
        if (refuseRemoval(key)) {
            return null;
        }
        return map.remove(key);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object removeFromListAt(List list, int index) {
        if (list == null) {
            return null;
        }
        if (index < 0 || index >= list.size()) {
            // out of range: hand it to the list so the caller sees the original exception
            return list.remove(index);
        }
        Object element = list.get(index);
        if (refuseRemoval(element)) {
            return element;
        }
        return list.remove(index);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean removeAllFromCollection(Collection collection, Collection elements) {
        if (collection == null || elements == null) {
            return false;
        }
        // the untouched path is the original call, so nothing but a protected element changes behaviour
        boolean anyProtected = false;
        for (Object element : elements) {
            if (refuseRemoval(element)) {
                anyProtected = true;
                break;
            }
        }
        if (!anyProtected) {
            return collection.removeAll(elements);
        }
        List<Object> allowed = new java.util.ArrayList<Object>(elements.size());
        for (Object element : elements) {
            if (!refuseRemoval(element)) {
                allowed.add(element);
            }
        }
        return collection.removeAll(allowed);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean addToCollection(Collection collection, Object element) {
        if (collection == null) {
            return false;
        }
        if (refuseAdd(element)) {
            return false;
        }
        return collection.add(element);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean addAllToCollection(Collection collection, Collection elements) {
        if (collection == null || elements == null) {
            return false;
        }
        boolean anyBanned = false;
        for (Object element : elements) {
            if (refuseAdd(element)) {
                anyBanned = true;
                break;
            }
        }
        if (!anyBanned) {
            return collection.addAll(elements);
        }
        List<Object> allowed = new java.util.ArrayList<Object>(elements.size());
        for (Object element : elements) {
            if (!refuseAdd(element)) {
                allowed.add(element);
            }
        }
        return collection.addAll(allowed);
    }
}
