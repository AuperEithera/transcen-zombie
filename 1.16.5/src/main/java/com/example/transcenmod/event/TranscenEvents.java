package com.example.transcenmod.event;
import net.minecraft.util.math.AxisAlignedBB;

import net.minecraft.world.server.ServerBossInfo;
import net.minecraft.util.text.StringTextComponent;

import net.minecraft.entity.projectile.FireballEntity;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.entity.TranscenLightningBolt;
import com.example.transcenmod.entity.TranscenZombie;
import com.example.transcenmod.network.ClientRemovePacket;
import com.example.transcenmod.network.HudSyncPacket;
import com.example.transcenmod.network.MarkDoomedPacket;
import net.minecraft.util.math.SectionPos;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.network.PacketDistributor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// per-target timeline: tick 0 server storage removal, client told to render it red
// ticks 1-10 two blood-red bolts a tick then tick 40 client removal + the vanilla white death puff
// dormant until a TranscenZombie exists in the level
// one sweep per server tick,executeCoreLogic is reachable from every living tick and walks the whole entity list
// without the latch that is tens of thousands of traversals a tick and an OutOfMemoryError
// players are never torn out of the entity storages - corrupts EntityTickList#active which DistanceManager shares
// the next movement packet then throws inside vanilla and the world is unplayable
public class TranscenEvents {

    private static final int STRIKE_TICKS = 10;
    private static final int BOLTS_PER_TICK = 2;
    private static final int CLIENT_REMOVE_DELAY_TICKS = 40;

    // latch so one server tick does at most one sweep
    private static boolean sweeping;
    private static long lastSweptTick = Long.MIN_VALUE;

    // last pushed hud state, the packet only goes out on change
    private static Boolean lastHudActive;
    private static float lastHudProgress = -1.0F;

