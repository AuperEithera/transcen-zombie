package com.example.transcenmod.event;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.entity.TranscenZombie;
import com.example.transcenmod.network.ClientRemoveMessage;
import com.example.transcenmod.network.HudSyncMessage;
import com.example.transcenmod.network.NetworkHandler;
import com.example.transcenmod.util.EntityWiper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerRespawnEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// the encounter's core loop. per-target timeline: tick 0 server storage removal and the client told to
// render it red; ticks 1-10 two blood-red bolts a tick; then at tick 40 the client removal and the vanilla
// white death puff.
// dormant until a TranscenZombie exists in a level. one sweep per server tick - executeCoreLogic is
// reachable from every living tick and walks the whole entity list, and without the latch that is tens of
// thousands of traversals a tick and an OutOfMemoryError.
// players are never torn out of the entity storages. doing that corrupts EntityTickList#active, which the
// distance manager shares, and the next movement packet then throws inside vanilla and the world is
// unplayable. 1.12.2 has a different layout but the same hazard, so the same rule applies.
// registered as an instance on MinecraftForge.EVENT_BUS from TranscenMod#preInit, so the handlers below are
// non-static - in 1.12.2 that is the unambiguous registration form.
public class TranscenEvents {

    private static final int STRIKE_TICKS = 10;
    private static final int BOLTS_PER_TICK = 2;

    /** Latch so one server tick does at most one sweep. */
    private static boolean sweeping;
    private static long lastSweptTick = Long.MIN_VALUE;

    /** Last pushed HUD state; the packet only goes out on change. */
    private static Boolean lastHudActive;
    private static float lastHudProgress = -1.0F;

    // ---------------------------------------------------------------------------------------------
    // Living entities -- the frozen-everything behaviour
    // ---------------------------------------------------------------------------------------------

    /**
     * Cancels every living entity's tick, then runs this mod's work on it.
     *
     * <p>1.12.2's {@code LivingUpdateEvent} is equally cancellable and the cancel equally reaches
     * {@code EntityLivingBase#onLivingUpdate}, so the port is a straight one. The pair of handlers is what
     * makes the cancellation harmless to everything else: highest priority cancels, lowest priority cancels
     * the cancellation, so the net effect on any other listener is nothing.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onEntityTickHighest(LivingEvent.LivingUpdateEvent event) {
        event.setCanceled(true);

        Entity entity = event.getEntity();

        executeCoreLogic(entity);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onEntityTickLowest(LivingEvent.LivingUpdateEvent event) {
        event.setCanceled(false);
    }

    // ---------------------------------------------------------------------------------------------
    // World ticks
    // ---------------------------------------------------------------------------------------------

    /**
     * Never cancelled: {@code TickEvent.WorldTickEvent} carries no {@code @Cancelable} in this generation
     * either.
     *
     * <p>Revive at both ends of the level tick so an entity removed early in the tick is back before it
     * finishes, and apply the night lock at the end.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onLevelTickHighest(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        if (event.world instanceof WorldServer) {
            guardedRevive((WorldServer) event.world);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onLevelTickLowest(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.world instanceof WorldServer) {
            WorldServer level = (WorldServer) event.world;
            guardedRevive(level);
            forceNightAndStorm(level);
            TranscenMod.CONFIG.loadFor(level.provider.getDimension());
        }
    }

    /** 18000 is dusk in this generation too, so this is deep night. */
    private static final long LOCKED_TIME = 18000L;

