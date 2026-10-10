package com.example.transcenmod.util;

/**
 * Holds the "the zombie's retaliation is in progress" state.
 *
 * <p>On 1.16.5 this lived outside the mixin package for a load-bearing reason: Mixin refuses to let a
 * config-owned class be referenced from ordinary code, and a helper the rest of the mod calls has to live
 * elsewhere. That constraint is gone here -- the injection is a class transformer with no package rules --
 * but the class stays exactly where it was, because it is part of this mod's shape and moving it would be
 * churn with no behaviour attached.
 *
 * <p>Retaliation outranks the spawn egg. Carrying the egg makes a player unkillable against lightning, the
 * void, commands and everything else; striking the zombie is the one act that is meant to be fatal
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
