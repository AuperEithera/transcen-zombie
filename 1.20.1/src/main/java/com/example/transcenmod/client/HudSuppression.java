package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.Minecraft;

// whether the encounter runs on this client
// kept out of the mixin package - anything in there gets loaded as a mixin and is banned from ordinary code
// used to filter overlays entry by entry,that died with whole-HUD-pass cancellation so the enum went too
public final class HudSuppression {

    private HudSuppression() {}

    // same three sources the bar uses,entity alone misses the 2s window after a removal when the server copy is gone
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
