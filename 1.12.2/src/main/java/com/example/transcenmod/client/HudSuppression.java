package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;

import net.minecraft.client.Minecraft;

/**
 * The single decision "is the encounter running for this client?".
 *
 * <p>Everything that hides the vanilla HUD asks this, so the rule lives in one place instead of being
 * re-derived (differently) in each handler. Four independent signals are ORed, and each covers a case the
 * others miss:
 *
 * <ul>
 *   <li>{@code TranscenMod.HUD_ACTIVE} -- the mod-wide switch, also set from the common side, so it works
 *       even before the first packet arrives.</li>
 *   <li>{@link AnnihilationClientState#isHudActive()} -- the server's last word on the bar.</li>
 *   <li>{@link AnnihilationClientState#isHudLatched()} -- this client has seen the encounter and should
 *       not fall back to the vanilla HUD during a gap between syncs.</li>
 *   <li>{@link ClientEvents#zombiePresent} -- the ground truth that cannot be desynced: a
 *       {@code TranscenZombie} is loaded right now. This is what covers a client that joined mid-encounter
 *       and never saw an activate packet.</li>
 * </ul>
 *
 * <p>The world null check is first and absolute. Every one of the signals above can be stale during the
 * gap between "disconnected" and {@code ClientDisconnectionFromServerEvent} being handled, and
 * {@code zombiePresent} would throw on a null world. Returning false there means the HUD comes back on the
 * main menu, which is correct.
 */
public final class HudSuppression {

    private HudSuppression() {}

    public static boolean isEncounterPresent() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            return false;
        }
        return TranscenMod.HUD_ACTIVE
                || AnnihilationClientState.isHudActive()
                || AnnihilationClientState.isHudLatched()
                || ClientEvents.zombiePresent(mc.world);
    }
}
