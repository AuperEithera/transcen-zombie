package com.example.transcenmod.client;

/**
 * Every piece of annihilation state that only the client is allowed to remember.
 *
 * <p>The dedicated server owns the truth -- it decides which entities are being annihilated and how far the
 * wipe has progressed -- and pushes the parts a client needs over the mod's own packets. Nothing here is
 * authoritative, so nothing here is ever sent back.
 *
 * <p>All state is deliberately static. A Forge event handler is constructed once per side and there is
 * exactly one logical client per JVM, so an instance-based holder would only add a lifetime question --
 * and the packet hooks in {@code network/ClientPacketHooks} are static by necessity, because the message
 * classes that call them must load on a dedicated server too and therefore cannot hold a client object.
 *
 * <p>Every flag is {@code volatile}. They are written from the network thread (a packet handler) and read
 * from the client thread (the tick and render handlers), which is a genuine cross-thread hand-off.
 */
public final class AnnihilationClientState {

    /**
     * Set once the encounter is over for this client.
     *
     * <p>Distinct from "the HUD is currently drawn": this is the latch that stops the bar from coming back
     * after the wipe finished, because {@link #hudLatched} is cleared by the same call. {@code
     * ClientEvents#deactivateHud} raises it and only {@code activateHud} (a fresh encounter) or {@link
     * #reset} (disconnect) lowers it.
     */
    private static volatile boolean protectionOff;

    /** Whether the boss bar should be on screen right now, as last reported by the server. */
    private static volatile boolean hudActive;

    /**
     * Bar fill, 0..1.
     *
     * <p>Defaults to 1.0F, not 0: a client that has not received a sync yet should show a full bar rather
     * than an empty one, so the first frame of an encounter does not look like an already finished wipe.
     */
    private static volatile float hudProgress = 1.0F;

    /**
     * "This client has seen the encounter, keep suppressing the HUD even if the server's last report said
     * the bar is gone."
     *
     * <p>Without this the bar (and the overlay suppression that hangs off it) would flicker off between
     * two syncs, or for the one tick between the zombie spawning and the first {@code HudSyncMessage}
     * arriving.
     */
    private static volatile boolean hudLatched;

    /** Never instantiated: the whole class is state and static accessors. */
    private AnnihilationClientState() {}

    public static void setProtectionOff(boolean value) {
        protectionOff = value;
    }

    public static boolean isProtectionOff() {
        return protectionOff;
    }

    /**
     * Applies a server sync.
     *
     * <p>The latch is only ever raised here, never lowered, and an active sync forces the visible flag on
     * regardless: {@code hudActive = hudLatched || active}. A client that has been latched stays latched
     * until {@link #unlatchHud}, which is what makes the suppression survive a frame where the server sends
     * {@code active = false} because the zombie list momentarily read as empty.
     */
    public static void setHud(boolean active, float progress) {
        if (active) {
            hudLatched = true;
        }
        hudActive = hudLatched || active;
        hudProgress = progress;
    }

    /** Raises both the latch and the visible flag at once; used when the HUD is switched on locally. */
    public static void latchHud() {
        hudLatched = true;
        hudActive = true;
    }

    public static boolean isHudLatched() {
        return hudLatched;
    }

    /** Drops both the latch and the visible flag at once; the mirror image of {@link #latchHud}. */
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

    /**
     * Full forget, called on disconnect.
     *
     * <p>Every field is cleared, including {@code protectionOff} and the progress, so that a client that
     * leaves one server and joins another (or rejoins the same one) cannot start out poisoned by the
     * previous session's state. A stale {@code protectionOff = true} in particular would leave the player
     * with a permanently suppressed boss bar and no way to clear it short of restarting the game.
     */
    public static void reset() {
        hudActive = false;
        hudLatched = false;
        hudProgress = 1.0F;
        protectionOff = false;
    }
}