    /**
     * Re-applied every tick rather than set once -- time advances by itself and rain stops when its timer
     * runs out.
     *
     * <p>1.12.2 gives no {@code setWeather(clearTime, rainTime, raining, thundering)} equivalent, so the
     * values are set one at a time through {@code WorldInfo}, which is public state and needs no access
     * transformer. Clearing the clean-weather timer first is the part that matters: a non-zero value there
     * is vanilla's "wait a random while before it rains", and leaving it set would hold the sky clear for
     * hundreds of ticks after the lock was applied. Setting {@code rainTime} and {@code thunderTime} past
     * that is what stops vanilla clearing the storm on its own schedule.
     *
     * <p>Gated on a zombie being in the level rather than on the HUD latch: the latch is sticky and would
     * leave the sky dark for good.
     */
    private static void forceNightAndStorm(WorldServer level) {
        boolean present = false;
        for (Entity entity : snapshot(level)) {
            if (entity instanceof TranscenZombie) {
                present = true;
                break;
            }
        }
        // A torn-out zombie is still on the watch list; count it present or the sky flickers off for a tick
        // per removal.
        if (!present && !TranscenMod.ZOMBIE_POSITIONS.isEmpty()) {
            present = true;
        }
        if (!present) {
            return;
        }
        if (level.getWorldTime() % 24000L != LOCKED_TIME) {
            // setWorldTime takes the day time directly and adjusts totalTime itself, which is the part a
            // bare field write would get wrong.
            level.setWorldTime(LOCKED_TIME);
        }
        net.minecraft.world.storage.WorldInfo info = level.getWorldInfo();
        if (!level.isRaining() || !level.isThundering() || info.getCleanWeatherTime() > 0) {
            info.setCleanWeatherTime(0);
            info.setRainTime(6000);
            info.setRaining(true);
            info.setThunderTime(6000);
            info.setThundering(true);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Player ticks
    // ---------------------------------------------------------------------------------------------

    /** {@code PlayerTickEvent} is not cancellable either. */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onPlayerTickHighest(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        executeCoreLogic(event.player);
        keepEggHolderAlive(event.player);
    }

    // ---------------------------------------------------------------------------------------------
    // Toggle and wipe
    // ---------------------------------------------------------------------------------------------

    /**
     * Wipes every {@link TranscenZombie} out of the level and ends the encounter. The egg both summons and
     * dismisses.
     *
     * <p>The watch list is emptied before the removals -- the other order makes the repair put one back per
     * removal. The HUD is switched off here rather than left to the client, so the encounter ends visibly
     * at once, music included.
     */
    public static void toggleDefence(EntityPlayerMP player, WorldServer level) {
        // The cooldown wraps the whole toggle, not just the wipe. Split apart, one click flipped the flag
        // and then had its wipe suppressed, and a second event flipped it back.
        long now = level.getTotalWorldTime();
        if (lastWipeTick != Long.MIN_VALUE && now - lastWipeTick < WIPE_COOLDOWN_TICKS) {
            return;
        }
        lastWipeTick = now;

        boolean on = !TranscenMod.defenceEnabled;

        // Protection off before the clear -- the clear inspects the world and a restore in the gap would
        // undo it.
        TranscenMod.defenceEnabled = on;
        if (!on) {
            clearAllZombies(level);
        }

        player.sendMessage(new TextComponentString(
                on ? "\u00a7aDefence enabled"
                        : "\u00a7cDefence disabled"));
    }

    public static void clearAllZombies(WorldServer level) {
        // No cooldown here; it belongs to the toggle, which guards the flag flip and this call together.
        // Switching the protection off first is the caller's job too -- this method inspects the world.
        TranscenMod.defenceEnabled = false;

        // a copy, because the wiper takes entities out of the very list this walks
        // forceRemove writes the flag through super: the entity overrides setDead to refuse, and that
        // refusal is deliberate, so the bypass stays explicit instead of the guard being relaxed
        List<UUID> wiped = new ArrayList<UUID>();
        for (Entity entity : new ArrayList<Entity>(level.loadedEntityList)) {
            if (entity instanceof TranscenZombie) {
                ((TranscenZombie) entity).forceRemove();
                wiped.add(entity.getUniqueID());
                EntityWiper.wipe(entity);
            }
        }
        // The wipe pulls the entity straight out of loadedEntityList and its chunk, which steps over the
        // entity tracker - so the destroy packet vanilla would have sent never goes out and every client
        // keeps drawing its own copy forever. One model left on screen after a wipe, on every client, is
        // this. The explicit removal has to be sent, in its own right.
        for (UUID id : wiped) {
            NetworkHandler.sendToDimension(level.provider.getDimension(),
                    new com.example.transcenmod.network.ClientRemoveMessage(id));
        }
        TranscenZombie.beginWipe();

        // Every container holding zombie state, not just the watch list.
        TranscenMod.ZOMBIE_POSITIONS.clear();
        TranscenMod.ZOMBIE_YAW.clear();
        TranscenMod.ZOMBIE_PITCH.clear();
        SPAWN_PENDING.clear();
        TranscenMod.ANNIHILATION_STRIKE_STARTED.clear();
        TranscenMod.ANNIHILATION_POSITIONS.clear();

        // Cached comparisons reset so the next tick re-evaluates; otherwise it decides nothing changed and
        // skips.
        lastSweptTick = Long.MIN_VALUE;
        lastHudActive = null;
        lastHudProgress = -1.0F;

        // Shells, bar and music all live on the client, so none of them stop without being told.
        TranscenMod.HUD_ACTIVE = false;
        NetworkHandler.sendToDimension(level.provider.getDimension(),
                new HudSyncMessage(false, 0.0F));
        NetworkHandler.sendToDimension(level.provider.getDimension(),
                new com.example.transcenmod.network.WipeEndedMessage());

        TranscenMod.CONFIG.setArmed(false);
    }

    private static final float EGG_HOLDER_HEALTH = 20.0F;

    /**
     * One left-click raises three events (air, block, attack-entity) and more than one can fire. A few
     * ticks of separation collapses them into the single action the player performed.
     */
    private static final long WIPE_COOLDOWN_TICKS = 5L;

    private static long lastWipeTick = Long.MIN_VALUE;

    /**
     * Set by a wipe, cleared by any zombie entering the level. While set the repair does nothing -- emptying
     * the watch list alone is not enough, entries come back a tick later.
     */
    private static volatile boolean encounterEnded;

    public static void endEncounter() {
        encounterEnded = true;
    }

    public static void restartEncounter() {
        encounterEnded = false;
    }

    // ---------------------------------------------------------------------------------------------
    // Revival
    // ---------------------------------------------------------------------------------------------

    /**
     * (0,0,0) means the placement never took, and the origin is usually outside the loaded region.
     * Elsewhere the chunk is the test: an entity in an unloaded chunk is accepted and then never reported.
     */
    private static boolean isUnusablePosition(WorldServer level, Vec3d pos) {
        if (pos == null) {
            return true;
        }
        if (pos.x == 0.0D && pos.y == 0.0D && pos.z == 0.0D) {
            return true;
        }
        try {
            return !level.isBlockLoaded(new BlockPos(
                    MathHelper.floor(pos.x), MathHelper.floor(pos.y), MathHelper.floor(pos.z)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Real logged-in player from the player list, offset two blocks so the zombie does not spawn inside
     * them.
     */
    private static Vec3d positionNearPlayer(WorldServer level, Vec3d fallback) {
        for (EntityPlayer player : level.playerEntities) {
            return new Vec3d(player.posX + 2.0D, player.posY, player.posZ + 2.0D);
        }
        // No players, so no loaded ground that is certainly with them. Fall back to the world spawn, which
        // is loaded whenever the level is.
        if (fallback != null && level.isBlockLoaded(level.getSpawnPoint())) {
            BlockPos spawn = level.getSpawnPoint();
            return new Vec3d(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D);
        }
        return null;
    }

    // health pinned and death refused while the egg is in the inventory. pinning alone is not enough: damage
    // is applied and death decided in one call, so a tick-end reset lands after the decision is already
    // made. the injected getHealth is the other half. this also feeds the coremod the UUID sets it reads.
    private static void keepEggHolderAlive(EntityPlayer player) {
        if (player.world.isRemote || player.isCreative() || player.isSpectator()) {
            forgetHealthState(player);
            return;
        }
        if (!isCarryingEgg(player)) {
            forgetHealthState(player);
            return;
        }
        TranscenMod.instance.setHealthProtected(player.getUniqueID(),
                !com.example.transcenmod.util.RetaliationState.isActive());
        if (player.getHealth() < EGG_HOLDER_HEALTH) {
            player.setHealth(EGG_HOLDER_HEALTH);
        }
        // 300 is vanilla's maximum air. 1.12.2 exposes no getMaxAir() -- the drown threshold and the bubble
        // count are both literals in the vanilla code -- so the constant is the only reading of "full".
        player.setAir(MAX_AIR);
        // Fire keeps damaging a player who cannot die; putting it out stops the screen burning forever.
        if (player.isBurning()) {
            player.extinguish();
        }
    }

    /** Vanilla's maximum air supply, the value {@code Entity#setAir} is filled to. */
    private static final int MAX_AIR = 300;

    private static void forgetHealthState(EntityPlayer player) {
        if (TranscenMod.instance != null) {
            TranscenMod.instance.setHealthProtected(player.getUniqueID(), false);
        }
    }

    /**
     * Whole inventory, not the selected slot: the meaning is ownership, not brandishing it, so the
     * protection does not lapse on a scroll.
     *
     * <p>Cached for one tick -- it is asked several times per tick and sweeping 36 stacks is too much for a
     * hot path. Keyed on the world's tick so it cannot answer stale across ticks.
     */
    public static boolean isCarryingEgg(EntityPlayer player) {
        long tick = player.world.getTotalWorldTime();
        if (player.getUniqueID().equals(eggCachePlayer) && tick == eggCacheTick) {
            return eggCacheValue;
        }
        boolean carried = false;
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) {
            if (player.inventory.getStackInSlot(slot).getItem() == TranscenMod.spawnEgg) {
                carried = true;
                break;
            }
        }
        eggCachePlayer = player.getUniqueID();
        eggCacheTick = tick;
        eggCacheValue = carried;
        return carried;
    }

    private static UUID eggCachePlayer;
    private static long eggCacheTick = Long.MIN_VALUE;
    private static boolean eggCacheValue;

    /**
     * The toggle asks holding, not carrying -- answering from the inventory made every left-click with any
     * item flip it. No cache: two hand slots are constant-time lookups.
     */
    public static boolean isHoldingEgg(EntityPlayer player) {
        return player.getHeldItemMainhand().getItem() == TranscenMod.spawnEgg
                || player.getHeldItemOffhand().getItem() == TranscenMod.spawnEgg;
    }

    /** Shared with the client guard so both sides pin the same value. */
    public static float eggHolderHealth() {
        return EGG_HOLDER_HEALTH;
    }

    // ---------------------------------------------------------------------------------------------
    // Damage, death
    // ---------------------------------------------------------------------------------------------

    /**
     * Cancel is the earliest possible point -- damage is written immediately after this event returns, and
     * restoring health in a later hook is too late because anything watching for health zero has already
     * run.
     *
     * <p>This one covers the red lightning: 1919810 damage then {@code setHealth(0)} with no gap between the
     * two calls. It still cannot stop that {@code setHealth(0)}, which is why the tick pin, the screen guard
     * and the injected {@code getHealth} all exist as well.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onLivingHurtLowest(LivingHurtEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer player = (EntityPlayer) event.getEntityLiving();
        if (player.world.isRemote || !isCarryingEgg(player)) {
            return;
        }
        TranscenMod.LOGGER.info(
                "[transcenmod] refused {} damage to egg holder (amount {})",
                event.getSource().getDamageType(), event.getAmount());
        event.setCanceled(true);
        player.setHealth(EGG_HOLDER_HEALTH);
    }

    /**
     * Restore rather than cancel -- being marked invulnerable is read by other mods and by the damage
     * pipeline itself.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onLivingDamageLowest(LivingDamageEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer player = (EntityPlayer) event.getEntityLiving();
        if (player.world.isRemote || !isCarryingEgg(player)) {
            return;
        }
        player.setHealth(EGG_HOLDER_HEALTH);
    }

    /** For the paths that never go through damage: the void and {@code /kill}. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onLivingDeathLowest(LivingDeathEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer player = (EntityPlayer) event.getEntityLiving();
        if (player.world.isRemote || !isCarryingEgg(player)) {
            return;
        }
        player.setHealth(EGG_HOLDER_HEALTH);
        event.setCanceled(true);
    }

    // ---------------------------------------------------------------------------------------------
    // Banned classes may not re-enter the world
    // ---------------------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onEntityJoinHighest(EntityJoinWorldEvent event) {
        Entity joining = event.getEntity();

        // Restart here as well as at low priority, because another mod's handler sits between the two. A
        // join it cancels still reaches this point; waiting would miss the summon and leave the encounter
        // off.
        if (joining instanceof TranscenZombie) {
            restartEncounter();
        }

        // Players are never blocked from joining whatever else happens.
        if (joining instanceof EntityPlayer) {
            return;
        }
        if (TranscenMod.BANNED_CLASSES.contains(joining.getClass())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onEntityJoinLowest(EntityJoinWorldEvent event) {
        if (!(event.getEntity() instanceof TranscenZombie)) {
            return;
        }
        TranscenZombie zombie = (TranscenZombie) event.getEntity();
        event.setCanceled(false);

        // Record the watch-list entry on join, whichever route put it there -- the egg uses vanilla's path
        // and would never be recorded. A retired zombie is left out, otherwise a wipe is entered straight
        // back and the repair resurrects it.
        restartEncounter();
        if (!event.getWorld().isRemote && !zombie.isRetired()) {
            TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUniqueID(),
                    new Vec3d(zombie.posX, zombie.posY, zombie.posZ));
            TranscenMod.CONFIG.setArmed(true);
        }
    }

    /** Clears both UUID sets when a player respawns, so a new life starts unprotected. */
    @SubscribeEvent
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (event.player != null) {
            TranscenMod.clearHealthState(event.player.getUniqueID());
            com.example.transcenmod.util.RetaliationState.clear();
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerLoggedOutEvent event) {
        if (event.player != null) {
            TranscenMod.clearHealthState(event.player.getUniqueID());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Core loop
    // ---------------------------------------------------------------------------------------------

    public static void executeCoreLogic(Entity trigger) {
        if (!(trigger.world instanceof WorldServer)) {
            return;
        }
        WorldServer level = (WorldServer) trigger.world;
        if (level.isRemote) {
            return;
        }
        // Protection off means no sweep at all -- the switch was cutting one path into the repair and
        // missing the other. Without this guard "off" could still revive a zombie and still leave one on
        // screen.
        if (!TranscenMod.defenceEnabled) {
            return;
        }
        // One sweep per tick however many entities fire this.
        long tick = level.getTotalWorldTime();
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

    private static void sweep(WorldServer level) {
        // Fresh spawn budget each tick so a burst of removals cannot cascade.
        beginTickSpawnBudget();

        List<Entity> entities = snapshot(level);

        // The HUD is armed, not inferred: once the encounter starts it stays on for the rest of the
        // session. Tying it to a zombie being present made the bar strobe off and on every time another mod
        // removed the zombie. The bar is the encounter's frame, not a health readout of one entity.
        boolean zombieOwned = TranscenMod.HUD_ACTIVE
                || !TranscenMod.ZOMBIE_POSITIONS.isEmpty()
                || firstZombie(entities) != null;

        // Server driven, so it is right even when the client cannot see the zombie.
        pushHud(level, zombieOwned);

        // Revival runs before the zombie-present test on purpose. Gating it behind that test is exactly the
        // case that matters: last zombie gone means firstZombie returns null and the rebuild is skipped.
        restoreMissingZombies(level, entities);

        if (!zombieOwned) {
            // Inert with no zombie; outstanding timelines still get to finish.
            advanceRemovalTimers(level);
            return;
        }

        annihilateForeignEntities(level, entities);
        advanceRemovalTimers(level);
    }

    /**
     * A fresh list every sweep: the sweep mutates the world's entity list while iterating it, and iterating
     * the live list directly is a {@code ConcurrentModificationException} waiting to happen.
     */
    private static List<Entity> snapshot(WorldServer level) {
        List<Entity> out = new ArrayList<Entity>();
        for (Entity entity : level.loadedEntityList) {
            if (entity != null && !entity.isDead) {
                out.add(entity);
            }
        }
        return out;
    }

    private static TranscenZombie firstZombie(List<Entity> entities) {
        for (Entity entity : entities) {
            if (entity instanceof TranscenZombie) {
                return (TranscenZombie) entity;
            }
        }
        return null;
    }

    private static void pushHud(WorldServer level, boolean zombieOwned) {
        boolean active = zombieOwned;
        float progress = 1.0F;
        boolean changed = lastHudActive == null || lastHudActive != active
                || Math.abs(lastHudProgress - progress) >= 0.001F;
        // The coremod suppression is re-asserted on every push rather than only on change: the bar is
        // consulted by the GUI on a frame the server does not drive.
        TranscenMod.setEncounterPresent(active);
        if (!changed) {
            return;
        }
        lastHudActive = active;
        lastHudProgress = progress;
        NetworkHandler.sendToDimension(level.provider.getDimension(),
                new HudSyncMessage(active, progress));
    }

    /** One-shot: a repeating failure would flood a log that is already fragile. */
    private static boolean reviveFailureLogged;

    /**
     * UUIDs with a respawn queued but not yet run. Keeps the four revive points from each queueing one --
     * they all run on the server thread, but the level's entity list is not updated until the tick ends. The
     * mark goes in before the spawn is queued and comes out in a finally, so a failure cannot strand it.
     */
    private static final Set<UUID> SPAWN_PENDING = new HashSet<UUID>();

    /**
     * Must never throw out of an event handler. Forge's {@code EventBus} reports a throw from a listener and
     * a log4j conflict turns that report into a fatal {@code LinkageError}, so the failure is printed
     * directly instead.
     */
    private static void guardedRevive(WorldServer level) {
        try {
            restoreMissingZombies(level, snapshot(level));
        } catch (Throwable failure) {
            if (!reviveFailureLogged) {
                reviveFailureLogged = true;
                failure.printStackTrace();
            }
        }
    }

    /**
     * Keyed by UUID, and the respawn reuses it -- both halves are needed. A head count cannot tell which
     * zombie is missing, and a fresh identity never satisfies the entry; an earlier revision did exactly
     * that and every sweep added another copy.
     */
    private static final int MAX_SPAWNS_PER_TICK = 4;

    /** Last-resort circuit breaker on how many the mod keeps alive. */
    private static final int MAX_ZOMBIES = 1;

    private static int spawnsThisTick;

    private static void beginTickSpawnBudget() {
        spawnsThisTick = 0;
    }

    private static void restoreMissingZombies(WorldServer level, List<Entity> entities) {
        // Protection off means nothing is put back -- this gate is what makes a wipe stick. encounterEnded
        // is checked as well: one is switched by the player and the other by a wipe, and both have to hold
        // the repair.
        if (!TranscenMod.defenceEnabled) {
            return;
        }
        if (encounterEnded) {
            return;
        }
        Set<UUID> present = new HashSet<UUID>();
        for (Entity entity : entities) {
            if (entity instanceof TranscenZombie) {
                TranscenZombie zombie = (TranscenZombie) entity;
                present.add(zombie.getUniqueID());
                // Keep the recorded spot and facing fresh so a respawn lands and looks where the zombie last
                // was.
                TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUniqueID(),
                        new Vec3d(zombie.posX, zombie.posY, zombie.posZ));
                TranscenMod.ZOMBIE_YAW.put(zombie.getUniqueID(), zombie.rotationYaw);
                TranscenMod.ZOMBIE_PITCH.put(zombie.getUniqueID(), zombie.rotationPitch);
            }
        }

        // Counted from the snapshot, not from a by-class query: that query reported zero even right after a
        // successful add, so the limit never held and every tick added another.
        int aliveCount = 0;
        for (Entity entity : entities) {
            if (entity instanceof TranscenZombie) {
                aliveCount++;
            }
        }
        if (aliveCount >= MAX_ZOMBIES) {
            return;
        }

        for (Map.Entry<UUID, Vec3d> entry : TranscenMod.ZOMBIE_POSITIONS.entrySet()) {
            UUID id = entry.getKey();
            if (SPAWN_PENDING.contains(id)) {
                continue;
            }
            if (present.contains(id) || level.getEntityFromUuid(id) != null) {
                continue;
            }
            if (spawnsThisTick >= MAX_SPAWNS_PER_TICK) {
                // Budget spent; the entry stays on the list for the next tick.
                return;
            }
            Vec3d pos = entry.getValue();
            float yaw = TranscenMod.ZOMBIE_YAW.containsKey(id)
                    ? TranscenMod.ZOMBIE_YAW.get(id) : 0.0F;
            float pitch = TranscenMod.ZOMBIE_PITCH.containsKey(id)
                    ? TranscenMod.ZOMBIE_PITCH.get(id) : 0.0F;

            // Never revive into the origin or unloaded ground: a zombie added to the manager there never
            // becomes findable, the next sweep sees it missing and makes another, thousands per tick, and
            // the client stutters. An unusable position falls back to beside a player, which is loaded by
            // definition.
            if (isUnusablePosition(level, pos)) {
                pos = positionNearPlayer(level, pos);
                if (pos == null) {
                    // Nowhere to put it: skip the entry and let the next tick try, rather than pile up an
                    // unfindable entity.
                    continue;
                }
                TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
            }

            final Vec3d target = pos;
            final UUID targetId = id;
            final float targetYaw = yaw;
            final float targetPitch = pitch;
            SPAWN_PENDING.add(id);
            spawnsThisTick++;
            level.getMinecraftServer().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        spawnZombie(level, targetId, target, targetYaw, targetPitch);
                    } finally {
                        SPAWN_PENDING.remove(targetId);
                    }
                }
            });
        }
    }

    public static void spawnZombie(WorldServer level, Vec3d pos) {
        spawnZombie(level, UUID.randomUUID(), pos, 0.0F, 0.0F);
    }

    /**
     * On the watch list before it is added -- a removal in the same tick still leaves an entry to repair.
     *
     * <p>Facing is restored too: a revived zombie that snaps to due south is visibly not the same entity.
     */
    private static void spawnZombie(WorldServer level, UUID id, Vec3d pos, float yaw, float pitch) {
        TranscenZombie zombie = new TranscenZombie(level);
        zombie.setUniqueId(id);

        // setLocationAndAngles with the relocation guard lifted, not a reflective write to the position
        // field. The field write looked obvious but silently did not work on the later generations, and a
        // declared method cannot be defeated by a name that resolves differently.
        final TranscenZombie target = zombie;
        final Vec3d where = pos;
        final float finalYaw = yaw;
        final float finalPitch = pitch;
        zombie.withRelocationAllowed(new Runnable() {
            @Override
            public void run() {
                target.setLocationAndAngles(where.x, where.y, where.z, finalYaw, finalPitch);
            }
        });

        // Head and body rotation are separate fields from yaw/pitch; without them a revived zombie snaps
        // round on its first frame.
        zombie.rotationYawHead = yaw;
        zombie.renderYawOffset = yaw;

        TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
        TranscenMod.ZOMBIE_YAW.put(id, yaw);
        TranscenMod.ZOMBIE_PITCH.put(id, pitch);

        boolean added = level.spawnEntity(zombie);
        if (added) {
            TranscenMod.LOGGER.info("[transcenmod] repair: re-added {}", id);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Annihilation
    // ---------------------------------------------------------------------------------------------

    // The server copy and the client copy go in the same tick. entities is already a copy of the world list,
    // so removing from the world while walking it is safe.
    private static void annihilateForeignEntities(WorldServer level, List<Entity> entities) {
        long now = level.getTotalWorldTime();
        for (Entity entity : entities) {
            if (isNeverRemovable(entity)) {
                continue;
            }
            // The zombie's own fireballs would be annihilated the instant they spawn; everything else,
            // including other mobs' projectiles, is still cleared.
            if (isOwnProjectile(entity)) {
                continue;
            }
            UUID id = entity.getUniqueID();
            if (TranscenMod.ANNIHILATION_STRIKE_STARTED.containsKey(id)) {
                continue;
            }
            // Position is captured before removal; the entity is unreachable afterwards and the strikes
            // still need a spot.
            TranscenMod.ANNIHILATION_POSITIONS.put(id, new Vec3d(entity.posX, entity.posY, entity.posZ));
            TranscenMod.ANNIHILATION_STRIKE_STARTED.put(id, now);

            // The ban list is keyed by class, globally and for the rest of the session: a banned class can
            // never join any world again. A blanket ban is what stopped mob spawning in every dimension with
            // nothing on screen to explain it, which is why the sweep handles what is present instead.
            if (!(entity instanceof EntityPlayer)) {
                TranscenMod.BANNED_CLASSES.add(entity.getClass());
            }
            entity.setEntityInvulnerable(true);
            entity.motionX = 0.0D;
            entity.motionY = 0.0D;
            entity.motionZ = 0.0D;
            if (entity instanceof EntityLiving) {
                ((EntityLiving) entity).setNoAI(true);
            }

            // Hand the client its instructions before the server copy is unlinked, or the entity tracker
            // never gets the chance to announce the removal and the client copy is stranded on screen.
            // A boss bar is not part of the entity; it is registered with the server and pushed on its own
            // packet stream, so removing the entity does not remove the bar, and it keeps drawing over a mob
            // that is gone.
            clearOwnedBossBars(entity);
            clearMatchingRegisteredBossBars(level, entity);

            // A foreign entity's own setDead is not overridden, so the plain call is correct here; the
            // forceRemove bypass is only for this mod's own zombie. The flag alone would leave the entity in
            // the world list and in its chunk, which is what the wiper is for.
            entity.setDead();
            EntityWiper.wipe(entity);
            NetworkHandler.sendToDimension(level.provider.getDimension(), new ClientRemoveMessage(id));
        }
    }

    // structural, not a known field name: holders spell theirs differently and modded bosses invent their
    // own. 1.12.2's BossInfoServer has no removeAllPlayers(), so the subscription is dropped one removePlayer
    // at a time, bounded to the entity and its superclasses or the walk could reach the level.
    private static void clearOwnedBossBars(Entity entity) {
        Set<Object> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Object, Boolean>());
        for (Class<?> type = entity.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                // Any BossInfo, not only the server implementation: a mod can hold a plain one and drive the
                // packets itself, and testing the narrow subtype missed those.
                if (!BossInfo.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(entity);
                    if (value instanceof BossInfo && seen.add(value)) {
                        BossInfo bar = (BossInfo) value;
                        boolean deactivated = false;
                        if (bar instanceof BossInfoServer) {
                            BossInfoServer serverBar = (BossInfoServer) bar;
                            serverBar.setVisible(false);
                            // Copied first: removePlayer mutates the very collection being walked.
                            for (EntityPlayerMP watcher
                                    : new ArrayList<EntityPlayerMP>(serverBar.getPlayers())) {
                                serverBar.removePlayer(watcher);
                            }
                            deactivated = true;
                        }
                        TranscenMod.LOGGER.info(
                                "[transcenmod] boss bar {} ({}) owned by {} -- {}",
                                field.getName(), bar.getClass().getSimpleName(),
                                entity.getUniqueID(),
                                deactivated ? "hidden" : "not a server bar, left alone");
                    }
                } catch (Throwable ignored) {
                    // An unreadable field is not a reason to abandon the removal.
                }
            }
        }
    }

    // second sweep, for bars whose ownership is not a field: a datapack or a command raised these, they are
    // registered with the server and hold no reference back to a mob, so the display name is the only link.
    // reflection, deliberately, and the one place in this class that reaches for it. 1.12.2 keeps the boss
    // registry in a private field on the server with no public accessor, so the field is found by type rather
    // than by name - every non-static Map field is examined and the entries matched by name. exact display
    // name only, so the worst a coincidence does is hide a bar showing the same text.
    private static void clearMatchingRegisteredBossBars(WorldServer level, Entity entity) {
        try {
            MinecraftServer server = level.getMinecraftServer();
            if (server == null) {
                return;
            }
            String name = entity.getDisplayName().getFormattedText();
            for (Class<?> type = server.getClass(); type != null && type != Object.class;
                    type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (!java.util.Map.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(server);
                    if (!(value instanceof java.util.Map)) {
                        continue;
                    }
                    for (Object candidate
                            : new ArrayList<Object>(((java.util.Map<?, ?>) value).values())) {
                        if (!(candidate instanceof BossInfoServer)) {
                            continue;
                        }
                        BossInfoServer bar = (BossInfoServer) candidate;
                        if (!bar.getName().getFormattedText().equals(name)) {
                            continue;
                        }
                        bar.setVisible(false);
                        TranscenMod.LOGGER.info(
                                "[transcenmod] hid registered boss bar '{}' for {}",
                                name, entity.getUniqueID());
                    }
                }
            }
        } catch (Throwable ignored) {
            // Best effort, like the rest of the removal.
        }
    }

    /**
     * By shooter, not by class, so a ghast's or a blaze's fireball is still annihilated and this zombie's
     * own survives.
     */
    private static boolean isOwnProjectile(Entity entity) {
        Entity shooter = null;
        if (entity instanceof EntityFireball) {
            shooter = ((EntityFireball) entity).shootingEntity;
        } else if (entity instanceof EntityThrowable) {
            shooter = ((EntityThrowable) entity).getThrower();
        }
        return shooter instanceof TranscenZombie;
    }

    /**
     * Players are never removed from the storages -- the tracker shares that list, and the next movement
     * packet then throws inside vanilla.
     */
    private static boolean isNeverRemovable(Entity entity) {
        if (entity instanceof EntityPlayer) {
            return true;
        }
        return TranscenZombie.isProtected(entity);
    }

    // The client copy is already gone by the time this runs, so the only thing left to time is the strikes.
    private static void advanceRemovalTimers(WorldServer level) {
        long now = level.getTotalWorldTime();
        Iterator<Map.Entry<UUID, Long>> it = TranscenMod.ANNIHILATION_STRIKE_STARTED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            UUID id = entry.getKey();
            if (now - entry.getValue().longValue() < STRIKE_TICKS) {
                Vec3d pos = TranscenMod.ANNIHILATION_POSITIONS.containsKey(id)
                        ? TranscenMod.ANNIHILATION_POSITIONS.get(id) : Vec3d.ZERO;
                strikeAt(level, pos);
                continue;
            }
            TranscenMod.ANNIHILATION_POSITIONS.remove(id);
            it.remove();
        }
    }

    // plain vanilla lightning, nothing added. effectOnly=false so it behaves exactly like a natural strike,
    // damage and fire included
    private static void strikeAt(WorldServer level, Vec3d pos) {
        for (int i = 0; i < BOLTS_PER_TICK; i++) {
            // addWeatherEffect, never spawnEntity. EntityLightningBolt is an EntityWeatherEffect: it lives in
        // World.weatherEffects, which updateWeather ticks, and WorldServer.addWeatherEffect is where
        // SPacketSpawnGlobalEntity is sent. spawnEntity put it in loadedEntityList instead - never
        // ticked, never sent, so the client had nothing to draw.
        level.addWeatherEffect(new EntityLightningBolt(level, pos.x, pos.y, pos.z, false));
        }
    }
}