    // living entities, the frozen-everything behaviour

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityTickHighest(LivingEvent.LivingUpdateEvent event) {
        // legal: LivingUpdateEvent carries @Cancelable and ForgeHooks.onLivingTick returns the flag to LivingEntity#tick
        event.setCanceled(true);

        Entity entity = event.getEntity();

        // doomed shell: client leftover, no tick at all so the red model stays frozen for the 2s window
        if (entity.getEntityWorld().isRemote
                && com.example.transcenmod.client.DoomedShell.isDoomed(entity.getUniqueID())) {
            return;
        }

        executeCoreLogic(entity);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityTickLowest(LivingEvent.LivingUpdateEvent event) {
        event.setCanceled(false);
    }

    // world and player ticks,not cancelable in forge 1.20.1

    // never cancelled - WorldTickEvent carries no @Cancelable and ForgeEventFactory#onPreLevelTick returns void
    // revive at both ends of the level tick so an entity removed early in the tick is back before it finishes
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onLevelTickHighest(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        if (event.world instanceof ServerWorld) { ServerWorld level = (ServerWorld) event.world;
            guardedRevive(level);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLevelTickLowest(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.world instanceof ServerWorld) { ServerWorld level = (ServerWorld) event.world;
            guardedRevive(level);
            forceNightAndStorm(level);
        }
    }

    // 18000 is dusk in 1.19.2 so this is deep night
    private static final long LOCKED_TIME = 18000L;

    // re-applied every tick not set once - time advances by itself and rain stops when the timer runs out
    // setWeather(0, 6000, true, true) - the 0 clear timer turns it at once rather than after a random delay
    // gated on a zombie in the level, not the hud latch, the latch is sticky and would leave the sky dark for good
    private static void forceNightAndStorm(ServerWorld level) {
        boolean present = false;
        for (Entity entity : snapshot(level)) {
            if (entity instanceof TranscenZombie) {
                present = true;
                break;
            }
        }
        // torn-out zombie is still on the watch list,count it present or the sky flickers off for a tick per removal
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
            level.setWeather(0, 6000, true, true);
        }
    }

    // PlayerTickEvent is not cancelable either
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onPlayerTickHighest(TickEvent.PlayerTickEvent event) {
        executeCoreLogic(event.player);
        keepEggHolderAlive(event.player);
    }


    // wipes every TranscenZombie out of the level and ends the encounter, the egg both summons and dismisses
    // watch list is emptied before the removals - the other order makes the repair put one back per removal
    // hud is switched off here, not left to the client, so the encounter ends visibly at once, music included
    // left-click with the egg toggles the protection, a chat message on each flip so the state is never a guess
    public static void toggleDefence(ServerPlayerEntity player, ServerWorld level) {
        // cooldown wraps the whole toggle not just the wipe
        // split apart,one click flipped the flag then had its wipe suppressed and a second event flipped it back
        long now = level.getGameTime();
        if (lastWipeTick != Long.MIN_VALUE && now - lastWipeTick < WIPE_COOLDOWN_TICKS) {
            return;
        }
        lastWipeTick = now;

        boolean on = !TranscenMod.defenceEnabled;

        // protection off before the clear - the clear inspects the world and a restore in the gap would undo it
        TranscenMod.defenceEnabled = on;
        if (!on) {
            clearAllZombies(level);
        }

        player.sendMessage(new StringTextComponent(
                on ? "\u00a7a\u9632\u5fa1\u5df2\u5f00\u542f"
                   : "\u00a7c\u9632\u5fa1\u5df2\u5173\u95ed"), java.util.UUID.randomUUID());
    }

    public static void clearAllZombies(ServerWorld level) {
        // no cooldown here,it belongs to the toggle which guards the flag flip and this call together
        // switching the protection off first is the caller's job too - this method inspects the world
        TranscenMod.defenceEnabled = false;

        // deep removal at the storage level, every route through the entity gets refused by the entity
        // so the uuid index and the tick list are walked by hand and the entity is never consulted
        // repeated passes, a removal mid-iteration can leave behind an entity this pass already walked past
        int deep = 0;
        for (int pass = 0; pass < 8; pass++) {
            List<Entity> batch = new ArrayList<>();
            for (Entity entity : level.getEntitiesIteratable()) {
                if (entity instanceof TranscenZombie) {
                    batch.add(entity);
                }
            }
            if (batch.isEmpty()) {
                break;
            }
            for (Entity entity : batch) {
                try {
                    entity.remove(false);
                    deep++;
                } catch (Throwable failure) {
                    TranscenMod.LOGGER.warn("[transcenmod] deep removal failed", failure);
                }
            }
        }
        TranscenZombie.beginWipe();

        // every container holding zombie state, not just the watch list
        TranscenMod.ZOMBIE_POSITIONS.clear();
        TranscenMod.ZOMBIE_YAW.clear();
        TranscenMod.ZOMBIE_PITCH.clear();
        SPAWN_PENDING.clear();
        TranscenMod.ANNIHILATION_DOOMED_ON.clear();
        TranscenMod.ANNIHILATION_POSITIONS.clear();

        // cached comparisons reset so the next tick re-evaluates,otherwise it decides nothing changed and skips
        lastSweptTick = Long.MIN_VALUE;
        lastHudActive = null;
        lastHudProgress = -1.0F;

        // shells, bar and music all live on the client so none of them stop without being told
        TranscenMod.HUD_ACTIVE = false;
        TranscenMod.NETWORK.send(
                PacketDistributor.DIMENSION.with(level::getDimensionKey),
                new HudSyncPacket(false, 0.0F));
        TranscenMod.NETWORK.send(
                PacketDistributor.DIMENSION.with(level::getDimensionKey),
                new com.example.transcenmod.network.WipeEndedPacket());

        TranscenMod.LOGGER.info("[transcenmod] wipe: {} zombie(s) removed", deep);
    }

    private static final float EGG_HOLDER_HEALTH = 20.0F;

    // one left-click raises three events (air, block, attack-entity) and more than one can fire
    // a few ticks of separation collapses them into the single action the player performed
    private static final long WIPE_COOLDOWN_TICKS = 5L;

    private static long lastWipeTick = Long.MIN_VALUE;

    // set by a wipe, cleared by any zombie entering the level
    // while set the repair does nothing - emptying the watch list alone is not enough, entries come back a tick later
    // an earlier version could never clear it, so every zombie created afterwards dismissed itself and the level filled up
    private static volatile boolean encounterEnded;

    public static void endEncounter() {
        encounterEnded = true;
    }

    public static void restartEncounter() {
        encounterEnded = false;
    }

    // (0,0,0) means the placement never took and the origin is usually outside the loaded region
    // elsewhere the chunk is the test,an entity in an unloaded chunk is accepted then never reported
    private static boolean isUnusablePosition(ServerWorld level, Vector3d pos) {
        if (pos == null) {
            return true;
        }
        if (pos.x == 0.0D && pos.y == 0.0D && pos.z == 0.0D) {
            return true;
        }
        try {
            return !level.isBlockLoaded(new net.minecraft.util.math.BlockPos(
                    net.minecraft.util.math.MathHelper.floor(pos.x),
                    net.minecraft.util.math.MathHelper.floor(pos.y),
                    net.minecraft.util.math.MathHelper.floor(pos.z)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    // real logged-in player from the player list,offset 2 blocks so the zombie does not spawn inside them
    private static Vector3d positionNearPlayer(ServerWorld level, Vector3d fallback) {
        for (net.minecraft.entity.player.ServerPlayerEntity player : level.getPlayers()) {
            return new Vector3d(player.getPosX() + 2.0D, player.getPosY(), player.getPosZ() + 2.0D);
        }
        // no players, so no loaded ground that is certainly with them, fall back to the world spawn which is loaded whenever the level is
        if (fallback != null && level.isBlockLoaded(level.getSpawnPoint())) {
            net.minecraft.util.math.BlockPos spawn = level.getSpawnPoint();
            return new Vector3d(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D);
        }
        return null;
    }

    // health pinned and death refused while the egg is in the inventory
    // pinning alone is not enough - damage is applied and death decided in one call, a tick-end reset lands after
    // the damage hook is lowest priority so it runs before the damage is written and the killing blow never lands
    // the death hook cancels outright for paths that bypass damage, the void and /kill
    // the per-tick pin covers a player who joins already hurt, the client never gets a death packet so no screen
    private static void keepEggHolderAlive(PlayerEntity player) {
        if (player.getEntityWorld().isRemote || player.isCreative() || player.isSpectator()) {
            return;
        }
        if (!isCarryingEgg(player)) {
            return;
        }
        if (player.getHealth() < EGG_HOLDER_HEALTH) {
            player.setHealth(EGG_HOLDER_HEALTH);
        }
        player.setAir(player.getMaxAir());
        // fire keeps damaging a player who cannot die,putting it out stops the screen burning forever
        if (player.isBurning()) {
            player.extinguish();
        }
    }

    // whole inventory, not the selected slot - the meaning is ownership, not brandishing it, so the protection does not lapse on a scroll
    // cached for one tick,it is asked several times per tick and sweeping 36 stacks is too much for a hot path
    // keyed on the server tick so it cannot answer stale across ticks
    public static boolean isCarryingEgg(PlayerEntity player) {
        long tick = player.getEntityWorld().getGameTime();
        if (player.getUniqueID().equals(eggCachePlayer) && tick == eggCacheTick) {
            return eggCacheValue;
        }
        boolean carried = false;
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) {
            if (player.inventory.getStackInSlot(slot).getItem() == TranscenMod.TRANSCEN_SPAWN_EGG.get()) {
                carried = true;
                break;
            }
        }
        eggCachePlayer = player.getUniqueID();
        eggCacheTick = tick;
        eggCacheValue = carried;
        return carried;
    }

    private static java.util.UUID eggCachePlayer;
    private static long eggCacheTick = Long.MIN_VALUE;
    private static boolean eggCacheValue;

    // the toggle asks holding,not carrying - answering from the inventory made every left-click with any item flip it
    // no cache, two hand slots are constant-time lookups
    public static boolean isHoldingEgg(PlayerEntity player) {
        return player.getHeldItemMainhand().getItem() == TranscenMod.TRANSCEN_SPAWN_EGG.get()
                || player.getHeldItemOffhand().getItem() == TranscenMod.TRANSCEN_SPAWN_EGG.get();
    }

    // shared with the client guard so both sides pin the same value
    public static float eggHolderHealth() {
        return EGG_HOLDER_HEALTH;
    }

    // cancel is the earliest possible point - damage is written immediately after this event returns
    // restoring health in a later hook is too late, anything watching for health zero has already run
    // this one covers the red lightning,1919810 damage then setHealth(0) with no gap between the two calls
    // it still cannot stop that setHealth(0), which is why the tick pin and the screen guard exist as well
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingHurtLowest(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof PlayerEntity)) {
            return;
        }
        PlayerEntity player = (PlayerEntity) event.getEntity();
        if (player.getEntityWorld().isRemote || !isCarryingEgg(player)) {
            return;
        }
        TranscenMod.LOGGER.info(
                "[transcenmod] refused {} damage to egg holder (amount {})",
                event.getSource().getDamageType(), event.getAmount());
        event.setCanceled(true);
        player.setHealth(EGG_HOLDER_HEALTH);
    }

    // restore rather than cancel,being marked invulnerable is read by other mods and by the pipeline itself
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDamageLowest(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof PlayerEntity)) {
            return;
        }
        PlayerEntity player = (PlayerEntity) event.getEntity();
        if (player.getEntityWorld().isRemote || !isCarryingEgg(player)) {
            return;
        }
        player.setHealth(EGG_HOLDER_HEALTH);
    }

