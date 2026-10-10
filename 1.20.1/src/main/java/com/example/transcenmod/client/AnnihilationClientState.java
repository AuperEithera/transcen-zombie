package com.example.transcenmod.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// client-side view of what the server is annihilating right now
// the server removes the entity at once,but the removal packet lands about 2s later
// inside that window it draws forced red,keyed by UUID and shared by packet handlers and render hooks
public final class AnnihilationClientState {

    private static final Set<UUID> DOOMED = ConcurrentHashMap.newKeySet();

    // force-off term,not another input to the OR
    // bar visibility ORs several sources one of which is "a zombie is visible" and that stays true a moment past the switch
    // that alone restarted the music right after it was turned off
    private static volatile boolean protectionOff;

    public static void setProtectionOff(boolean off) {
        protectionOff = off;
    }

    public static boolean isProtectionOff() {
        return protectionOff;
    }

    // no red remnant over an entity the client already discarded
    public static void clearAllDoomed() {
        DOOMED.clear();
    }


    // server-driven HUD state,see HudSyncPacket
    private static volatile boolean hudActive;
    private static volatile float hudProgress = 1.0F;

    // latches on the first active update and no server off clears it
    // bar and overlay frame the encounter,not one entity
    // "a zombie exists right now" made them strobe when repair removed and re-added it,the only exit is #reset()
    private static volatile boolean hudLatched;

    private AnnihilationClientState() {}

    // renderer forces red while this is set
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
    // once per client tick,before anything reads the set
    public static void noteTick() {
        if (!DOOMED.isEmpty()) {
            }
    }

    // called from the red application path so a probe can prove it ran

    // active latches,hudActive stays latched OR active so a later off cannot blank the screen
    // the server cannot see the client's own "egg used" state,its off only means no zombie visible
    public static void setHud(boolean active, float progress) {
        if (active) {
            hudLatched = true;
        }
        hudActive = hudLatched || active;
        hudProgress = progress;
    }

    // client-side arming,spawn egg use today
    public static void latchHud() {
        hudLatched = true;
        hudActive = true;
    }

    public static boolean isHudLatched() {
        return hudLatched;
    }

    // the one path past the latch,a deliberate wipe
    // otherwise the latch is immune to the server's off and the bar outlives its zombies
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

    // connection teardown so nothing leaks between worlds
    public static void reset() {
        DOOMED.clear();
        hudActive = false;
        hudLatched = false;
        hudProgress = 1.0F;
    }
}
