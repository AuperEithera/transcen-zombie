package com.example.transcenmod.util;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraftforge.fml.loading.FMLLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reflective plumbing for the entity-storage surgery.
 *
 * <p><b>Nothing in here was rewritten.</b> {@link #getField(Object, String, String)} keeps its
 * original contract: look the field up by its Mojang-mapped name first, then by the SRG name, then
 * walk up the superclass chain, and hand back the value.
 *
 * <p>Two additions exist solely because of AccessTransformer:
 * <ul>
 *   <li>{@link #srgCandidates(String)} -- a small alias table, because the SRG id of a field is not
 *       always obvious and the original single hard-coded id was wrong for
 *       {@code ServerLevel.players}. The mapped name always wins, so behaviour is unchanged when
 *       the mapped name resolves.</li>
 *       self-check that reports whether the AT actually took effect for every member this mod
 *       touches. Useful for diagnosing a stale workspace.</li>
 * </ul>
 */
public final class ReflectionAccess {

    private ReflectionAccess() {}

    /**
     * SRG aliases for members whose exact id depends on the mapping generation.
     *
     * <p>The first entry of every row is the id verified against the 1.20.1 SRG artifacts; the
     * following entries keep the lookup alive across mapping refreshes. Because the Mojang-mapped
     * name is always tried first, these aliases can never change behaviour in a workspace where the
     * mapped name resolves -- they only rescue the SRG fallback.
     */
    private static String[] srgCandidates(String mappedName) {
        switch (mappedName) {
            // Entity
            case "position":            return new String[]{"f_19825_", "f_19846_", "f_19847_"};
            case "levelCallback":       return new String[]{"f_146801_"};
            case "valid":               return new String[]{"f_19861_"};
            case "isAddedToWorld":      return new String[]{"f_19862_"};
            case "capabilities":        return new String[]{"f_19830_"};
            // Level / ClientLevel / ServerLevel
            case "entityStorage":       return new String[]{"f_171631_", "f_104586_", "f_157587_"};
            case "tickingEntities":     return new String[]{"f_171630_", "f_104590_"};
            case "entityManager":       return new String[]{"f_143244_", "f_143243_"};
            case "entityTickList":      return new String[]{"f_143243_"};
            case "players":             return new String[]{"f_8546_", "f_104566_", "f_104584_", "f_85038_"};
            // PersistentEntitySectionManager
            case "sectionStorage":      return new String[]{"f_157495_", "f_157588_"};
            case "visibleEntityStorage":return new String[]{"f_157494_", "f_157591_"};
            // knownUuids decides whether a UUID may enter the world at all. It was missing from this
            // table, and because the lookup failure is swallowed by the teardown's catch-all, removing
            // an entity left its id claimed forever with no visible error: the zombie was then refused
            // re-entry on every revival, so the repair loop never converged. A hole in this table is not
            // a cosmetic problem -- the AT can have hit the field perfectly and the code still never
            // reach it.
            case "knownUuids":          return new String[]{"f_157491_"};
            // EntitySection / ClassInstanceMultiMap
            case "storage":             return new String[]{"f_156827_", "f_156828_", "f_156829_"};
            case "byClass":             return new String[]{"f_13527_", "f_156788_"};
            // EntityLookup
            case "byUuid":              return new String[]{"f_156808_", "f_156809_", "f_156819_", "f_156820_"};
            case "byId":                return new String[]{"f_156807_", "f_156818_"};
            // EntityTickList
            case "active":              return new String[]{"f_156903_", "f_156885_"};
            case "passive":             return new String[]{"f_156904_"};
            default:                    return new String[0];
        }
    }

    /**
     * ORIGINAL implementation -- untouched.
     *
     * <p>Finds {@code obj}'s field by mapped name, falling back to the SRG name and then to the
     * superclass chain, and returns its value.
     */
    public static Object getField(Object obj, String srgName, String mappedName) throws Exception {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(mappedName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                try {
                    Field f = clazz.getDeclaredField(srgName);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException ex) {
                    clazz = clazz.getSuperclass();
                }
            }
        }
        throw new NoSuchFieldException(mappedName);
    }

    /**
     * Mapping-generation tolerant variant of {@link #getField}. Mapped name first (identical to the
     * original behaviour), then every SRG alias, then the superclass chain.
     */
    public static Object getFieldRelaxed(Object obj, String mappedName) throws Exception {
        String[] aliases = srgCandidates(mappedName);
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            Field f = findLocal(clazz, mappedName, aliases);
            if (f != null) {
                f.setAccessible(true);
                return f.get(obj);
            }
            clazz = clazz.getSuperclass();
        }
        throw new NoSuchFieldException(mappedName);
    }

    private static Field findLocal(Class<?> clazz, String mappedName, String[] aliases) {
        try {
            return clazz.getDeclaredField(mappedName);
        } catch (NoSuchFieldException ignored) {
            // fall through to aliases
        }
        for (String alias : aliases) {
            try {
                return clazz.getDeclaredField(alias);
            } catch (NoSuchFieldException ignored) {
                // try next
            }
        }
        return null;
    }

    /** Reflectively invokes a no-argument method by name. */
    public static Object call(Object target, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = target.getClass().getMethod(methodName, paramTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }



    private static boolean hasField(Class<?> owner, String name) {
        Class<?> clazz = owner;
        while (clazz != null) {
            try {
                clazz.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return false;
    }
}