    // for the paths that never go through damage, void and /kill
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDeathLowest(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof PlayerEntity)) {
            return;
        }
        PlayerEntity player = (PlayerEntity) event.getEntity();
        if (player.getEntityWorld().isRemote || !isCarryingEgg(player)) {
            return;
        }
        player.setHealth(EGG_HOLDER_HEALTH);
        event.setCanceled(true);
    }

    // banned classes may not re-enter the world

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityJoinHighest(EntityJoinWorldEvent event) {
        Entity joining = event.getEntity();

        // restarts here as well as at low priority,another mod's handler sits between the two
        // a join it cancels still reaches this point, waiting would miss the summon and leave the encounter off
        if (joining instanceof TranscenZombie) {
            restartEncounter();
        }

        // players are never blocked from joining whatever else happens
        if (joining instanceof PlayerEntity) {
            return;
        }
        if (TranscenMod.BANNED_CLASSES.contains(joining.getClass())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityJoinLowest(EntityJoinWorldEvent event) {
        if (!(event.getEntity() instanceof TranscenZombie)) {
            return;
        }
        TranscenZombie zombie = (TranscenZombie) event.getEntity();
        event.setCanceled(false);

        // record the watch-list entry on join,whichever route put it there - the egg uses vanilla's path and would never be recorded
        // a retired zombie is left out, otherwise a wipe is entered straight back and the repair resurrects it
        // zombie entering restarts the encounter whether it came from the egg or a command
        restartEncounter();
        if (!event.getWorld().isRemote() && !zombie.isRetired()) {
            TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUniqueID(),
                    new Vector3d(zombie.getPosX(), zombie.getPosY(), zombie.getPosZ()));
        }
    }

    // core loop

    public static void executeCoreLogic(Entity trigger) {
        if (!(trigger.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        ServerWorld level = (ServerWorld) trigger.getEntityWorld();
        if (level.isRemote) {
            return;
        }
        // protection off means no sweep at all - the switch was cutting one path into the repair and missing the other
        // without this guard "off" could still revive a zombie and still leave one on screen
        if (!TranscenMod.defenceEnabled) {
            return;
        }
        // one sweep per tick however many entities fire this
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

    private static void sweep(ServerWorld level) {
        // fresh spawn budget each tick so a burst of removals cannot cascade
        beginTickSpawnBudget();

        List<Entity> snapshot = snapshot(level);

        // the hud is armed not inferred, once the encounter starts it stays on for the rest of the session
        // tying it to a zombie being present made the bar strobe off and on every time another mod removed the zombie
        // the bar is the encounter's frame,not a health readout of one entity
        boolean zombieOwned = TranscenMod.HUD_ACTIVE
                || !TranscenMod.ZOMBIE_POSITIONS.isEmpty()
                || firstZombie(snapshot) != null;

        // server-driven so it is right even when the client cannot see the zombie
        pushHud(level, zombieOwned);

        // revival runs before the zombie-present test on purpose
        // gating it behind that test is exactly the case that matters,last zombie gone means firstZombie returns null and the rebuild is skipped
        restoreMissingZombies(level, snapshot);

        if (!zombieOwned) {
            // inert with no zombie, outstanding timelines still get to finish
            advanceRemovalTimers(level);
            return;
        }

        annihilateForeignEntities(level, snapshot);
        advanceRemovalTimers(level);
    }

    // getAllEntities() legitimately yields null entries and the sweep mutates the storages while iterating
    private static List<Entity> snapshot(ServerWorld level) {
        List<Entity> out = new ArrayList<>();
        for (Entity entity : level.getEntitiesIteratable()) {
            if (entity != null && !entity.removed) {
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

    private static void pushHud(ServerWorld level, boolean zombieOwned) {
        boolean active = zombieOwned;
        float progress = 1.0F;
        if (lastHudActive != null && lastHudActive == active
                && Math.abs(lastHudProgress - progress) < 0.001F) {
            return;
        }
        lastHudActive = active;
        lastHudProgress = progress;
        TranscenMod.NETWORK.send(
                PacketDistributor.DIMENSION.with(level::getDimensionKey),
                new HudSyncPacket(active, progress));
    }

    // one-shot, a repeating failure would flood a log that is already fragile
    private static boolean reviveFailureLogged;

    // uuids with a respawn queued but not yet run,keeps the four revive points from each queueing one
    // they all run on the server thread but the level's entity list is not updated until the tick ends
    // the mark goes in before the spawn is queued and comes out in a finally, so a failure cannot strand it
    private static final Set<UUID> SPAWN_PENDING = new HashSet<>();

    // must never throw out of an event handler - forge's EventBus reports it and a log4j conflict turns that report into a fatal LinkageError
    // printStackTrace rather than the logger, for the same reason
    private static void guardedRevive(ServerWorld level) {
        try {
            reviveNow(level);
        } catch (Throwable failure) {
            if (!reviveFailureLogged) {
                reviveFailureLogged = true;
                failure.printStackTrace();
            }
        }
    }

    private static void reviveNow(ServerWorld level) {
        restoreMissingZombies(level, snapshot(level));
    }

    // keyed by uuid and the respawn reuses it - both halves are needed
    // a head count cannot tell which zombie is missing and a fresh identity never satisfies the entry
    // an earlier revision did exactly that, every sweep added another copy
    // a live zombie is re-recorded here since the list is otherwise only written on this mod's own spawn path
    // 1794 zombies were once created in one tick, all at the origin, and it took the server down
    // the throttle makes a runaway degrade into a few repairs per tick
    private static final int MAX_SPAWNS_PER_TICK = 4;

    // last-resort circuit breaker on how many the mod keeps alive
    private static final int MAX_ZOMBIES = 1;

    private static int spawnsThisTick;

    private static void beginTickSpawnBudget() {
        spawnsThisTick = 0;
    }

    private static void restoreMissingZombies(ServerWorld level, List<Entity> snapshot) {
        // protection off means nothing is put back - this gate is what makes a wipe stick
        // encounterEnded is checked as well, one is switched by the player and the other by a wipe, both hold the repair
        if (!TranscenMod.defenceEnabled) {
            return;
        }
        if (encounterEnded) {
            return;
        }
        Set<UUID> present = new HashSet<>();
        for (Entity entity : snapshot) {
            if (entity instanceof TranscenZombie) { TranscenZombie zombie = (TranscenZombie) entity;
                present.add(zombie.getUniqueID());
                // keep the recorded spot and facing fresh so a respawn lands and looks where the zombie last was
                TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUniqueID(),
                        new Vector3d(zombie.getPosX(), zombie.getPosY(), zombie.getPosZ()));
                TranscenMod.ZOMBIE_YAW.put(zombie.getUniqueID(), zombie.rotationYaw);
                TranscenMod.ZOMBIE_PITCH.put(zombie.getUniqueID(), zombie.rotationPitch);
            }
        }

        // counted from the snapshot,not a by-class query
        // that query reported zero even right after a successful add on this setup, so the limit never held and every tick added another
        int aliveCount = 0;
        for (Entity entity : snapshot) {
            if (entity instanceof TranscenZombie) {
                aliveCount++;
            }
        }
        if (aliveCount >= MAX_ZOMBIES) {
            return;
        }

        for (Map.Entry<UUID, Vector3d> entry : TranscenMod.ZOMBIE_POSITIONS.entrySet()) {
            UUID id = entry.getKey();
            if (SPAWN_PENDING.contains(id)) {
                continue;
            }
            if (present.contains(id) || level.getEntityByUuid(id) != null) {
                continue;
            }
            if (spawnsThisTick >= MAX_SPAWNS_PER_TICK) {
                // budget spent, the entry stays on the list for the next tick
                return;
            }
            Vector3d pos = entry.getValue();
            float yaw = TranscenMod.ZOMBIE_YAW.getOrDefault(id, 0.0F);
            float pitch = TranscenMod.ZOMBIE_PITCH.getOrDefault(id, 0.0F);

            // never revive into the origin or unloaded ground - a zombie added to the manager there never becomes findable
            // the next sweep sees it missing and makes another,thousands per tick and the client stutters
            // an unusable position falls back to beside a player, loaded by definition
            if (isUnusablePosition(level, pos)) {
                pos = positionNearPlayer(level, pos);
                if (pos == null) {
                    // nowhere to put it, skip the entry and let the next tick try rather than pile up an unfindable entity
                    continue;
                }
                TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
            }

            // the lambda captures this so it has to be effectively final,the position above may have been replaced
            final Vector3d target = pos;
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
        // repair is not converging, the cap is masking it rather than solving it
        if (present.size() >= MAX_ZOMBIES) {
            TranscenMod.LOGGER.warn(
                    "[transcenmod] revival: {} zombies alive, at the {}-zombie cap -- repair is not converging",
                    present.size(), MAX_ZOMBIES);
        }
    }

    public static void spawnZombie(ServerWorld level, Vector3d pos) {
        spawnZombie(level, UUID.randomUUID(), pos, 0.0F, 0.0F);
    }


    // on the watch list before it is added - a removal in the same tick still leaves an entry to repair
    // facing is restored, a revived zombie that snaps to due south is visibly not the same entity
    private static void spawnZombie(ServerWorld level, UUID id, Vector3d pos, float yaw, float pitch) {
        TranscenZombie zombie = new TranscenZombie(TranscenMod.TRANSCEN_ZOMBIE.get(), level);
        zombie.setUniqueId(id);

        // moveTo with the relocation guard lifted,not a reflective write to the position field
        // the field write looked obvious but silently did not work on 1.19.2, 1794 zombies all at the origin
        // a declared method cannot be defeated by a name that resolves differently
        zombie.withRelocationAllowed(() -> zombie.setLocationAndAngles(pos.x, pos.y, pos.z, yaw, pitch));

        // head and body rotation are separate fields from yaw/pitch,without them a revived zombie snaps round on its first frame
        zombie.rotationYawHead = yaw;
        zombie.renderYawOffset = yaw;

        TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
        TranscenMod.ZOMBIE_YAW.put(id, yaw);
        TranscenMod.ZOMBIE_PITCH.put(id, pitch);


        boolean added = level.addEntity(zombie);
        if (added) {
            TranscenMod.LOGGER.info("[transcenmod] repair: re-added {}", id);
        }
    }

    // stage 1 - the server copy is torn out immediately, ending in ChunkMap.removeEntity -> broadcastAndSend
    // that also empties TrackedEntity.seenBy,no recipient is left to despawn and the client keeps its red shell
    // invulnerable and stationary, or the lightning kills it into a death animation while the shell is on display
    private static void annihilateForeignEntities(ServerWorld level, List<Entity> snapshot) {
        long now = level.getGameTime();
        for (Entity entity : snapshot) {
            if (isNeverRemovable(entity)) {
                continue;
            }
            // the zombie's own fireballs would be annihilated the instant they spawn, everything else including other mobs' projectiles is still cleared
            if (isOwnProjectile(entity)) {
                continue;
            }
            UUID id = entity.getUniqueID();
            if (TranscenMod.ANNIHILATION_DOOMED_ON.containsKey(id)) {
                continue;
            }
            // position is captured before removal,the entity is unreachable afterwards and the strikes still need a spot
            TranscenMod.ANNIHILATION_POSITIONS.put(id, entity.getPositionVec());
            TranscenMod.ANNIHILATION_DOOMED_ON.put(id, now);

            // the ban list is keyed by class,globally and for the rest of the session - a banned class can never join any world again
            // mob spawning stopped in every dimension with nothing on screen to explain it, the sweep already handles what is present
            if (!(entity instanceof PlayerEntity)) {
                TranscenMod.BANNED_CLASSES.add(entity.getClass());
            }
            entity.setInvulnerable(true);
            entity.setMotion(Vector3d.ZERO);
            if (entity instanceof MobEntity) { MobEntity mob = (MobEntity) entity;
                mob.setNoAI(true);
            }

            // mark the client copy first then remove the server copy in the same tick
            // the hand-rolled path removed the copy instantly,kept only as documentation of what not to do
            TranscenMod.NETWORK.send(
                    PacketDistributor.DIMENSION.with(level::getDimensionKey),
                    new MarkDoomedPacket(id));

            // a boss bar is not part of the entity,it is registered with the server and pushed on its own packet stream
            // so removing the entity does not remove the bar, it keeps drawing over a mob that is gone
            clearOwnedBossBars(entity);
            // second route for bars raised through the datapack/command registry, no reference back to the mob so the name is the only link
            clearMatchingRegisteredBossBars(level, entity);

            entity.remove(false);
        }
    }

    // structural, not a known field name - vanilla holders spell theirs differently and modded bosses invent their own
    // walks the entity's fields and its superclasses for anything derived from ServerBossInfo, covers WitherBoss, EnderDragon and forge holders
    // both levers - setVisible(false) stops the drawing, removeAllPlayers drops the subscription so no updates keep streaming
    // setVisible alone leaves the server streaming updates about a dead entity, removeAllPlayers alone lets clients that already have it keep drawing
    // bounded to the entity and its superclasses, an unbounded walk could reach the level or the player list and hide unrelated bars
    private static void clearOwnedBossBars(Entity entity) {
        Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Class<?> type = entity.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                // any BossInfo,not only ServerBossInfo - a mod can hold a plain one and drive the packets itself
                // testing the narrow subtype missed those and the bar looked like it refused to go away
                if (!net.minecraft.world.BossInfo.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(entity);
                    if (value instanceof net.minecraft.world.BossInfo && seen.add(value)) {
                        net.minecraft.world.BossInfo bar =
                                (net.minecraft.world.BossInfo) value;
                        // setVisible and removeAllPlayers live on the server-side implementation, the base class only carries the data
                        boolean deactivated = false;
                        if (bar instanceof net.minecraft.world.server.ServerBossInfo) { net.minecraft.world.server.ServerBossInfo serverBar = (net.minecraft.world.server.ServerBossInfo) bar;
                            serverBar.setVisible(false);
                            serverBar.removeAllPlayers();
                            deactivated = true;
                        }
                        TranscenMod.LOGGER.info(
                                "[transcenmod] boss bar {} ({}) owned by {} ({}) -- {}",
                                field.getName(), bar.getClass().getSimpleName(),
                                entity.getType(), entity.getUniqueID(),
                                deactivated ? "hidden" : "not a server bar, left alone");
                    }
                } catch (Throwable ignored) {
                    // an unreadable field is not a reason to abandon the removal
                }
            }
        }
    }

    // second sweep for bars whose ownership is not a field
    // a bar from the datapack/command registry is keyed by ResourceLocation and holds no reference back, the name is the only link
    // exact display name and only visible bars,the worst a coincidence does is hide a bar showing the same text
    private static void clearMatchingRegisteredBossBars(ServerWorld level, Entity entity) {
        try {
            Object events = level.getServer().getCustomBossEvents();
            if (events == null) {
                return;
            }
            java.lang.reflect.Method getEvents = events.getClass().getMethod("getEvents");
            Object list = getEvents.invoke(events);
            if (!(list instanceof java.util.Collection)) {
                return;
            }
            java.util.Collection<?> bars = (java.util.Collection<?>) list;
            String name = entity.getDisplayName().getString();
            for (Object candidate : bars) {
                if (!(candidate instanceof net.minecraft.world.server.ServerBossInfo)) {
                    continue;
                }
                net.minecraft.world.server.ServerBossInfo bar = (net.minecraft.world.server.ServerBossInfo) candidate;
                if (!bar.isVisible() || !bar.getName().getString().equals(name)) {
                    continue;
                }
                bar.setVisible(false);
                TranscenMod.LOGGER.info(
                        "[transcenmod] hid registered boss bar '{}' for {}", name, entity.getUniqueID());
            }
        } catch (Throwable ignored) {
            // best effort like the rest of the removal
        }
    }


    // by getShooter() not by class,so a ghast or blaze fireball is still annihilated and the zombie's own survives
    private static boolean isOwnProjectile(Entity entity) {
        if (!(entity instanceof ProjectileEntity)) {
            return false;
        }
        ProjectileEntity projectile = (ProjectileEntity) entity;
        return projectile.getShooter() instanceof TranscenZombie;
    }

    // players are never removed from the storages - corrupts EntityTickList#active which DistanceManager shares
    // the next movement packet then throws inside vanilla
    private static boolean isNeverRemovable(Entity entity) {
        if (entity instanceof PlayerEntity) {
            return true;
        }
        return TranscenZombie.isProtected(entity);
    }

    private static void advanceRemovalTimers(ServerWorld level) {
        long now = level.getGameTime();
        Iterator<Map.Entry<UUID, Long>> it = TranscenMod.ANNIHILATION_DOOMED_ON.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            UUID id = entry.getKey();
            long elapsed = now - entry.getValue();
            Vector3d pos = TranscenMod.ANNIHILATION_POSITIONS.getOrDefault(id, Vector3d.ZERO);

            if (elapsed < STRIKE_TICKS) {
                strikeAt(level, pos);
                continue;
            }
            if (elapsed < CLIENT_REMOVE_DELAY_TICKS) {
                continue;
            }

            // stage 3, the shell's two seconds are up: drop the client copy and leave the death puff
            TranscenMod.LOGGER.info(
                    "[transcenmod] DEAD t={} id={} elapsed={} sends=removal", now, id, elapsed);
            TranscenMod.NETWORK.send(
                    PacketDistributor.DIMENSION.with(level::getDimensionKey),
                    new ClientRemovePacket(id));
            spawnDeathPuff(level, pos);
            TranscenMod.ANNIHILATION_POSITIONS.remove(id);
            it.remove();
        }
    }

    private static void strikeAt(ServerWorld level, Vector3d pos) {
        for (int i = 0; i < BOLTS_PER_TICK; i++) {
            TranscenLightningBolt bolt =
                    new TranscenLightningBolt(TranscenMod.TRANSCEN_LIGHTNING.get(), level);
            // moveTo like spawnZombie, a declared method cannot be defeated by a name that resolves differently
            // no relocation guard to lift here,the lightning bolt is not a TranscenZombie
            bolt.setLocationAndAngles(pos.x, pos.y, pos.z, 0.0F, 0.0F);
            level.addEntity(bolt);
        }
    }

    // the vanilla white puff mobs leave on death
    private static void spawnDeathPuff(ServerWorld level, Vector3d pos) {
        level.spawnParticle(ParticleTypes.POOF, pos.x, pos.y + 0.5D, pos.z,
                20, 0.5D, 0.5D, 0.5D, 0.1D);
    }
}
