package com.example.transcenmod.util;

/**
 * Holds the "the zombie's retaliation is in progress" state.
 *
 * <p>This lives here rather than next to the mixin that reads it, and that placement is load-bearing
 * rather than tidy. Everything under the mixin package belongs to the mixin configuration, and Mixin
 * refuses to let such a class be referenced directly: loading it from ordinary code raises
 * "is in a defined mixin package ... and cannot be referenced directly". A helper class the rest of the
 * mod calls has to live outside that package.
 *
 * <p>Retaliation outranks the spawn egg. Carrying the egg makes a player unkillable against lightning,
 * the void, commands and everything else; striking the zombie is the one act that is meant to be fatal
 * regardless, and without this override the egg would silently immunise the player against the only
 * consequence the encounter is built around.
 */
public final class RetaliationState {

    private RetaliationState() {}

    private static volatile boolean retaliation;

    /** Called by the retaliation path before the player is cleared. */
    public static void mark() {
        retaliation = true;
    }

    /** Called when the retaliation is over, so the override does not outlive it. */
    public static void clear() {
        retaliation = false;
    }

    public static boolean isActive() {
        return retaliation;
    }
}
