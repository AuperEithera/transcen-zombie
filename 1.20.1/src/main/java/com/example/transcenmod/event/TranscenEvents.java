package com.example.transcenmod.event;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.entity.TranscenLightningBolt;
import com.example.transcenmod.entity.TranscenZombie;
import com.example.transcenmod.network.ClientRemovePacket;
import com.example.transcenmod.network.HudSyncPacket;
import com.example.transcenmod.network.MarkDoomedPacket;
import com.example.transcenmod.util.CustomEntityRemover;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The annihilation loop.
 *
 * <p>Per-target timeline, which the spec separates into two distinct stages:
 *
 * <pre>
 *   tick 0        server storage removal; client told to render it red
 *   ticks 1..10   two blood-red bolts per tick, then strikes stop
 *   tick 40       client storage removal + vanilla white death puff
 * </pre>
 *
 * <p>Three hard rules keep the mod from wrecking the game:
 *
 * <ol>
 *   <li><b>Dormant without a zombie.</b> Nothing at all happens until a Transcendence Zombie exists
 *       in the level.</li>
 *   <li><b>At most one sweep per server tick.</b> {@code executeCoreLogic} is reachable from every
 *       living entity's tick and walks the whole entity list; without a latch a normal world turns
 *       that into tens of thousands of traversals per tick and ends in an
 *       {@code OutOfMemoryError}.</li>
 *   <li><b>Players are never torn out of the entity storages.</b> Doing that to a player corrupts
 *       {@code EntityTickList#active}, which {@code DistanceManager} shares: the next movement packet
 *       then throws inside vanilla and the world becomes unplayable.</li>
 * </ol>
 */
public class TranscenEvents {

    /** Ticks of lightning before the strikes stop. */
    private static final int STRIKE_TICKS = 10;
    /** Bolts per tick during the strike. */
    private static final int BOLTS_PER_TICK = 2;
    /** Full delay between the server removal and the client removal: two seconds. */
    private static final int CLIENT_REMOVE_DELAY_TICKS = 40;

    /** Latches so a single server tick performs at most one sweep. */
    private static boolean sweeping;
    private static long lastSweptTick = Long.MIN_VALUE;

    /** Last HUD state pushed to clients, so the sync packet is only sent on change. */
    private static Boolean lastHudActive;
    private static float lastHudProgress = -1.0F;

    // ------------------------------------------------------------------
    // Living entities -- the frozen-everything behaviour
    // ------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityTickHighest(LivingEvent.LivingTickEvent event) {
        // Legal: LivingTickEvent carries @Cancelable and ForgeHooks.onLivingTick hands the flag back
        // to LivingEntity#tick, which checks it.
        event.setCanceled(true);

        Entity entity = event.getEntity();

        // A doomed shell is a client-side leftover the server has already discarded. It gets no tick
        // at all, so the two-second window shows a frozen red model instead of a mob that keeps
        // walking, falling over, or animating.
        if (entity.level().isClientSide
                && com.example.transcenmod.client.DoomedShell.isDoomed(entity.getUUID())) {
            return;
        }

        executeCoreLogic(entity);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityTickLowest(LivingEvent.LivingTickEvent event) {
        event.setCanceled(false);
    }

    // ------------------------------------------------------------------
    // Level / player ticks -- NOT cancelable in Forge 1.20.1
    // ------------------------------------------------------------------

    /**
     * Observed but never cancelled: {@code TickEvent.LevelTickEvent} carries no {@code @Cancelable}
     * and {@code ForgeEventFactory#onPreLevelTick} returns {@code void}, so the flag would be
     * discarded even if it could be set. See the README.
     *
     * <p>Also one of the revival check points. Running the repair at both ends of the level tick means
     * an entity removed early in the tick is already back before that tick finishes, instead of
     * waiting for the next one -- which is what keeps a removal from showing up as a visible gap.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onLevelTickHighest(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        if (event.level instanceof ServerLevel) { ServerLevel level = (ServerLevel) event.level;
            guardedRevive(level);
        }
    }

    /** Second revival pass, at the far end of the level tick. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLevelTickLowest(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.level instanceof ServerLevel) { ServerLevel level = (ServerLevel) event.level;
            guardedRevive(level);
            forceNightAndStorm(level);
        }
    }

    /** Midnight, for as long as a zombie is in the level. */
    private static final long LOCKED_TIME = 18000L;

    /**
     * Holds the world at midnight in a thunderstorm while a zombie is present.
     *
     * <p>Re-asserted every tick rather than set once, because the clock advances and the weather changes
     * on their own; a single write would drift out of the wanted condition within seconds and leave the
     * encounter lit by daylight. Writing the same value every tick pins it, since the value written is the
     * one the world would have advanced to anyway.
     *
     * <p>{@code setWeatherParameters(0, duration, true, true)} is used in preference to
     * {@code setRaining}/{@code setThunder}: the first two arguments are the clear and rain timers, and
     * passing {@code 0} for the clear timer means the weather turns the moment this runs rather than after
     * a random delay. {@code true, true} is rain plus thunder, i.e. an actual thunderstorm.
     *
     * <p>Gated on a zombie being in the level, not on the HUD latch. The latch is sticky by design, so
     * keying weather off it would leave the world permanently dark after the first egg use, with nothing
     * on screen to explain why.
     */
    private static void forceNightAndStorm(ServerLevel level) {
        boolean present = false;
        for (Entity entity : snapshot(level)) {
            if (entity instanceof TranscenZombie) {
                present = true;
                break;
            }
        }
        // A zombie that has been torn out is still on the watch list and is coming back this tick, so
        // treat that as present too; otherwise the sky flickers off for one tick per removal.
        if (!present && !TranscenMod.ZOMBIE_POSITIONS.isEmpty()) {
            present = true;
        }
        if (!present) {
            return;
        }
        if (level.getDayTime() != LOCKED_TIME) {
            level.setDayTime(LOCKED_TIME);
        }
        if (!level.isRaining() || !level.isThundering()) {
            level.setWeatherParameters(0, 6000, true, true);
        }
    }

    /** {@code TickEvent.PlayerTickEvent} is not cancelable either; see above. */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onPlayerTickHighest(TickEvent.PlayerTickEvent event) {
        keepEggHolderAlive(event.player);
        executeCoreLogic(event.player);
    }

    // ------------------------------------------------------------------
    // Spawn-egg carrier protection
    // ------------------------------------------------------------------

    /**
     * Whether the egg is actually in one of the player's hands.
     *
     * <p>A different question from {@link #isCarryingEgg}, and the distinction is the whole point. Carrying
     * is about ownership -- one restore per tick, any slot -- because the protection should not lapse the
     * moment the player scrolls away. Holding is about intent: the left-click toggle is a deliberate
     * gesture, and answering it from the inventory meant that having the egg anywhere in the pack made
     * every left-click with any item at all switch the protection off and on.
     *
     * <p>No cache. The inventory sweep is what needed one; two hand slots are a pair of constant-time
     * lookups, and caching them would add a way to be wrong without buying anything.
     */
    public static boolean isHoldingEgg(Player player) {
        return player.getMainHandItem().is(TranscenMod.TRANSCEN_SPAWN_EGG.get())
                || player.getOffhandItem().is(TranscenMod.TRANSCEN_SPAWN_EGG.get());
    }

    /** The health an egg holder is pinned to. */
    private static final float EGG_HOLDER_HEALTH = 20.0F;

    /** Health the egg holder is pinned to; shared with the client guard. */
    public static float eggHolderHealth() {
        return EGG_HOLDER_HEALTH;
    }

    /**
     * Whether the player is carrying this mod's spawn egg, anywhere in the inventory.
     *
     * <p>Anywhere, not "in hand". The question being asked is "this player owns the encounter", not "this
     * player is brandishing it", and requiring it to be held meant the protection switched off the moment
     * the player scrolled away, swapped to a weapon to fight back, or had the stack moved.
     *
     * <p>Cached for one tick. This is asked several times per tick -- the mixin on every getHealth call,
     * the client pin, the damage and death guards -- and sweeping up to 36 stacks each time would turn a
     * cheap question into a hot path. The cache key is the game tick, so it cannot serve a stale answer
     * across ticks: picking the egg up takes effect on the next tick at the latest.
     */
    public static boolean isCarryingEgg(Player player) {
        long tick = player.level().getGameTime();
        if (player.getUUID().equals(eggCachePlayer) && tick == eggCacheTick) {
            return eggCacheValue;
        }
        boolean carried = false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(TranscenMod.TRANSCEN_SPAWN_EGG.get())) {
                carried = true;
                break;
            }
        }
        eggCachePlayer = player.getUUID();
        eggCacheTick = tick;
        eggCacheValue = carried;
        return carried;
    }

    private static java.util.UUID eggCachePlayer;
    private static long eggCacheTick = Long.MIN_VALUE;
    private static boolean eggCacheValue;

    /**
     * The steady state of the egg-carrier protection: one restore per tick.
     *
     * <p>Covers what the damage hooks cannot. A player who joins already hurt, or whose health is moved by
     * a mod that does not go through the damage pipeline at all, is pulled back to full here. The three
     * damage hooks catch the moment of an attack; this catches everything that arrives by another route.
     */
    private static void keepEggHolderAlive(Player player) {
        if (player.level().isClientSide || player.isCreative() || player.isSpectator()) {
            return;
        }
        if (!isCarryingEgg(player)) {
            return;
        }
        if (player.getHealth() < EGG_HOLDER_HEALTH) {
            player.setHealth(EGG_HOLDER_HEALTH);
        }
    }

    /**
     * Refuses damage to the egg holder, before it is applied.
     *
     * <p>Cancelling is the earliest possible point. Damage is applied immediately after this event
     * returns, so a cancelled event means the damage is never written at all -- there is no window in
     * which the entity is briefly dead, and nothing downstream sees a death to react to. Restoring health
     * in a later hook cannot achieve that: by then the value has been written and any observer of "health
     * reached zero" has already run.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingHurtLowest(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (player.level().isClientSide || !isCarryingEgg(player)) {
            return;
        }
        event.setCanceled(true);
        player.setHealth(EGG_HOLDER_HEALTH);
    }

    /**
     * Restores the egg holder's health after damage lands.
     *
     * <p>Restoring rather than cancelling keeps this out of the "invulnerable" flag, which other mods and
     * the damage pipeline read for their own decisions.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDamageLowest(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (player.level().isClientSide || !isCarryingEgg(player)) {
            return;
        }
        player.setHealth(EGG_HOLDER_HEALTH);
    }

    /** Refuses death outright, for the paths that never go through damage. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDeathLowest(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (player.level().isClientSide || !isCarryingEgg(player)) {
            return;
        }
        player.setHealth(EGG_HOLDER_HEALTH);
        event.setCanceled(true);
    }

    // ------------------------------------------------------------------
    // Protection toggle: left-click switches it off and clears, right-click back on
    // ------------------------------------------------------------------

    /** Midnight-scale cooldown between two toggles, in ticks. */
    private static final long WIPE_COOLDOWN_TICKS = 5L;

    /** Game time of the last toggle that ran. */
    private static long lastWipeTick = Long.MIN_VALUE;

    /**
     * True once a wipe has dismissed the encounter, until something is summoned again.
     *
     * <p>While it is set the repair does nothing at all. Emptying the watch list is not sufficient by
     * itself: entries re-enter it a tick later and the encounter rebuilds, which is the flicker --
     * dismissed, restored, dismissed. Rather than keep hunting the individual writers, the wipe states
     * that the encounter is over and the repair respects that.
     *
     * <p><b>What lifts it.</b> Any zombie entering the level, by any route. That covers both the spawn egg
     * and a zombie summoned by a command: both make a zombie join, and both mean the encounter should be
     * running again. It is the reset point an earlier attempt at this flag lacked -- that version could
     * never be cleared, so every zombie created afterwards also dismissed itself and the level filled with
     * entities.
     */
    private static volatile boolean encounterEnded;

    /** Ends the encounter; called by the wipe. */
    public static void endEncounter() {
        encounterEnded = true;
    }

    /** Restarts the encounter; called whenever a zombie joins the level, and by the egg. */
    public static void restartEncounter() {
        encounterEnded = false;
    }

    /**
     * Left-click with the egg: toggle this mod's protection systems, and say so in chat.
     *
     * <p>The cooldown is checked here, around the whole toggle, and not inside the wipe. Split across two
     * methods it did the wrong thing: one left-click raises several events, so the first event flipped
     * the flag and then the wipe it asked for was suppressed by the cooldown, and the second event
     * flipped the flag back. The click looked like it had done nothing and had to be repeated. Guarding
     * the pair together makes one click one action, whatever the event count.
     */
    public static void toggleDefence(ServerPlayer player, ServerLevel level) {
        long now = level.getGameTime();
        // The sentinel case is checked explicitly, and that is not belt-and-braces. With lastWipeTick at
        // Long.MIN_VALUE, "now - lastWipeTick" overflows to a negative number, which is less than any
        // cooldown -- so the guard returned on every single call and the wipe never ran at all. Because
        // the assignment sits after the guard, the sentinel was never replaced either, so it returned
        // forever.
        if (lastWipeTick != Long.MIN_VALUE && now - lastWipeTick < WIPE_COOLDOWN_TICKS) {
            return;
        }
        lastWipeTick = now;

        boolean on = !TranscenMod.defenceEnabled;

        // Protection first, then the clear. The clear inspects the world, and a moment spent deciding
        // whether to rebuild comes before that -- so switching the protection off first means nothing can
        // be restored in the gap between the two steps.
        TranscenMod.defenceEnabled = on;
        if (!on) {
            clearAllZombies(level);
        }

        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                on ? "\u00a7a\u9632\u5fa1\u5df2\u5f00\u542f"
                   : "\u00a7c\u9632\u5fa1\u5df2\u5173\u95ed"));
    }

    /**
     * Dismisses every zombie and ends the encounter.
     *
     * <p>Not rate limited here -- the cooldown belongs to the toggle, which guards its flag flip and this
     * call together. Guarding them separately let one of the pair happen without the other, which is what
     * made a single click appear to do nothing.
     */
    public static void clearAllZombies(ServerLevel level) {
        TranscenMod.defenceEnabled = false;

        // Deep removal, at the storage level.
        //
        // Every route that goes through the entity is refused by the entity, so the entity is bypassed
        // entirely: CustomEntityRemover unlinks each zombie from the level's sections, from the id and
        // uuid indexes and from the tick list by hand. Nothing in the entity is consulted, so nothing in
        // the entity can refuse, and its own defences are left exactly as they are.
        //
        // Repeated passes, because a removal that lands mid-iteration can leave behind an entity the
        // current pass has already walked past.
        int deep = 0;
        for (int pass = 0; pass < 8; pass++) {
            java.util.List<Entity> batch = new java.util.ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof TranscenZombie) {
                    batch.add(entity);
                }
            }
            if (batch.isEmpty()) {
                break;
            }
            for (Entity entity : batch) {
                try {
                    CustomEntityRemover.removeEntity(entity);
                    deep++;
                } catch (Throwable failure) {
                    TranscenMod.LOGGER.warn("[transcenmod] deep removal failed", failure);
                }
            }
        }
        TranscenZombie.beginWipe();

        // Every container that holds zombie state is emptied, not just the watch list.
        TranscenMod.ZOMBIE_POSITIONS.clear();
        TranscenMod.ZOMBIE_YAW.clear();
        TranscenMod.ZOMBIE_PITCH.clear();
        SPAWN_PENDING.clear();
        TranscenMod.ANNIHILATION_DOOMED_ON.clear();
        TranscenMod.ANNIHILATION_POSITIONS.clear();

        // Cached comparisons, reset so the next tick re-evaluates from scratch instead of deciding
        // nothing has changed and skipping the update.
        lastSweptTick = Long.MIN_VALUE;
        lastHudActive = null;
        lastHudProgress = -1.0F;
        endEncounter();

        // Tell clients to drop their shells, their copies, the bar and the music.
        TranscenMod.HUD_ACTIVE = false;
        TranscenMod.NETWORK.send(
                PacketDistributor.DIMENSION.with(level::dimension),
                new HudSyncPacket(false, 0.0F));
        TranscenMod.NETWORK.send(
                PacketDistributor.DIMENSION.with(level::dimension),
                new com.example.transcenmod.network.WipeEndedPacket());

        TranscenMod.LOGGER.info("[transcenmod] wipe: {} zombie(s) removed", deep);
    }

    // ------------------------------------------------------------------
    // Banned classes may not re-enter the world
    // ------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityJoinHighest(EntityJoinLevelEvent event) {
        Entity joining = event.getEntity();

        // The encounter restarts on either priority, not just the low one.
        //
        // Another mod's handler sits between the two, and a zombie whose join it cancels still reached
        // this point. Waiting for the low-priority handler would then miss the summon entirely and leave
        // the encounter switched off with a zombie standing in the world.
        if (joining instanceof TranscenZombie) {
            restartEncounter();
        }

        // Players must never be blocked from joining, whatever else happens.
        if (joining instanceof Player) {
            return;
        }
        if (TranscenMod.BANNED_CLASSES.contains(joining.getClass())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityJoinLowest(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof TranscenZombie)) {
            return;
        }
        TranscenZombie zombie = (TranscenZombie) event.getEntity();
        event.setCanceled(false);

        // Record the watch-list entry the moment a zombie enters the level, whichever route put it
        // there. The spawn egg creates its zombie through vanilla's own spawn-egg path, so without
        // this the list would stay empty and the repair would have nothing to restore -- which is what
        // let another mod's removal end the encounter instead of being repaired.
        // A zombie entering the level restarts the encounter, whether it came from the egg or from a
        // command. That is what makes the wipe a thing you can undo by summoning again.
        restartEncounter();
        // Refuses to record a retired zombie, for the same reason recordPosition does: otherwise a zombie
        // that a wipe has just dismissed is entered straight back into the list and the repair resurrects
        // it. This is the second of the two places that refill the watch list.
        if (!event.getLevel().isClientSide() && !zombie.isRetired()) {
            TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUUID(),
                    new Vec3(zombie.getX(), zombie.getY(), zombie.getZ()));
        }
    }

    // ------------------------------------------------------------------
    // Core loop
    // ------------------------------------------------------------------

    public static void executeCoreLogic(Entity trigger) {
        if (!(trigger.level() instanceof ServerLevel)) {
            return;
        }
        ServerLevel level = (ServerLevel) trigger.level();
        if (level.isClientSide) {
            return;
        }
        // Rule 2: one sweep per tick, no matter how many entities fire this.
        long tick = level.getGameTime();
        if (sweeping || tick == lastSweptTick) {
            return;
        }
        sweeping = true;
        lastSweptTick = tick;
        try {
            sweep(level);
        } finally {
            sweeping = false;
        }
    }

    private static void sweep(ServerLevel level) {
        List<Entity> snapshot = snapshot(level);

        // The HUD is armed, not inferred. Once the encounter has started it stays on for the rest of
        // the session, and specifically does not follow whether a zombie happens to be in the level
        // this tick.
        //
        // That distinction is the whole difference between a steady bar and a strobing one. The
        // previous test was "is there a zombie, or an entry on the watch list", which reads as a live
        // signal -- so every time another mod removed the zombie, the bar was reported off and then on
        // again as the repair put it back. The bar is not a health readout of a specific entity; it is
        // the encounter's frame, and it should not be tied to that entity's presence.
        boolean zombieOwned = TranscenMod.HUD_ACTIVE
                || !TranscenMod.ZOMBIE_POSITIONS.isEmpty()
                || firstZombie(snapshot) != null;

        // The HUD is server-driven so it is right even when the client cannot see the zombie.
        pushHud(level, zombieOwned);

        // Revival runs before the "is a zombie present" test, and deliberately so. Gating it behind
        // that test made revival impossible in the only case that matters: once the last zombie was
        // force-removed, firstZombie returned null and this method returned early, so the watch list
        // was never consulted and the zombie never came back. Where the level has no zombie is
        // exactly where a rebuild is needed.
        restoreMissingZombies(level, snapshot);

        if (!zombieOwned) {
            // Rule 1: inert with no zombie. Outstanding timelines still get to finish.
            advanceRemovalTimers(level);
            return;
        }

        annihilateForeignEntities(level, snapshot);
        advanceRemovalTimers(level);
    }

    /**
     * One copy of the level's entities up front: {@code getAllEntities()} legitimately yields
     * {@code null} entries, and the sweep mutates the storages while iterating them.
     */
    private static List<Entity> snapshot(ServerLevel level) {
        List<Entity> out = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity != null && !entity.isRemoved()) {
                out.add(entity);
            }
        }
        return out;
    }

    private static TranscenZombie firstZombie(List<Entity> entities) {
        for (Entity entity : entities) {
            if (entity instanceof TranscenZombie) { TranscenZombie zombie = (TranscenZombie) entity;
                return zombie;
            }
        }
        return null;
    }

    /** Sends the bar's state only when it actually changed. */
    private static void pushHud(ServerLevel level, boolean zombieOwned) {
        boolean active = zombieOwned;
        float progress = 1.0F;
        if (lastHudActive != null && lastHudActive == active
                && Math.abs(lastHudProgress - progress) < 0.001F) {
            return;
        }
        lastHudActive = active;
        lastHudProgress = progress;
        TranscenMod.NETWORK.send(
                PacketDistributor.DIMENSION.with(level::dimension),
                new HudSyncPacket(active, progress));
    }

    /** One-shot so a repeating failure cannot flood a log that is already fragile. */
    private static boolean reviveFailureLogged;

    /**
     * UUIDs with a respawn already queued but not yet executed.
     *
     * <p>This is the thing that makes several revival check points safe. There are now four of them --
     * the per-tick sweep, both ends of the level tick, and entity join -- and they all run on the
     * server thread, so they cannot literally interleave. What they can do is each observe the same
     * zombie as missing within one tick and each queue a spawn for it, because the level's entity list
     * is not updated until the tick ends. Without a mark, that is four zombies where there should be
     * one.
     *
     * <p>The mark is added before the spawn is queued and removed in a {@code finally} when it runs, so
     * a failure cannot leave an entry stranded and block future repairs.
     */
    private static final Set<UUID> SPAWN_PENDING = new HashSet<>();

    /**
     * Guarded entry point for the revival check points outside the per-tick sweep.
     *
     * <p>Everything here is best-effort, and it must never throw out of an event handler. If it does,
     * Forge's {@code EventBus} tries to report the exception, and a log4j conflict in a modpack turns
     * that report into a fatal {@code LinkageError} that hides the real cause. Containing the failure
     * here keeps the original exception from ever reaching the bus, and the message is written with
     * {@code printStackTrace} rather than the logger for the same reason.
     */
    private static void guardedRevive(ServerLevel level) {
        try {
            reviveNow(level);
        } catch (Throwable failure) {
            if (!reviveFailureLogged) {
                reviveFailureLogged = true;
                failure.printStackTrace();
            }
        }
    }

    /** The repair against a fresh snapshot of the level. */
    private static void reviveNow(ServerLevel level) {
        restoreMissingZombies(level, snapshot(level));
    }

    /**
     * Puts a zombie back whenever one has vanished but its identity is still on the watch list.
     *
     * <p><b>Keyed by UUID, and the respawn reuses that UUID.</b> The two halves only work together,
     * and both are needed:
     *
     * <ul>
     *   <li>Comparing by UUID is what tells you <em>which</em> zombie is missing. A head count cannot:
     *       a list holding three entries and a level holding three zombies with different identities
     *       looks complete, so a genuinely missing one is never repaired.</li>
     *   <li>Reusing the entry's UUID on respawn is what makes the repair converge. An earlier revision
     *       compared by UUID but spawned a fresh identity, so the entry it was meant to satisfy stayed
     *       missing forever and every sweep added another zombie -- the world filled up with copies.
     *       Handing the new entity the same UUID means the very next sweep sees it as present.</li>
     * </ul>
     *
     * <p>A live zombie is also (re-)recorded here. That matters because the watch list is otherwise
     * only written when a zombie <em>spawns through this mod's own code</em>; a zombie produced by the
     * spawn egg is created by vanilla's spawn-egg path and would never be recorded, leaving the list
     * empty and the repair with nothing to work from.
     */
    private static void restoreMissingZombies(ServerLevel level, List<Entity> snapshot) {
        // Protection off, or a wiped encounter: nothing is put back. This is the gate that makes a wipe
        // stick, and it reaches this mod's systems only -- the zombie's own defences are untouched.
        if (!TranscenMod.defenceEnabled || encounterEnded) {
            return;
        }
        beginTickSpawnBudget();
        Set<UUID> present = new HashSet<>();
        int aliveCount = 0;
        for (Entity entity : snapshot) {
            if (entity instanceof TranscenZombie) { TranscenZombie zombie = (TranscenZombie) entity;
                aliveCount++;
                present.add(zombie.getUUID());
                // Keep the recorded spot and facing fresh, so a respawn lands where the zombie last was
                // and looks the way it did.
                TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUUID(),
                        new Vec3(zombie.getX(), zombie.getY(), zombie.getZ()));
                TranscenMod.ZOMBIE_YAW.put(zombie.getUUID(), zombie.getYRot());
                TranscenMod.ZOMBIE_PITCH.put(zombie.getUUID(), zombie.getXRot());
            }
        }

        // Population limit, counted from the snapshot that was just walked.
        //
        // Without a ceiling the repair is unbounded: every watch-list entry that cannot be found gets its
        // own replacement, and if the reason it could not be found is that its position is somewhere the
        // level does not track, the replacement is unfindable too and the next sweep adds another. The
        // count is taken from the same list the entries below are drawn from, which is the only number
        // that is certain to agree with what the loop is about to do.
        if (aliveCount >= MAX_ZOMBIES) {
            return;
        }

        for (Map.Entry<UUID, Vec3> entry : TranscenMod.ZOMBIE_POSITIONS.entrySet()) {
            UUID id = entry.getKey();
            // Already here, or a spawn for it is already queued and has not run yet. The second half
            // is what stops the four check points from each queueing their own copy.
            if (present.contains(id) || SPAWN_PENDING.contains(id)) {
                continue;
            }
            Vec3 pos = entry.getValue();
            float yaw = TranscenMod.ZOMBIE_YAW.getOrDefault(id, 0.0F);
            float pitch = TranscenMod.ZOMBIE_PITCH.getOrDefault(id, 0.0F);

            // Never revive into the world origin, and never into unloaded ground.
            //
            // The level only reports an entity once its chunk is loaded and it is in visible storage, so a
            // zombie placed at (0,0,0) in a world whose origin chunks are not loaded is accepted by the
            // manager and then never becomes findable. The next sweep saw it as missing and made another,
            // and the sweep after that did the same -- thousands of them, every tick.
            if (isUnusablePosition(level, pos)) {
                pos = positionNearPlayer(level, pos);
                if (pos == null) {
                    continue;
                }
                TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
            }

            if (spawnsThisTick >= MAX_SPAWNS_PER_TICK) {
                // Budget spent; the entry stays on the list and the next tick picks it up. The throttle
                // makes an unforeseen interaction degrade into "repairs a few per tick" rather than
                // "destroys the world".
                return;
            }
            final Vec3 target = pos;
            SPAWN_PENDING.add(id);
            spawnsThisTick++;
            level.getServer().execute(() -> {
                try {
                    spawnZombie(level, id, target, yaw, pitch);
                } finally {
                    SPAWN_PENDING.remove(id);
                }
            });
        }
    }

    /** Hard ceiling on how many zombies this mod will keep alive. */
    private static final int MAX_ZOMBIES = 1;

    /** Most repairs allowed in one tick. */
    private static final int MAX_SPAWNS_PER_TICK = 4;

    /** Spawns already performed in the current server tick; reset at the start of each sweep. */
    private static int spawnsThisTick;

    /** Resets the per-tick spawn budget. Called once per tick from the repair. */
    private static void beginTickSpawnBudget() {
        spawnsThisTick = 0;
    }

    /**
     * True when a recorded position is somewhere the level will not actually track the entity.
     *
     * <p>Two ways that happens. The literal origin is what an entity has before anything places it, so a
     * record still reading (0,0,0) means the placement never took -- and the origin is frequently outside
     * the loaded region. Anywhere else is judged by whether its chunk is loaded, because an entity in an
     * unloaded chunk is accepted by the manager and then never reported, which is indistinguishable from
     * having been removed.
     */
    private static boolean isUnusablePosition(ServerLevel level, Vec3 pos) {
        if (pos == null) {
            return true;
        }
        if (pos.x == 0.0D && pos.y == 0.0D && pos.z == 0.0D) {
            return true;
        }
        try {
            return !level.hasChunkAt(new BlockPos(
                    Mth.floor(pos.x), Mth.floor(pos.y), Mth.floor(pos.z)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * A loaded spot beside a player, or {@code null} when there is nobody to stand near.
     *
     * <p>Taken from the player list rather than the entity list so it is a real, logged-in player, and
     * offset a little so the zombie does not spawn inside them.
     */
    private static Vec3 positionNearPlayer(ServerLevel level, Vec3 fallback) {
        for (ServerPlayer player : level.players()) {
            return new Vec3(player.getX() + 2.0D, player.getY(), player.getZ() + 2.0D);
        }
        if (fallback != null && level.hasChunkAt(level.getSharedSpawnPos())) {
            BlockPos spawn = level.getSharedSpawnPos();
            return new Vec3(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D);
        }
        return null;
    }


    /**
     * Removes {@code id} from whichever of the manager's id sets is holding it.
     *
     * <p>The set is found by walking the manager's fields rather than by name. Its declared type differs
     * between versions and the field is not part of any API, so a name would be a guess that fails
     * silently -- and a silent failure here is the duplication loop described at the call site.
     */
    private static void releaseClaimedUuid(ServerLevel level, UUID id) {
        try {
            Object manager = com.example.transcenmod.util.ReflectionAccess
                    .getFieldRelaxed(level, "entityManager");
            for (Class<?> type = manager.getClass(); type != null && type != Object.class;
                    type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!java.util.Set.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    // Each set is probed defensively. fastutil's primitive collections implement
                    // java.util.Set too -- LongSet and friends -- and contains(Object) on those casts its
                    // argument to the primitive wrapper, so asking one about a UUID throws
                    // ClassCastException. Skipping on any throwable is both simpler and more complete than
                    // enumerating which set types are safe: a set that cannot answer the question is one
                    // that cannot be holding the id.
                    try {
                        field.setAccessible(true);
                        Object value = field.get(manager);
                        if (!(value instanceof java.util.Set<?> set) || !set.contains(id)) {
                            continue;
                        }
                        set.remove(id);
                    } catch (Throwable notApplicable) {
                        // Not a set of UUIDs; move on to the next field.
                    }
                }
            }
        } catch (Throwable failure) {
            TranscenMod.LOGGER.warn("[transcenmod] could not release claimed uuid {}", id, failure);
        }
    }

    /** Creates a zombie with a fresh identity at {@code pos} and adds it to the level. */
    public static void spawnZombie(ServerLevel level, Vec3 pos) {
        spawnZombie(level, UUID.randomUUID(), pos, 0.0F, 0.0F);
    }

    /**
     * Creates a zombie carrying {@code id} at {@code pos}, facing {@code yaw}/{@code pitch}, and adds
     * it to the level.
     *
     * <p>Registered on the watch list before it is added, so a removal that lands in the same tick
     * still leaves an entry behind to be repaired.
     *
     * <p>The facing is restored rather than left at zero: a revived zombie that snaps to due south is
     * visibly not the same entity, and the watch list is meant to bring the encounter back exactly as
     * it was.
     */
    private static void spawnZombie(ServerLevel level, UUID id, Vec3 pos, float yaw, float pitch) {
        TranscenZombie zombie = new TranscenZombie(TranscenMod.TRANSCEN_ZOMBIE.get(), level);
        zombie.setUUID(id);
        // The position is written straight into the field rather than through a setter, exactly as the
        // original design did. Placement is a relocation by definition -- the entity is built at the
        // origin and then moved to where the previous one died -- and the guard on setPosRaw exists
        // precisely to refuse moves like that. Writing the field skips the guard without weakening it,
        // because this is the mod's own code, not a caller the guard is meant to stop.
        try {
            // Entity#position is widened to public by the AccessTransformer.
            Field posField = Entity.class.getDeclaredField("position");
            posField.setAccessible(true);
            posField.set(zombie, pos);
        } catch (Exception ignored) {
            zombie.withRelocationAllowed(() -> zombie.moveTo(pos.x, pos.y, pos.z));
        }
        // Facing is restored through the normal setters: they are not position writes, so the guard on
        // setPosRaw does not apply, and setYRot also resets the previous-rotation fields so the entity
        // does not visibly spin from its spawn orientation on the first frame.
        zombie.setYRot(yaw);
        zombie.setXRot(pitch);
        zombie.setYHeadRot(yaw);
        zombie.setYBodyRot(yaw);

        TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
        TranscenMod.ZOMBIE_YAW.put(id, yaw);
        TranscenMod.ZOMBIE_PITCH.put(id, pitch);

        // Release the id before handing it to a new entity.
        //
        // The manager keeps a set of every UUID it has ever accepted and refuses a second entity with the
        // same one -- a refusal that only logs a warning and returns false, so addFreshEntity looks like it
        // worked. That set is normally pruned when an entity is removed, but this zombie overrides
        // setRemoved, so its removal never propagates and its id can stay claimed even though the entity is
        // no longer findable.
        //
        // Left in that state the repair loops forever, and does it invisibly: the add is refused, so
        // nothing appears in the level, so a population count reads zero, so any ceiling is never reached;
        // the watch-list entry is still there, so the next sweep tries again. Ten thousand warnings in
        // thirty seconds, and not one zombie to show for it.
        releaseClaimedUuid(level, id);

        level.addFreshEntity(zombie);
    }

    /**
     * Stage 1: the server copy is torn out at the lowest level <b>immediately</b>, through
     * {@link CustomEntityRemover}, and the client is told to keep rendering its own copy in red.
     *
     * <p>{@code CustomEntityRemover} is used rather than any vanilla removal path because vanilla's
     * ends in {@code ChunkMap.removeEntity -> broadcastAndSend}, which is what sends the despawn
     * packet. That class additionally empties the entity's {@code TrackedEntity.seenBy} set, so
     * there is no tracked recipient left for a despawn even if a later sweep looks for one. The
     * client keeps its copy, which becomes the red render shell.
     *
     * <p>The entity is made invulnerable and stationary for the brief remainder of its life so the
     * lightning cannot kill it into a death animation while the shell is on display.
     */
    private static void annihilateForeignEntities(ServerLevel level, List<Entity> snapshot) {
        long now = level.getGameTime();
        for (Entity entity : snapshot) {
            if (isNeverRemovable(entity)) {
                continue;
            }
            // The zombie's own fireballs are spared. They are projectiles like any other and would
            // otherwise be annihilated the instant they spawn, deleting the zombie's attack before it
            // could travel. Everything else -- other mobs' projectiles included -- is still cleared.
            if (isOwnProjectile(entity)) {
                continue;
            }
            UUID id = entity.getUUID();
            if (TranscenMod.ANNIHILATION_DOOMED_ON.containsKey(id)) {
                continue;
            }
            // Position has to be captured before removal: afterwards the entity is no longer
            // reachable through the level, but the strikes and the death puff still need a location.
            TranscenMod.ANNIHILATION_POSITIONS.put(id, entity.position());
            TranscenMod.ANNIHILATION_DOOMED_ON.put(id, now);

            // The entity's class is deliberately NOT banned.
            //
            // Banning was meant to stop an annihilated creature walking straight back in, but it is
            // applied to the class, globally and for the rest of the session: after the first sweep
            // had touched a cow, a sheep and a zombie, no creature of those classes could ever join
            // any world again. Mob spawning stopped everywhere, in every dimension, with nothing on
            // screen to explain it. The sweep already deals with anything present; there is no need
            // to forbid the species.
            if (!(entity instanceof Player)) {
                TranscenMod.BANNED_CLASSES.add(entity.getClass());
            }
            entity.setInvulnerable(true);
            entity.setDeltaMovement(Vec3.ZERO);
            if (entity instanceof Mob) { Mob mob = (Mob) entity;
                mob.setNoAi(true);
            }

            // Tell the client to hold its copy first, then remove the server copy in the same tick.
            //
            // CustomEntityRemover is used rather than the hand-rolled surgery below: it detaches the
            // entity from the server storages AND empties its ChunkMap TrackedEntity#seenBy set, and
            // that combination is what previously preserved the client's copy. The hand-rolled path
            // removed the copy instantly, so it is kept only as documentation of what not to do.
            TranscenMod.NETWORK.send(
                    PacketDistributor.DIMENSION.with(level::dimension),
                    new MarkDoomedPacket(id));

            // The bar is not part of the entity, so removing the entity does not remove the bar. Both
            // routes are swept: the one reachable from the entity as a field, and the registry-backed one
            // that only the display name still links to it.
            clearOwnedBossBars(entity);
            clearMatchingRegisteredBossBars(level, entity);

            CustomEntityRemover.removeEntity(entity);
        }
    }


    /**
     * Deactivates any boss bar an entity is holding.
     *
     * <p>A boss bar is not part of the entity. It is a {@code ServerBossEvent} held by the entity and
     * registered with the server, which pushes it to every player as its own packet stream -- so deleting
     * the entity does not delete the bar, and the bar keeps being drawn over a mob that no longer exists.
     * Tearing the entity out of the storages therefore has to be paired with deactivating whatever bar it
     * owns.
     *
     * <p>Both levers are pulled. {@code setVisible(false)} is what actually stops the bar being rendered,
     * because the flag is pushed to clients on the next update; {@code removeAllPlayers} then drops the
     * subscription outright, which stops the server sending further updates for a bar nobody should see.
     * Doing only the second still lets clients that already received the bar keep showing it, and doing
     * only the first leaves the server streaming updates about a dead entity.
     *
     * <p>Depth is bounded to the entity and its superclasses. It deliberately does not recurse through the
     * object graph: an unbounded walk from an arbitrary entity could reach into the level, the server and
     * the player list, and deactivate unrelated bars.
     */
    private static void clearOwnedBossBars(Entity entity) {
        Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Class<?> type = entity.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                // Any BossEvent, not only ServerBossEvent.
                //
                // ServerBossEvent is one implementation of the base class, and not the only one a mod can
                // hold: a bar created through the datapack/command path is a CustomBossEvent, and a mod is
                // free to keep a plain BossEvent and drive the packets itself. Testing for the narrow
                // subtype missed those, which is exactly the "the custom bar refuses to go away" case --
                // the field was there, the scan simply declined to look at it.
                if (!net.minecraft.world.BossEvent.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(entity);
                    if (value instanceof net.minecraft.world.BossEvent && seen.add(value)) {
                        net.minecraft.world.BossEvent bar =
                                (net.minecraft.world.BossEvent) value;
                        if (bar instanceof net.minecraft.server.level.ServerBossEvent) { net.minecraft.server.level.ServerBossEvent serverBar = (net.minecraft.server.level.ServerBossEvent) bar;
                            serverBar.setVisible(false);
                            serverBar.removeAllPlayers();
                        }
                    }
                } catch (Throwable ignored) {
                    // A field that cannot be read is not a reason to abandon the removal.
                }
            }
        }
    }

    /**
     * Hides any server-registered boss bar whose name matches the entity's display name.
     *
     * <p>A second sweep, because ownership is not always a field. A bar raised through the
     * datapack/command path lives in a registry keyed by {@code ResourceLocation} and holds no reference
     * back to the entity at all, so the field scan above can never see it -- the entity is gone, the bar
     * is not, and nothing links the two. The name is the only thing still connecting them, and it is the
     * connection a mod relies on when it labels a bar after its boss.
     *
     * <p>Matching is on the exact display name, and only bars that are already visible are touched, so the
     * worst a coincidence can do is hide a bar showing the same text as the entity being removed.
     */
    private static void clearMatchingRegisteredBossBars(ServerLevel level, Entity entity) {
        try {
            Object events = level.getServer().getCustomBossEvents();
            if (events == null) {
                return;
            }
            java.lang.reflect.Method getEvents = events.getClass().getMethod("getEvents");
            Object list = getEvents.invoke(events);
            if (!(list instanceof java.util.Collection<?> bars)) {
                return;
            }
            String name = entity.getDisplayName().getString();
            for (Object candidate : bars) {
                if (!(candidate instanceof net.minecraft.server.bossevents.CustomBossEvent)) {
                    continue;
                }
                net.minecraft.server.bossevents.CustomBossEvent bar = (net.minecraft.server.bossevents.CustomBossEvent) candidate;
                if (!bar.isVisible() || !bar.getName().getString().equals(name)) {
                    continue;
                }
                bar.setVisible(false);
            }
        } catch (Throwable ignored) {
            // Best effort, like the rest of the removal.
        }
    }

    /**
     * True for a projectile launched by a Transcendence Zombie.
     *
     * <p>Ownership is checked through {@code Projectile#getOwner()} rather than by projectile class,
     * so an unrelated {@code LargeFireball} from a ghast or a blaze is still annihilated while the
     * zombie's own attack survives.
     */
    private static boolean isOwnProjectile(Entity entity) {
        if (!(entity instanceof Projectile)) {
            return false;
        }
        Projectile projectile = (Projectile) entity;
        return projectile.getOwner() instanceof TranscenZombie;
    }

    /**
     * Rule 3, plus the mod's own mobs. Players are never removed from the storages: a player removed
     * this way corrupts {@code EntityTickList#active}, which {@code DistanceManager} shares, and the
     * next movement packet throws inside vanilla.
     */
    private static boolean isNeverRemovable(Entity entity) {
        if (entity instanceof Player) {
            return true;
        }
        return TranscenZombie.isProtected(entity);
    }

    /** Bolts for the first ten ticks, then the client-side removal and the vanilla death puff. */
    private static void advanceRemovalTimers(ServerLevel level) {
        long now = level.getGameTime();
        Iterator<Map.Entry<UUID, Long>> it = TranscenMod.ANNIHILATION_DOOMED_ON.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            UUID id = entry.getKey();
            long elapsed = now - entry.getValue();
            Vec3 pos = TranscenMod.ANNIHILATION_POSITIONS.getOrDefault(id, Vec3.ZERO);

            if (elapsed < STRIKE_TICKS) {
                strikeAt(level, pos);
                continue;
            }
            if (elapsed < CLIENT_REMOVE_DELAY_TICKS) {
                continue;
            }

            // Stage 3: the shell's two seconds are up. Drop the client copy, leave the death puff.
            TranscenMod.LOGGER.info(
                    "[transcenmod] DEAD t={} id={} elapsed={} sends=removal", now, id, elapsed);
            TranscenMod.NETWORK.send(
                    PacketDistributor.DIMENSION.with(level::dimension),
                    new ClientRemovePacket(id));
            spawnDeathPuff(level, pos);
            TranscenMod.ANNIHILATION_POSITIONS.remove(id);
            it.remove();
        }
    }

    /** Spawns {@link #BOLTS_PER_TICK} blood-red bolts on the given spot. */
    private static void strikeAt(ServerLevel level, Vec3 pos) {
        for (int i = 0; i < BOLTS_PER_TICK; i++) {
            TranscenLightningBolt bolt =
                    new TranscenLightningBolt(TranscenMod.TRANSCEN_LIGHTNING.get(), level);
            try {
                // Entity#position is widened to public by the AccessTransformer.
                Field posField = Entity.class.getDeclaredField("position");
                posField.setAccessible(true);
                posField.set(bolt, pos);
            } catch (Exception ignored) {
                bolt.moveTo(pos.x, pos.y, pos.z);
            }
            level.addFreshEntity(bolt);
        }
    }

    /** The vanilla white puff that mobs leave behind when they die. */
    private static void spawnDeathPuff(ServerLevel level, Vec3 pos) {
        level.sendParticles(ParticleTypes.POOF, pos.x, pos.y + 0.5D, pos.z,
                20, 0.5D, 0.5D, 0.5D, 0.1D);
    }
}
