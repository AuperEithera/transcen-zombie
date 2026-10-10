package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.Minecraft;

// not in the mixin package, Mixin refuses ordinary code referencing a class under a defined mixin package
// the failure is runtime "is in a defined mixin package ... and cannot be referenced directly"
// suppression cancels the whole hud pass,there is no per entry overlay list left to filter
public final class HudSuppression {

    private HudSuppression() {}

    // same three sources as the bar's own visibility,not the entity alone
    // right after a removal the server copy is gone for the 2 second window but the encounter is still running
    public static boolean isEncounterPresent() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        return TranscenMod.HUD_ACTIVE
                || AnnihilationClientState.isHudActive()
                || AnnihilationClientState.isHudLatched()
                || ClientEvents.zombiePresent(mc.level);
    }
}
