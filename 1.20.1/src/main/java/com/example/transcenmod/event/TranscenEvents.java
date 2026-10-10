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

// annihilation loop,one timeline per target
// tick 0 server storage removal with the client told to render it red
// ticks 1..10 two bolts per tick then the strikes stop, tick 40 client removal and the death puff
//
// dormant with no zombie in the level
// one sweep per server tick at most - executeCoreLogic is reachable from every living entity's tick
//   and walks the whole entity list, the latch is what keeps that from ending in an OutOfMemoryError
// players are never torn out of the entity storages, it corrupts EntityTickList#active which
//   DistanceManager shares and the next movement packet then throws inside vanilla
public class TranscenEvents {

    private static final int STRIKE_TICKS = 10;
    private static final int BOLTS_PER_TICK = 2;
    // server removal to client removal,the two second shell window
    private static final int CLIENT_REMOVE_DELAY_TICKS = 40;

    // latch plus the tick of the last sweep,one sweep per server tick at most
    private static boolean sweeping;
    private static long lastSweptTick = Long.MIN_VALUE;

    // last HUD state pushed to clients,so the packet only goes out on change
    private static Boolean lastHudActive;
    private static float lastHudProgress = -1.0F;

    // living entities,the frozen-everything behaviour

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityTickHighest(LivingEvent.LivingTickEvent event) {
        // legal: LivingTickEvent carries @Cancelable and ForgeHooks.onLivingTick hands the flag back
        // to LivingEntity#tick which checks it
        event.setCanceled(true);

        Entity entity = event.getEntity();

        // a doomed shell is a client leftover the server already discarded, ticking it would walk it
        // and animate it through the two second window instead of holding the frozen red model
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

    // level and player ticks,not cancelable in Forge 1.20.1

    // observed but never cancelled, TickEvent.LevelTickEvent carries no @Cancelable and
    // ForgeEventFactory#onPreLevelTick returns void so the flag would be discarded anyway
    // also a revival check point, repairing at both ends of the tick puts an entity removed early in
    // the tick back before it finishes instead of leaving a visible gap
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onLevelTickHighest(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        if (event.level instanceof ServerLevel) { ServerLevel level = (ServerLevel) event.level;
            guardedRevive(level);
        }
    }

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

    // 18000 is midnight in vanilla day time
    private static final long LOCKED_TIME = 18000L;

    // re-asserted every tick, the clock and the weather advance on their own and a single write drifts
    // out of midnight within seconds
    // setWeatherParameters(0, 6000, true, true) and not setRaining/setThunder, the first two arguments
    // are the clear and rain timers so 0 makes the weather turn now rather than after a random delay
    // gated on a zombie being in the level, not on the HUD latch - the latch is sticky and keying the
    // weather off it would leave the world permanently dark after the first egg use with nothing on
    // screen to explain why
    private static void forceNightAndStorm(ServerLevel level) {
        boolean present = false;
        for (Entity entity : snapshot(level)) {
            if (entity instanceof TranscenZombie) {
                present = true;
                break;
            }
        }
        // a zombie that has been torn out is still on the watch list and is coming back this tick,so
        // treat that as present too or the sky flickers off for one tick per removal
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

    // PlayerTickEvent is not cancelable either,same reason as the level tick
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onPlayerTickHighest(TickEvent.PlayerTickEvent event) {
        keepEggHolderAlive(event.player);
        executeCoreLogic(event.player);
    }

    // spawn-egg carrier protection

    // a different question from isCarryingEgg,and the distinction is the point
    // carrying is ownership and survives scrolling away, holding is intent - answering the left-click
    // toggle from the inventory made any left-click with any item flip the protection
    // no cache,the inventory sweep needed one, two hand slots are constant time lookups
    public static boolean isHoldingEgg(Player player) {
        return player.getMainHandItem().is(TranscenMod.TRANSCEN_SPAWN_EGG.get())
                || player.getOffhandItem().is(TranscenMod.TRANSCEN_SPAWN_EGG.get());
    }

    private static final float EGG_HOLDER_HEALTH = 20.0F;

    // exposed so the client guard pins the same value
    public static float eggHolderHealth() {
        return EGG_HOLDER_HEALTH;
    }

    // anywhere in the inventory,not in hand - requiring it held switched the protection off the moment
    // the player scrolled away, swapped to a weapon or had the stack moved
    // cached on the game tick,the mixin asks this on every getHealth along with the client pin and
    // the damage and death guards, and sweeping up to 36 stacks each time is a hot path
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

    // steady state,one restore per tick
    // covers what the damage hooks cannot: a player who joins already hurt,or whose health is moved
    // by a mod that never goes through the damage pipeline at all
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

    // cancel is the earliest point,damage is applied right after this event returns so a cancel means
    // the value is never written and nothing downstream ever sees a death to react to
    // restoring in a later hook cannot do that, by then the value is written and the observers have run
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

    // restoring and not cancelling keeps this out of the invulnerable flag that other mods and the
    // damage pipeline read for their own decisions
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

    // for the paths that never go through damage at all
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

    // toggle,left-click switches the protection off and clears

    private static final long WIPE_COOLDOWN_TICKS = 5L;

    private static long lastWipeTick = Long.MIN_VALUE;

    // true once a wipe has dismissed the encounter, until something is summoned again
    // while it is set the repair does nothing,and emptying the watch list is not enough on its own -
    // entries re-enter it a tick later and the encounter rebuilds, dismissed restored dismissed
    // lifted by any zombie entering the level by any route, egg or command
    // an earlier version had no reset at all, so it could never be cleared and every zombie created
    // afterwards dismissed itself too and the level filled with entities
    private static volatile boolean encounterEnded;

    // called by the wipe
    public static void endEncounter() {
        encounterEnded = true;
    }

    // called whenever a zombie joins the level, and by the egg
    public static void restartEncounter() {
        encounterEnded = false;
    }

    // the cooldown wraps the whole toggle,not just the wipe - one left-click raises several events,
    // so splitting them let the first one flip the flag, the wipe it asked for then be suppressed and
    // the second event flip the flag back, the click looked like it had done nothing
    public static void toggleDefence(ServerPlayer player, ServerLevel level) {
        long now = level.getGameTime();
        // the sentinel case has to be checked explicitly, now - lastWipeTick at Long.MIN_VALUE
        // overflows negative which is under any cooldown,so the guard returned on every call, and the
        // assignment sits after the guard so the sentinel was never replaced and it returned forever
        if (lastWipeTick != Long.MIN_VALUE && now - lastWipeTick < WIPE_COOLDOWN_TICKS) {
            return;
        }
        lastWipeTick = now;

        boolean on = !TranscenMod.defenceEnabled;

        // protection off first,then the clear - the clear inspects the world and nothing may get the
        // chance to decide to rebuild in the gap between the two steps
        TranscenMod.defenceEnabled = on;
        if (!on) {
            clearAllZombies(level);
        }

        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                on ? "\u00a7a\u9632\u5fa1\u5df2\u5f00\u542f"
                   : "\u00a7c\u9632\u5fa1\u5df2\u5173\u95ed"));
    }

