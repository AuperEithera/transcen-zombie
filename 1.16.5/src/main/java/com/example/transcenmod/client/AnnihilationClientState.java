package com.example.transcenmod.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side view of what the server is in the middle of annihilating.
 *
 * <p>An entity that the server has already pulled out of its storages still exists on the client
 * until the removal packet arrives two seconds later. During that window it is rendered forced red,
 * which is the "the thing is doomed" feedback. Membership is keyed by UUID and shared between the
 * packet handlers and the render hooks.
 */
public final class AnnihilationClientState {

    private static final Set<UUID> DOOMED = ConcurrentHashMap.newKeySet();

    /**
     * True while the protection is switched off.
     *
     * <p>Forces the bar and the music off regardless of what else is true. Bar visibility is an OR of
     * several sources, one of which is "a zombie is visible", and a zombie can remain visible for a moment
     * after the switch is thrown -- which was enough to start the music straight back up after turning it
     * off. This is the term that overrides the others rather than adding to them.
     */
    private static volatile boolean protectionOff;

    public static void setProtectionOff(boolean off) {
        protectionOff = off;
    }

    public static boolean isProtectionOff() {
        return protectionOff;
    }

    /**
     * Drops every remembered shell.
     *
     * <p>This is what actually makes a wiped encounter disappear, and it was the piece missing from
     * every earlier attempt. The server can remove a zombie and tell the client nothing -- the red shell
     * exists precisely so the removal is not reflected immediately -- so the client goes on drawing a
     * zombie that no longer exists. From the player's side that is indistinguishable from "it will not
     * delete", and it is also the flicker: the shell is re-latched every tick for an entity that the
     * server has already discarded.
     */
    public static void clearAllDoomed() {
        DOOMED.clear();
    }
    /** Server-driven HUD state; see {@code HudSyncPacket}. */
    private static volatile boolean hudActive;
    private static volatile float hudProgress = 1.0F;

    /**
     * Latches once the HUD has been switched on, and is never cleared by a server update.
     *
     * <p>The encounter's bar and overlay are a frame around the encounter, not a readout of one
     * entity. Tying their visibility to "a zombie exists right now" makes them strobe the instant
     * anything removes that zombie -- the state goes off and back on as the repair puts it back, and
     * that reads as flickering. Once the encounter has started, the only way it should end is the
     * player leaving the world, which is exactly what {@link #reset()} is for.
     */
    private static volatile boolean hudLatched;

    private AnnihilationClientState() {}

    /** Marks an entity as doomed, so the renderer forces it red. */
    public static void markDoomed(UUID id) {
        if (DOOMED.add(id)) {
            }
    }

    public static void clearDoomed(UUID id) {
        DOOMED.remove(id);
    }

    public static boolean isDoomed(UUID id) {
        return DOOMED.contains(id);
    }
    /** Called once per client tick, before anything else looks at the set. */
    public static void noteTick() {
        if (!DOOMED.isEmpty()) {
        }
    }

    /** Called by the red application path so the probe can prove it ran. */

    /**
     * Applies a server HUD update.
     *
     * <p>Turning on latches; turning off is ignored once latched. The server is not the authority on
     * whether the encounter is running -- it cannot see the client's own "the egg was used" state --
     * so its "off" is treated as "no zombie visible right now", which is not the same thing and must
     * not blank the screen.
     */
    public static void setHud(boolean active, float progress) {
        if (active) {
            hudLatched = true;
        }
        hudActive = hudLatched || active;
        hudProgress = progress;
    }

    /** Arms the HUD from the client side, e.g. when the spawn egg is used. */
    public static void latchHud() {
        hudLatched = true;
        hudActive = true;
    }

    public static boolean isHudLatched() {
        return hudLatched;
    }

    /**
     * Releases the latch, so the encounter can be switched off.
     *
     * <p>The latch exists so an ordinary removal cannot make the bar flicker, and it is deliberately
     * immune to the server's "off" updates. A deliberate wipe is the one case that has to get past it,
     * and this is the only way through -- without it the bar would outlive the zombies it belongs to.
     */
    public static void unlatchHud() {
        hudLatched = false;
        hudActive = false;
    }

    public static boolean isHudActive() {
        return hudActive;
    }

    public static float hudProgress() {
        return hudProgress;
    }

    /** Called when the connection goes away, so nothing leaks between worlds. */
    public static void reset() {
        DOOMED.clear();
        hudActive = false;
        hudLatched = false;
        hudProgress = 1.0F;
    }
}
