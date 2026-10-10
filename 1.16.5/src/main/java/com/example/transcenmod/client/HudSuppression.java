package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.Minecraft;

/**
 * Whether the encounter is running on this client.
 *
 * <p>On 1.16.5 there is no Forge overlay registry and no {@code ForgeGui}; the whole HUD is drawn by
 * {@code IngameGui.renderIngameGui}. Suppression therefore happens by intercepting that one method, and
 * the only question left here is whether the encounter is running.
 */
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