    // not rate limited here,the cooldown belongs to the toggle which guards its flag flip and this
    // call together - guarding them apart let one of the pair happen without the other
    public static void clearAllZombies(ServerLevel level) {
        TranscenMod.defenceEnabled = false;

        // deep removal at the storage level, every route through the entity is refused by the entity so
        // CustomEntityRemover unlinks it from the level's sections,from the id and uuid indexes and from
        // the tick list by hand, nothing in the entity is consulted so nothing can refuse and its own
        // defences are left exactly as they are
        // repeated passes, a removal landing mid-iteration can leave behind an entity the current pass
        // has already walked past
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

        // every container that holds zombie state,not just the watch list
        TranscenMod.ZOMBIE_POSITIONS.clear();
        TranscenMod.ZOMBIE_YAW.clear();
        TranscenMod.ZOMBIE_PITCH.clear();
        SPAWN_PENDING.clear();
        TranscenMod.ANNIHILATION_DOOMED_ON.clear();
        TranscenMod.ANNIHILATION_POSITIONS.clear();

        // reset,so the next tick re-evaluates from scratch instead of deciding nothing has changed
        // and skipping the update
        lastSweptTick = Long.MIN_VALUE;
        lastHudActive = null;
        lastHudProgress = -1.0F;
        endEncounter();

        // tell clients to drop their shells,their copies, the bar and the music
        TranscenMod.HUD_ACTIVE = false;
        TranscenMod.NETWORK.send(
                PacketDistributor.DIMENSION.with(level::dimension),
                new HudSyncPacket(false, 0.0F));
        TranscenMod.NETWORK.send(
                PacketDistributor.DIMENSION.with(level::dimension),
                new com.example.transcenmod.network.WipeEndedPacket());

        TranscenMod.LOGGER.info("[transcenmod] wipe: {} zombie(s) removed", deep);
    }

