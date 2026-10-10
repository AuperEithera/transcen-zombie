package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.Minecraft;

/**
 * Whether the encounter is running on this client.
 *
 * <p>Lives here rather than beside the mixin that uses it. Everything under the mixin package belongs to
 * the mixin configuration, and Mixin refuses to let such a class be referenced from ordinary code --
 * loading it raises "is in a defined mixin package ... and cannot be referenced directly" at runtime.
 *
 * <p>This class used to do two jobs. The other one was deciding which registered overlays belonged to
 * somebody else, so the overlay pass could be filtered entry by entry. That was abandoned when suppression
 * moved to cancelling the whole HUD pass, which makes the question unnecessary -- there is no list to
 * filter if the pass is not run -- and the overlay enumeration, its cache and its diagnostics were left
 * behind unused.
 */
public final class HudSuppression {

    private HudSuppression() {}

    /**
     * True while the encounter is running on this client.
     *
     * <p>Uses the same three sources as the bar's own visibility: the local "egg was used" flag, the
     * server's HUD sync, and whether a zombie is currently visible. Checking only the entity would turn
     * the suppression off during the two-second window after a removal, when the server copy is gone but
     * the encounter is plainly still happening.
     */
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
