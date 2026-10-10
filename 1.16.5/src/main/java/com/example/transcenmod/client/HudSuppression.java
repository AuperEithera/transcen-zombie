package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.Minecraft;

// 1.16.5 has no forge overlay registry and no ForgeGui, IngameGui.renderIngameGui draws the whole HUD
// so suppression is an intercept on that one method, all that is left here is "is the encounter running"
public final class HudSuppression {

    private HudSuppression() {}

    public static boolean isEncounterPresent() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.world == null) {
            return false;
        }
        return TranscenMod.HUD_ACTIVE
                || AnnihilationClientState.isHudActive()
                || AnnihilationClientState.isHudLatched()
                || ClientEvents.zombiePresent(mc.world);
    }
}