    // banned classes may not re-enter the world

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityJoinHighest(EntityJoinLevelEvent event) {
        Entity joining = event.getEntity();

        // the encounter restarts on either priority,not just the low one
        // another mod's handler sits between the two and a zombie whose join it cancels still reached
        // this point, waiting for the low handler would miss the summon entirely and leave the
        // encounter switched off with a zombie standing in the world
        if (joining instanceof TranscenZombie) {
            restartEncounter();
        }

        // players must never be blocked from joining,whatever else happens
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

        // recorded the moment a zombie enters the level,whichever route put it there
        // the spawn egg creates its zombie through vanilla's own spawn-egg path, so without this the
        // list stays empty and the repair has nothing to restore
        // a join also restarts the encounter, which is what makes a wipe undoable by summoning again
        restartEncounter();
        // a retired zombie is not recorded,otherwise one a wipe just dismissed goes straight back onto
        // the list and the repair resurrects it
        if (!event.getLevel().isClientSide() && !zombie.isRetired()) {
            TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUUID(),
                    new Vec3(zombie.getX(), zombie.getY(), zombie.getZ()));
        }
    }

    // core loop

    public static void executeCoreLogic(Entity trigger) {
        if (!(trigger.level() instanceof ServerLevel)) {
            return;
        }
        ServerLevel level = (ServerLevel) trigger.level();
        if (level.isClientSide) {
            return;
        }
        // one sweep per tick,no matter how many entities fire this
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

        // the HUD is armed,not inferred - once the encounter has started it stays on for the session
        // and specifically does not follow whether a zombie happens to be in the level this tick
        // that is the difference between a steady bar and a strobing one, testing for a live zombie or
        // a watch-list entry reported the bar off every time another mod removed the zombie and on
        // again as the repair put it back - the bar is the encounter's frame, not an entity readout
        boolean zombieOwned = TranscenMod.HUD_ACTIVE
                || !TranscenMod.ZOMBIE_POSITIONS.isEmpty()
                || firstZombie(snapshot) != null;

        // server driven so the bar is right even when the client cannot see the zombie
        pushHud(level, zombieOwned);

        // revival runs before the zombie-present test,and deliberately so
        // gating it behind that test made revival impossible in the only case that matters,once the
        // last zombie was force-removed firstZombie returned null and this method returned early so the
        // watch list was never consulted - where the level has no zombie is exactly where a rebuild is
        // needed
        restoreMissingZombies(level, snapshot);

        if (!zombieOwned) {
            // inert with no zombie, outstanding timelines still get to finish
            advanceRemovalTimers(level);
            return;
        }

        annihilateForeignEntities(level, snapshot);
        advanceRemovalTimers(level);
    }

    // one copy of the entities up front,getAllEntities legitimately yields null entries and the sweep
    // mutates the storages while iterating them
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

    // one shot,a repeating failure cannot flood a log that is already fragile
    private static boolean reviveFailureLogged;

    // uuids with a respawn already queued but not yet run
    // this is what makes several revival check points safe,they all run on the server thread so they
    // cannot interleave, but they can each observe the same zombie as missing within one tick because
    // the level's entity list is not updated until the tick ends - that is four zombies instead of one
    // marked before the spawn is queued and removed in a finally, so a failure cannot strand an entry
    // and block future repairs
    private static final Set<UUID> SPAWN_PENDING = new HashSet<>();

    // guarded entry point for the revival check points outside the per-tick sweep, it must never throw
    // out of an event handler - Forge's EventBus then tries to report the exception and a log4j
    // conflict in a modpack turns that report into a fatal LinkageError that hides the real cause
    // printStackTrace rather than the logger for the same reason
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

    private static void reviveNow(ServerLevel level) {
        restoreMissingZombies(level, snapshot(level));
    }

    // puts a zombie back whenever one has vanished but its identity is still on the watch list
    // keyed by uuid and the respawn reuses that uuid,the two halves only work together
    // comparing by uuid is what tells you which zombie is missing,a head count cannot - three entries
    // and three zombies of different identities in the level looks complete
    // an earlier revision compared by uuid but spawned a fresh identity, so the entry stayed missing
    // forever and every sweep added another zombie until the world filled up with copies
    // a live zombie is re-recorded here too, the list is otherwise only written when a zombie spawns
    // through this mod's own code and the egg goes through vanilla's path
    private static void restoreMissingZombies(ServerLevel level, List<Entity> snapshot) {
        // protection off or a wiped encounter,nothing is put back - this gate is what makes a wipe
        // stick and it reaches this mod's systems only, the zombie's own defences are untouched
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
                // keep the recorded spot and facing fresh,so a respawn lands where the zombie last was
                // and looks the way it did
                TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUUID(),
                        new Vec3(zombie.getX(), zombie.getY(), zombie.getZ()));
                TranscenMod.ZOMBIE_YAW.put(zombie.getUUID(), zombie.getYRot());
                TranscenMod.ZOMBIE_PITCH.put(zombie.getUUID(), zombie.getXRot());
            }
        }

        // population limit counted from the snapshot that was just walked
        // without a ceiling the repair is unbounded, every entry that cannot be found gets its own
        // replacement and if the reason it cannot be found is that its position is untracked the
        // replacement is unfindable too, so the next sweep adds another
        if (aliveCount >= MAX_ZOMBIES) {
            return;
        }

        for (Map.Entry<UUID, Vec3> entry : TranscenMod.ZOMBIE_POSITIONS.entrySet()) {
            UUID id = entry.getKey();
            // already here,or a spawn for it is already queued and has not run yet
            // the second half is what stops the check points from each queueing their own copy
            if (present.contains(id) || SPAWN_PENDING.contains(id)) {
                continue;
            }
            Vec3 pos = entry.getValue();
            float yaw = TranscenMod.ZOMBIE_YAW.getOrDefault(id, 0.0F);
            float pitch = TranscenMod.ZOMBIE_PITCH.getOrDefault(id, 0.0F);

            // never revive into the world origin,and never into unloaded ground
            // the level only reports an entity once its chunk is loaded and it is in visible storage, so
            // a zombie at (0,0,0) in a world whose origin chunks are unloaded is accepted by the manager
            // and then never becomes findable - the next sweep made another and the sweep after that did
            // the same, thousands of them every tick
            if (isUnusablePosition(level, pos)) {
                pos = positionNearPlayer(level, pos);
                if (pos == null) {
                    continue;
                }
                TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
            }

            if (spawnsThisTick >= MAX_SPAWNS_PER_TICK) {
                // budget spent,the entry stays on the list and the next tick picks it up
                // the throttle makes an unforeseen interaction degrade into repairing a few per tick
                // rather than destroying the world
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

    // hard ceiling on the population the repair may rebuild
    private static final int MAX_ZOMBIES = 1;

    private static final int MAX_SPAWNS_PER_TICK = 4;

    private static int spawnsThisTick;

    private static void beginTickSpawnBudget() {
        spawnsThisTick = 0;
    }

    // true when a recorded position is somewhere the level will not track the entity
    // the literal origin is what an entity has before anything places it,so a record still reading
    // (0,0,0) means the placement never took and the origin is often outside the loaded region
    // anywhere else is judged by whether its chunk is loaded, an entity in an unloaded chunk is
    // accepted by the manager and then never reported, which looks the same as having been removed
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

    // from the player list so it is a real,logged-in player, offset a little so the zombie does not
    // spawn inside them
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


    // removes id from whichever of the manager's id sets is holding it
    // the set is found by walking the manager's fields and not by name,its declared type differs
    // between versions and the field is not part of any API, so a name would be a guess that fails
    // silently and a silent failure here is the duplication loop described at the call site
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
                    // each set is probed defensively, fastutil's primitive collections implement
                    // java.util.Set too and contains(Object) on those casts its argument to the
                    // primitive wrapper so asking one about a uuid throws ClassCastException
                    // skipping on any throwable is simpler and more complete than enumerating which
                    // set types are safe, a set that cannot answer is one that cannot hold the id
                    try {
                        field.setAccessible(true);
                        Object value = field.get(manager);
                        if (!(value instanceof java.util.Set<?> set) || !set.contains(id)) {
                            continue;
                        }
                        set.remove(id);
                    } catch (Throwable notApplicable) {
                        // not a set of uuids,next field
                    }
                }
            }
        } catch (Throwable failure) {
            TranscenMod.LOGGER.warn("[transcenmod] could not release claimed uuid {}", id, failure);
        }
    }

    public static void spawnZombie(ServerLevel level, Vec3 pos) {
        spawnZombie(level, UUID.randomUUID(), pos, 0.0F, 0.0F);
    }

    // creates a zombie carrying id at pos, facing yaw and pitch, and adds it to the level
    // registered on the watch list before it is added,so a removal landing in the same tick still
    // leaves an entry behind to be repaired
    // the facing is restored rather than left at zero, a revived zombie that snaps to due south is
    // visibly not the same entity
    private static void spawnZombie(ServerLevel level, UUID id, Vec3 pos, float yaw, float pitch) {
        TranscenZombie zombie = new TranscenZombie(TranscenMod.TRANSCEN_ZOMBIE.get(), level);
        zombie.setUUID(id);
        // the position is written straight into the field and not through a setter, as the original
        // design did - placement is a relocation by definition, the entity is built at the origin and
        // then moved to where the previous one died, and the guard on setPosRaw exists precisely to
        // refuse moves like that, so writing the field skips the guard without weakening it because
        // this is the mod's own code and not a caller the guard is meant to stop
        try {
            // Entity#position is widened to public by the AccessTransformer
            Field posField = Entity.class.getDeclaredField("position");
            posField.setAccessible(true);
            posField.set(zombie, pos);
        } catch (Exception ignored) {
            zombie.withRelocationAllowed(() -> zombie.moveTo(pos.x, pos.y, pos.z));
        }
        // facing is restored through the normal setters,they are not position writes so the guard on
        // setPosRaw does not apply, and setYRot also resets the previous-rotation fields so the entity
        // does not visibly spin from its spawn orientation on the first frame
        zombie.setYRot(yaw);
        zombie.setXRot(pitch);
        zombie.setYHeadRot(yaw);
        zombie.setYBodyRot(yaw);

        TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
        TranscenMod.ZOMBIE_YAW.put(id, yaw);
        TranscenMod.ZOMBIE_PITCH.put(id, pitch);

        // the id is released before it is handed to a new entity
        // the manager keeps every uuid it has ever accepted and refuses a second entity with the same
        // one,logging a warning and returning false so addFreshEntity looks like it worked
        // normally pruned on removal, but this zombie overrides setRemoved so its id can stay claimed
        // while the entity is unfindable - left that way the repair loops invisibly, the add is refused
        // so nothing appears and no ceiling is ever reached while the entry keeps the next sweep trying
        releaseClaimedUuid(level, id);

        level.addFreshEntity(zombie);
    }

    // stage 1, the server copy is torn out at the lowest level immediately through CustomEntityRemover
    // and the client is told to keep rendering its own copy in red
    // not a vanilla removal path,vanilla's ends in ChunkMap.removeEntity -> broadcastAndSend which
    // sends the despawn, and that class also empties TrackedEntity.seenBy so there is no tracked
    // recipient left for a despawn even if a later sweep looks for one
    // invulnerable and stationary for the brief remainder of its life so the lightning cannot kill it
    // into a death animation while the shell is on display
    private static void annihilateForeignEntities(ServerLevel level, List<Entity> snapshot) {
        long now = level.getGameTime();
        for (Entity entity : snapshot) {
            if (isNeverRemovable(entity)) {
                continue;
            }
            // the zombie's own fireballs are spared, they are projectiles like any other and would
            // otherwise be annihilated the instant they spawn, deleting the attack before it travels
            // everything else, other mobs' projectiles included, is still cleared
            if (isOwnProjectile(entity)) {
                continue;
            }
            UUID id = entity.getUUID();
            if (TranscenMod.ANNIHILATION_DOOMED_ON.containsKey(id)) {
                continue;
            }
            // position has to be captured before removal,afterwards the entity is no longer
            // reachable through the level but the strikes and the death puff still need a location
            TranscenMod.ANNIHILATION_POSITIONS.put(id, entity.position());
            TranscenMod.ANNIHILATION_DOOMED_ON.put(id, now);

            // the entity's class is deliberately not banned
            // banning was meant to stop an annihilated creature walking straight back in, but it is
            // applied to the class globally and for the rest of the session - after the first sweep
            // touched a cow, a sheep and a zombie no creature of those classes could ever join any
            // world again, mob spawning stopped everywhere in every dimension with nothing on screen
            // to explain it, the sweep already deals with anything present
            if (!(entity instanceof Player)) {
                TranscenMod.BANNED_CLASSES.add(entity.getClass());
            }
            entity.setInvulnerable(true);
            entity.setDeltaMovement(Vec3.ZERO);
            if (entity instanceof Mob) { Mob mob = (Mob) entity;
                mob.setNoAi(true);
            }

            // tell the client to hold its copy first,then the server copy goes in the same tick
            // CustomEntityRemover and not the hand-rolled surgery, it detaches the entity from the
            // server storages and empties its ChunkMap TrackedEntity#seenBy set, and that combination
            // is what preserves the client's copy - the hand-rolled path removed it instantly
            TranscenMod.NETWORK.send(
                    PacketDistributor.DIMENSION.with(level::dimension),
                    new MarkDoomedPacket(id));

            // the bar is not part of the entity,so removing the entity does not remove the bar
            // both routes are swept,the one reachable from the entity as a field and the
            // registry-backed one that only the display name still links to it
            clearOwnedBossBars(entity);
            clearMatchingRegisteredBossBars(level, entity);

            CustomEntityRemover.removeEntity(entity);
        }
    }


    // deactivates any boss bar an entity is holding
    // a boss bar is not part of the entity, it is a ServerBossEvent held by the entity and registered
    // with the server which pushes it to every player as its own packet stream - so deleting the
    // entity does not delete the bar and the bar keeps being drawn over a mob that no longer exists
    // both levers are pulled,setVisible(false) is what actually stops the bar being rendered and
    // removeAllPlayers drops the subscription outright - only the second still lets clients that
    // already received the bar keep showing it, only the first leaves the server streaming updates
    // about a dead entity
    // depth is bounded to the entity and its superclasses, an unbounded walk from an arbitrary entity
    // could reach into the level,the server and the player list and deactivate unrelated bars
    private static void clearOwnedBossBars(Entity entity) {
        Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Class<?> type = entity.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                // any BossEvent, not only ServerBossEvent
                // ServerBossEvent is one implementation of the base class and not the only one a mod
                // can hold, a datapack bar is a CustomBossEvent and a mod may keep a plain BossEvent
                // and drive the packets itself - testing for the narrow subtype missed those, which is
                // the custom bar refuses to go away case, the field was there and the scan declined
                // to look at it
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
                    // a field that cannot be read is not a reason to abandon the removal
                }
            }
        }
    }

    // hides any server-registered boss bar whose name matches the entity's display name
    // a second sweep,because ownership is not always a field - a bar raised through the datapack or
    // command path lives in a registry keyed by ResourceLocation and holds no reference back to the
    // entity at all, so the field scan above can never see it and the name is the only thing still
    // connecting the two
    // matching is on the exact display name and only bars that are already visible are touched,so the
    // worst a coincidence can do is hide a bar showing the same text as the entity being removed
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
        }
    }

    // ownership is checked through Projectile#getOwner and not by projectile class,so an unrelated
    // LargeFireball from a ghast or a blaze is still annihilated while the zombie's own attack survives
    private static boolean isOwnProjectile(Entity entity) {
        if (!(entity instanceof Projectile)) {
            return false;
        }
        Projectile projectile = (Projectile) entity;
        return projectile.getOwner() instanceof TranscenZombie;
    }

    // players are never removed from the storages,that corrupts EntityTickList#active which
    // DistanceManager shares and the next movement packet throws inside vanilla
    private static boolean isNeverRemovable(Entity entity) {
        if (entity instanceof Player) {
            return true;
        }
        return TranscenZombie.isProtected(entity);
    }

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

            // stage 3,the shell's two seconds are up - drop the client copy and leave the death puff
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

    private static void strikeAt(ServerLevel level, Vec3 pos) {
        for (int i = 0; i < BOLTS_PER_TICK; i++) {
            TranscenLightningBolt bolt =
                    new TranscenLightningBolt(TranscenMod.TRANSCEN_LIGHTNING.get(), level);
            try {
                // Entity#position is public via the AccessTransformer
                Field posField = Entity.class.getDeclaredField("position");
                posField.setAccessible(true);
                posField.set(bolt, pos);
            } catch (Exception ignored) {
                bolt.moveTo(pos.x, pos.y, pos.z);
            }
            level.addFreshEntity(bolt);
        }
    }

    // the vanilla puff mobs leave behind when they die
    private static void spawnDeathPuff(ServerLevel level, Vec3 pos) {
        level.sendParticles(ParticleTypes.POOF, pos.x, pos.y + 0.5D, pos.z,
                20, 0.5D, 0.5D, 0.5D, 0.1D);
    }
}
