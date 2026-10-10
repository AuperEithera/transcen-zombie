package com.example.transcenmod.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// client-side view of what the server is annihilating
// removal packets land 2s after the server pulls the entity,those 2s are the red shell
// keyed by uuid,shared between packet handlers and render hooks
public final class AnnihilationClientState {

    private static final Set<UUID> DOOMED = ConcurrentHashMap.newKeySet();

    // overrides the other bar sources instead of adding to them
    // a zombie can stay visible a moment after the switch,which restarted the music
    private static volatile boolean protectionOff;

    public static void setProtectionOff(boolean off) {
        protectionOff = off;
    }

    public static boolean isProtectionOff() {
        return protectionOff;
    }

    // the server removes its zombie and tells the client nothing, so the client keeps drawing it red
    // clearing this set is what makes a wiped encounter actually vanish
    public static void clearAllDoomed() {
        DOOMED.clear();
    }
    // server-driven HUD state, see HudSyncPacket
    private static volatile boolean hudActive;
    private static volatile float hudProgress = 1.0F;

    // latches on and is never cleared by a server update
    // the bar frames the whole encounter,tying it to one zombie made it strobe as repairs put it back
    // only reset() ends it
    private static volatile boolean hudLatched;

    private AnnihilationClientState() {}

    // red only, the server already dropped the entity
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
    // once per client tick,before anything else reads the set
    public static void noteTick() {
        if (!DOOMED.isEmpty()) {
        }
    }

    // called by the red application path so the probe can prove it ran

    // on latches,off is ignored once latched
    // the server cannot see the client's egg-used state,so its off means no zombie right now
    public static void setHud(boolean active, float progress) {
        if (active) {
            hudLatched = true;
        }
        hudActive = hudLatched || active;
        hudProgress = progress;
    }

    // the only thing that can latch before any server packet
    public static void latchHud() {
        hudLatched = true;
        hudActive = true;
    }

    public static boolean isHudLatched() {
        return hudLatched;
    }

    // the latch is deliberately immune to the server's off updates
    // a deliberate wipe is the one case that has to get past it,otherwise the bar outlives its zombies
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

    // stops state leaking between worlds
    public static void reset() {
        DOOMED.clear();
        hudActive = false;
        hudLatched = false;
        hudProgress = 1.0F;
    }
}
