package com.example.transcenmod.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AnnihilationClientState
{

    private static final Set<UUID> DOOMED = ConcurrentHashMap.newKeySet();

    private static volatile boolean protectionOff;

    public static void setProtectionOff(boolean off)
    {
        protectionOff = off;
    }

    public static boolean isProtectionOff()
    {
        return protectionOff;
    }

    public static void clearAllDoomed()
    {
        DOOMED.clear();
    }
    private static volatile boolean hudActive;
    private static volatile float hudProgress = 1.0F;

    // the bar frames the whole encounter,tying it to one zombie made it strobe as repairs put it back
    private static volatile boolean hudLatched;

    private AnnihilationClientState() {}

    public static void markDoomed(UUID id)
    {
        if (DOOMED.add(id))
        {
            }
    }

    public static void clearDoomed(UUID id)
    {
        DOOMED.remove(id);
    }

    public static boolean isDoomed(UUID id)
    {
        return DOOMED.contains(id);
    }
    public static void noteTick()
    {
        if (!DOOMED.isEmpty())
        {
        }
    }


    public static void setHud(boolean active, float progress)
    {
        if (active)
        {
            hudLatched = true;
        }
        hudActive = hudLatched || active;
        hudProgress = progress;
    }

    public static void latchHud()
    {
        hudLatched = true;
        hudActive = true;
    }

    public static boolean isHudLatched()
    {
        return hudLatched;
    }

    public static void unlatchHud()
    {
        hudLatched = false;
        hudActive = false;
    }

    public static boolean isHudActive()
    {
        return hudActive;
    }

    public static float hudProgress()
    {
        return hudProgress;
    }

    public static void reset()
    {
        DOOMED.clear();
        hudActive = false;
        hudLatched = false;
        hudProgress = 1.0F;
    }
}
