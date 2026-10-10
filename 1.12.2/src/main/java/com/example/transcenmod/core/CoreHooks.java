package com.example.transcenmod.core;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State bridge between the injected bytecode and this mod.
 *
 * <p>This class is the 1.12.2 replacement for what {@code PlayerHealthMixin} did on 1.16.5, and its
 * placement is what makes the replacement work at all.
 *
 * <p>A coremod transformer runs on the launch classloader. The mod's own classes live in the mod
 * classloader, which is a child of it, so a constant reference from transformer bytecode to a mod class
 * would blow up with {@code NoClassDefFoundError} the moment a target class is transformed. Everything a
 * transformer needs to touch therefore has to live here, in a class with no Minecraft types at all and no
 * dependency on the mod loader. The mod pushes its state in through the setters below; the injected
 * bytecode reads it back through a single static call.
 *
 * <p>The 1.16.5 version answered {@code getHealth()} for <em>every</em> living entity and narrowed to
 * players inside the injection. That is possible in bytecode too, but a UUID keyed set is cheaper and,
 * more importantly, reaches the same answer without loading an entity class from inside a transformer.
 */
public final class CoreHooks {

    private CoreHooks() {}

    /** Players whose {@code getHealth()} must report full health while they carry the spawn egg. */
    private static final Set<UUID> PROTECTED = ConcurrentHashMap.newKeySet();

    /**
     * Players mid-retaliation.
     *
     * <p>Retaliation deliberately outranks the egg: being struck by the zombie is meant to be fatal even
     * for an otherwise immune carrier, so this set is consulted first and reports zero rather than full.
     * Without it the egg would silently immunise the player against the only consequence the encounter is
     * built around.
     */
    private static final Set<UUID> RETALIATING = ConcurrentHashMap.newKeySet();

    /** True while an encounter is running, i.e. while third-party boss bars are suppressed. */
    private static volatile boolean encounterPresent;

    // ---------------------------------------------------------------------------------------------
    // Health override
    // ---------------------------------------------------------------------------------------------

    /** Called every server tick for every player carrying the egg. */
    public static void setProtected(UUID player, boolean protectedNow) {
        if (protectedNow) {
            PROTECTED.add(player);
        } else {
            PROTECTED.remove(player);
        }
    }

    /** Called by the retaliation path before the player is cleared, and on the next tick after. */
    public static void setRetaliating(UUID player, boolean retaliating) {
        if (retaliating) {
            RETALIATING.add(player);
        } else {
            RETALIATING.remove(player);
        }
    }

    /**
     * The whole body of the injected check.
     *
     * <p>Returns {@code null} for "let vanilla answer", which is every entity that is not one of this
     * mod's protected players. Returning a boxed value rather than a primitive is what keeps the injected
     * bytecode to a single null test.
     */
    public static Float getHealthOverride(UUID id) {
        if (id == null) {
            return null;
        }
        if (RETALIATING.contains(id)) {
            return Float.valueOf(0.0F);
        }
        if (PROTECTED.contains(id)) {
            return Float.valueOf(20.0F);
        }
        return null;
    }

    /** Drops a player's protection when it leaves the world. */
    public static void clear(UUID player) {
        PROTECTED.remove(player);
        RETALIATING.remove(player);
    }

    // ---------------------------------------------------------------------------------------------
    // Boss bar suppression
    // ---------------------------------------------------------------------------------------------

    public static void setEncounterPresent(boolean present) {
        encounterPresent = present;
    }

    /**
     * Consulted at the head of {@code GuiBossOverlay.renderBossHealth}.
     *
     * <p>Returns true to skip the vanilla method outright. 1.16.5 reached the same place through
     * {@code ForgeIngameGui#pre/post}; 1.12.2 has neither, but its whole boss bar pass is one method, so
     * the equivalent is an early return there.
     */
    public static boolean shouldSuppressBossHealth() {
        return encounterPresent;
    }
}
