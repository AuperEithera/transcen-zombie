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

// the version trap: a field is named one way in mojang mappings, another way in SRG
// getField is the original, getFieldRelaxed adds the alias table srgCandidates
// aliases only ever rescue the SRG fallback,the mapped name is always tried first
public final class ReflectionAccess {

    private ReflectionAccess() {}

    // first id of every row was verified against the 1.20.1 SRG artifacts
    // later ids keep the lookup alive across mapping refreshes
    // a missing row is silent here - the caller's catch-all swallows it
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
            // knownUuids decides whether a uuid may enter the world at all
            // it was missing here and the teardown's catch-all hid the lookup failure
            // so a removed entity kept its id claimed and every revival was refused,repair loop never converged
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

    // parameter order is srg first,then mapped - both are kept for the existing callers
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

    // mapped name first then every alias,then the superclass chain
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
