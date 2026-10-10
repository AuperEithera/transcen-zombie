package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.Minecraft;

public final class HudSuppression
{

    private HudSuppression() {}

    public static boolean isEncounterPresent()
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
        {
            return false;
        }
        return TranscenMod.HUD_ACTIVE
                || AnnihilationClientState.isHudActive()
                || AnnihilationClientState.isHudLatched()
                || ClientEvents.zombiePresent(mc.level);
    }
}
