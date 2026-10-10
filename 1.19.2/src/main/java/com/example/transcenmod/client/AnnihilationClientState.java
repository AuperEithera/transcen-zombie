package com.example.transcenmod.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// client-side mirror of what the server is already annihilating
// the server removes the entity first, the removal packet reaches the client ~2s later -that gap is the forced red shell
public final class AnnihilationClientState {

    private static final Set<UUID> DOOMED = ConcurrentHashMap.newKeySet();

    // hard override not one more or term in the bar visibility chain
    // a zombie can stay visible for a tick after the switch flips, which was enough to restart the music
    private static volatile boolean protectionOff;

    public static void setProtectionOff(boolean off) {
        protectionOff = off;
    }

    public static boolean isProtectionOff() {
        return protectionOff;
    }

    // server can discard a zombie and tell the client nothing, so the client goes on drawing the shell
    // the shell is re-latched every tick for an entity that no longer exists -clearing here is what ends a wiped encounter
    public static void clearAllDoomed() {
        DOOMED.clear();
    }
    private static volatile boolean hudActive;
    private static volatile float hudProgress = 1.0F;

    // latched on by any hud start, server or client, and never cleared by a server "off"
    // bar visibility tied to "a zombie exists right now" strobed as removals and repairs alternated
    // only reset() clears it
    private static volatile boolean hudLatched;

    private AnnihilationClientState() {}

    // renderer forces the red tint off this set
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
    // runs first each client tick, before anything else reads the set
    public static void noteTick() {
        if (!DOOMED.isEmpty()) {
        }
    }

    // red application path calls this so the probe can prove it ran

    // server "off" only means no zombie is visible at that instant,it cannot see the client's own egg state,so it must not blank the bar
    // once latched the bar stays up
    public static void setHud(boolean active, float progress) {
        if (active) {
            hudLatched = true;
        }
        hudActive = hudLatched || active;
        hudProgress = progress;
    }

    // arms the hud from the client side, the spawn egg is the usual caller
    public static void latchHud() {
        hudLatched = true;
        hudActive = true;
    }

    public static boolean isHudLatched() {
        return hudLatched;
    }

    // the one way past the latch,server "off" updates are ignored by design
    // without it the bar outlives the zombies it belongs to
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

    // connection teardown, keeps state from leaking between worlds
    public static void reset() {
        DOOMED.clear();
        hudActive = false;
        hudLatched = false;
        hudProgress = 1.0F;
    }
}
