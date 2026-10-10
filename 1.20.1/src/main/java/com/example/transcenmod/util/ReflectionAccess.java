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

public final class ReflectionAccess
{

    private ReflectionAccess() {}

    private static String[] srgCandidates(String mappedName)
    {
        switch (mappedName)
        {
            case "position":            return new String[]{"f_19825_", "f_19846_", "f_19847_"};
            case "levelCallback":       return new String[]{"f_146801_"};
            case "valid":               return new String[]{"f_19861_"};
            case "isAddedToWorld":      return new String[]{"f_19862_"};
            case "capabilities":        return new String[]{"f_19830_"};
            case "entityStorage":       return new String[]{"f_171631_", "f_104586_", "f_157587_"};
            case "tickingEntities":     return new String[]{"f_171630_", "f_104590_"};
            case "entityManager":       return new String[]{"f_143244_", "f_143243_"};
            case "entityTickList":      return new String[]{"f_143243_"};
            case "players":             return new String[]{"f_8546_", "f_104566_", "f_104584_", "f_85038_"};
            case "sectionStorage":      return new String[]{"f_157495_", "f_157588_"};
            case "visibleEntityStorage":return new String[]{"f_157494_", "f_157591_"};
            case "storage":             return new String[]{"f_156827_", "f_156828_", "f_156829_"};
            case "byClass":             return new String[]{"f_13527_", "f_156788_"};
            case "byUuid":              return new String[]{"f_156808_", "f_156809_", "f_156819_", "f_156820_"};
            case "byId":                return new String[]{"f_156807_", "f_156818_"};
            case "active":              return new String[]{"f_156903_", "f_156885_"};
            case "passive":             return new String[]{"f_156904_"};
            default:                    return new String[0];
        }
    }

    public static Object getField(Object obj, String srgName, String mappedName) throws Exception
    {
        Class<?> clazz = obj.getClass();
        while (clazz != null)
        {
            try
            {
                Field f = clazz.getDeclaredField(mappedName);
                f.setAccessible(true);
                return f.get(obj);
            }
            catch (NoSuchFieldException e)
            {
                try
                {
                    Field f = clazz.getDeclaredField(srgName);
                    f.setAccessible(true);
                    return f.get(obj);
                }
                catch (NoSuchFieldException ex)
                {
                    clazz = clazz.getSuperclass();
                }
            }
        }
        throw new NoSuchFieldException(mappedName);
    }

    public static Object getFieldRelaxed(Object obj, String mappedName) throws Exception
    {
        String aliases[] = srgCandidates(mappedName);
        Class<?> clazz = obj.getClass();
        while (clazz != null)
        {
            Field f = findLocal(clazz, mappedName, aliases);
            if (f != null)
            {
                f.setAccessible(true);
                return f.get(obj);
            }
            clazz = clazz.getSuperclass();
        }
        throw new NoSuchFieldException(mappedName);
    }

    private static Field findLocal(Class<?> clazz, String mappedName, String[] aliases)
    {
        try
        {
            return clazz.getDeclaredField(mappedName);
        }
        catch (NoSuchFieldException ignored)
        {
        }
        for (String alias : aliases)
        {
            try
            {
                return clazz.getDeclaredField(alias);
            }
            catch (NoSuchFieldException ignored)
            {
            }
        }
        return null;
    }

    public static Object call(Object target, String methodName, Class<?>[] paramTypes, Object... args) throws Exception
    {
        Method m = target.getClass().getMethod(methodName, paramTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }



    private static boolean hasField(Class<?> owner, String name)
    {
        Class<?> clazz = owner;
        while (clazz != null)
        {
            try
            {
                clazz.getDeclaredField(name);
                return true;
            }
            catch (NoSuchFieldException e)
            {
                clazz = clazz.getSuperclass();
            }
        }
        return false;
    }
}
