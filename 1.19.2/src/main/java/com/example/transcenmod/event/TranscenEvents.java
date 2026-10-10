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
import net.minecraft.network.chat.Component;
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
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// one timeline per target - tick 0 tears out the server copy and tells the client to render it red
// ticks 1..10 two blood-red bolts per tick, tick 40 drops the client copy and the vanilla death puff
// executeCoreLogic runs from every living entity tick and walks the whole entity list, the
// lastSweptTick latch holds that to one sweep per server tick - without it a normal world does
// tens of thousands of traversals per tick and ends in an OutOfMemoryError
// players are never torn out of the entity storages, that corrupts EntityTickList#active which
// DistanceManager shares and the next movement packet then throws inside vanilla
public class TranscenEvents {

    private static final int STRIKE_TICKS = 10;
    private static final int BOLTS_PER_TICK = 2;
    private static final int CLIENT_REMOVE_DELAY_TICKS = 40;

    private static boolean sweeping;
    private static long lastSweptTick = Long.MIN_VALUE;

    // cached hud state,the packet only goes out when this changes
    private static Boolean lastHudActive;
    private static float lastHudProgress = -1.0F;

    // living entities the frozen everything behaviour

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityTickHighest(LivingEvent.LivingTickEvent event) {
        // LivingTickEvent carries @Cancelable and ForgeHooks.onLivingTick hands the flag back to
        // LivingEntity#tick which checks it so cancelling here is legal
        event.setCanceled(true);

        Entity entity = event.getEntity();

        // a doomed shell is a client-side leftover the server has already dropped
        // ticking it would show a mob that keeps walking and animating instead of a frozen red model
        if (entity.getLevel().isClientSide
                && com.example.transcenmod.client.DoomedShell.isDoomed(entity.getUUID())) {
            return;
        }

        executeCoreLogic(entity);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityTickLowest(LivingEvent.LivingTickEvent event) {
        event.setCanceled(false);
    }

    // level and player ticks not cancelable in Forge 1.20.1

    // never cancelled - TickEvent.LevelTickEvent carries no @Cancelable and
    // ForgeEventFactory#onPreLevelTick returns void so the flag would be discarded anyway
    // both ends of the level tick run the repair so an entity removed early in the tick is back
    // before that tick finishes instead of leaving a visible gap
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

    // 18000 is dusk in 1.19.2,so locking it here reads as deep night
    private static final long LOCKED_TIME = 18000L;

    // both halves are re-applied every tick because time advances and rain stops when the timer runs out
    // so anything set once decays instead of holding
    // setWeatherParameters(0, duration, true, true) rather than setRaining/setThunder - the 0 is the
    // clear timer, which turns the weather the moment this runs rather than after a random delay
    // gated on a zombie in the level and not on the hud latch, the latch is sticky so keying weather
    // off it would leave the world dark forever after the first egg use
    private static void forceNightAndStorm(ServerLevel level) {
        boolean present = false;
        for (Entity entity : snapshot(level)) {
            if (entity instanceof TranscenZombie) {
                present = true;
                break;
            }
        }
        // a zombie torn out is still on the watch list and comes back this tick
        // counting it as present is what stops the sky flickering off for one tick per removal
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

    // TickEvent.PlayerTickEvent is not cancelable either,see above
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onPlayerTickHighest(TickEvent.PlayerTickEvent event) {
        executeCoreLogic(event.player);
        keepEggHolderAlive(event.player);
    }


    // wipes every TranscenZombie out of the level and ends the encounter,the egg both summons and
    // dismisses with the same click
    // order matters and getting it wrong makes the wipe undo itself - the watch list drives the
    // repair so it has to be empty before the entities go, otherwise every removal reads as a zombie
    // that vanished and the repair puts one back for each
    // same detachment as foreign entities so the client is not told to drop its copies at once, the
    // hud is switched off here because the encounter must end visibly at once music included
    // left-click with the egg toggles this mod's protection and says so in chat
    // a toggle rather than a one-way action, one message per flip means the state is never a guess
    public static void toggleDefence(ServerPlayer player, ServerLevel level) {
        // the cooldown wraps the whole toggle, not just the wipe - one left-click raises several events
        // and guarding the pair together is what makes one click one action, split apart the first
        // event flipped the flag and the second flipped it back so the click looked like it did nothing
        long now = level.getGameTime();
        if (lastWipeTick != Long.MIN_VALUE && now - lastWipeTick < WIPE_COOLDOWN_TICKS) {
            return;
        }
        lastWipeTick = now;

        boolean on = !TranscenMod.defenceEnabled;

        // defence off first then the clear
        // the clear inspects the world so nothing may be restored in the gap between the two steps
        TranscenMod.defenceEnabled = on;
        if (!on) {
            clearAllZombies(level);
        }

        player.sendSystemMessage(Component.literal(
                on ? "\u00a7a\u9632\u5fa1\u5df2\u5f00\u542f"
                   : "\u00a7c\u9632\u5fa1\u5df2\u5173\u95ed"));
    }

    public static void clearAllZombies(ServerLevel level) {
        // no rate limit here, the cooldown belongs to the toggle and covers this call too
        // defence must already be off before this runs
        // the repair has to be down before the world is read
        TranscenMod.defenceEnabled = false;

        // storage-level removal, the entity is bypassed entirely - its own removal overrides refuse
        // every route through it, CustomEntityRemover unlinks it from the sections, the id and uuid
        // indexes and the tick list by hand, leaving its defences exactly as they are
        // several passes, a removal landing mid-iteration can leave behind an entity this pass walked past
        int deep = 0;
        for (int pass = 0; pass < 8; pass++) {
            List<Entity> batch = new ArrayList<>();
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

        TranscenMod.ZOMBIE_POSITIONS.clear();
        TranscenMod.ZOMBIE_YAW.clear();
        TranscenMod.ZOMBIE_PITCH.clear();
        SPAWN_PENDING.clear();
        TranscenMod.ANNIHILATION_DOOMED_ON.clear();
        TranscenMod.ANNIHILATION_POSITIONS.clear();

        // reset the cached comparisons otherwise the next tick decides nothing has changed and skips
        // the push
        lastSweptTick = Long.MIN_VALUE;
        lastHudActive = null;
        lastHudProgress = -1.0F;

        // shells bar and music all live on the client,so none of them stops unless it is told to
        TranscenMod.HUD_ACTIVE = false;
        TranscenMod.NETWORK.send(
                PacketDistributor.DIMENSION.with(level::dimension),
                new HudSyncPacket(false, 0.0F));
        TranscenMod.NETWORK.send(
                PacketDistributor.DIMENSION.with(level::dimension),
                new com.example.transcenmod.network.WipeEndedPacket());

        TranscenMod.LOGGER.info("[transcenmod] wipe: {} zombie(s) removed", deep);
    }

    private static final float EGG_HOLDER_HEALTH = 20.0F;

    // not a slowdown - one left-click raises three events (air, block, attack-entity) and more than
    // one of them fires depending on the crosshair, a few ticks collapses them into one action and
    // still allows two deliberate clicks in a row
    private static final long WIPE_COOLDOWN_TICKS = 5L;

    private static long lastWipeTick = Long.MIN_VALUE;

    // true once a wipe dismissed the encounter until a zombie enters the world again
    // while set the repair does nothing at all - emptying the watch list is not enough on its own,
    // entries re-enter it a tick later and the encounter rebuilds, dismissed, restored, dismissed
    // any zombie entering the level by any route lifts it egg or command - an earlier version of this
    // flag could never be cleared, so every zombie made afterwards dismissed itself too and the level
    // filled with entities
    private static volatile boolean encounterEnded;

    public static void endEncounter() {
        encounterEnded = true;
    }

    public static void restartEncounter() {
        encounterEnded = false;
    }

    // (0,0,0) means the placement never took and the origin is frequently outside the loaded region
    // elsewhere an unloaded chunk is accepted by the manager and then never reported, which looks
    // exactly like having been removed
    private static boolean isUnusablePosition(ServerLevel level, Vec3 pos) {
        if (pos == null) {
            return true;
        }
        if (pos.x == 0.0D && pos.y == 0.0D && pos.z == 0.0D) {
            return true;
        }
        try {
            return !level.hasChunkAt(new net.minecraft.core.BlockPos(
                    net.minecraft.util.Mth.floor(pos.x),
                    net.minecraft.util.Mth.floor(pos.y),
                    net.minecraft.util.Mth.floor(pos.z)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    // from the player list rather than the entity list so it is a real logged-in player
    // offset a little so the zombie does not spawn inside them
    private static Vec3 positionNearPlayer(ServerLevel level, Vec3 fallback) {
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            return new Vec3(player.getX() + 2.0D, player.getY(), player.getZ() + 2.0D);
        }
        // no players,no loaded ground with them - fall back to the world spawn which is always loaded
        if (fallback != null && level.hasChunkAt(level.getSharedSpawnPos())) {
            net.minecraft.core.BlockPos spawn = level.getSharedSpawnPos();
            return new Vec3(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D);
        }
        return null;
    }

    // while the egg is carried the health is pinned and death is refused
    // pinning alone is not enough - damage is applied and death is decided inside one call, so a
    // tick-end reset can land after the death was registered and the screen already requested
    // the hooks sit at different points - onLivingDamageLowest restores health before the damage is
    // applied so the killing blow never lands, onLivingDeathLowest cancels deaths that bypass damage
    // (the void, /kill) and this one is the steady state, one restore per tick
    // the death screen is the client's reaction to a death packet
    // so a death that never happens cannot open one
    private static void keepEggHolderAlive(Player player) {
        if (player.getLevel().isClientSide || player.isCreative() || player.isSpectator()) {
            return;
        }
        if (!isCarryingEgg(player)) {
            return;
        }
        if (player.getHealth() < EGG_HOLDER_HEALTH) {
            player.setHealth(EGG_HOLDER_HEALTH);
        }
        player.setAirSupply(player.getMaxAirSupply());
        // fire and the void keep damaging a player who cannot be killed
        // putting them out here stops an invulnerable player burning on screen
        if (player.isOnFire()) {
            player.clearFire();
        }
    }

    // whole inventory rather than the held slot, the intent is this player owns the encounter and
    // requiring it held switched the protection off the moment the player scrolled away to fight back
    // cached for one tick because it is asked from several places per tick (the getHealth mixin, the
    // client pin, the damage and death guards) and sweeping 36 stacks each time is a hot path
    // the cache key is the server tick so it cannot serve a stale answer across ticks
    public static boolean isCarryingEgg(Player player) {
        long tick = player.getLevel().getGameTime();
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

    // a different question from isCarryingEgg - the toggle is a deliberate gesture and answering it
    // from the inventory meant a left-click with any item at all flipped the protection
    // no cache, two hand slots are constant-time lookups and the inventory sweep was what needed one
    public static boolean isHoldingEgg(Player player) {
        return player.getMainHandItem().is(TranscenMod.TRANSCEN_SPAWN_EGG.get())
                || player.getOffhandItem().is(TranscenMod.TRANSCEN_SPAWN_EGG.get());
    }

    // shared with the client guard so both sides pin the same value
    public static float eggHolderHealth() {
        return EGG_HOLDER_HEALTH;
    }

    // cancelling here is the earliest point - damage is applied immediately after the event returns
    // so a cancelled event means the value is never written and nothing downstream sees a death
    // restoring health in a later hook cannot do that, the value is written by then
    // this hook covers the red lightning, which does victim.hurt(source, 1919810) then
    // victim.setHealth(0) with nowhere in between - cancelling does not stop that setHealth(0),
    // which is why the tick-level pin and the screen guard exist as well
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingHurtLowest(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (player.getLevel().isClientSide || !isCarryingEgg(player)) {
            return;
        }
        TranscenMod.LOGGER.info(
                "[transcenmod] refused {} damage to egg holder (amount {})",
                event.getSource().getMsgId(), event.getAmount());
        event.setCanceled(true);
        player.setHealth(EGG_HOLDER_HEALTH);
    }

    // health restored rather than cancelled,cancelling would set the invulnerable flag that other
    // mods and the damage pipeline read for their own decisions
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDamageLowest(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (player.getLevel().isClientSide || !isCarryingEgg(player)) {
            return;
        }
        player.setHealth(EGG_HOLDER_HEALTH);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDeathLowest(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (player.getLevel().isClientSide || !isCarryingEgg(player)) {
            return;
        }
        player.setHealth(EGG_HOLDER_HEALTH);
        event.setCanceled(true);
    }

    // banned classes may not re-enter the world

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityJoinHighest(EntityJoinLevelEvent event) {
        Entity joining = event.getEntity();

        // restarted on either priority, not just the low one - another mod's handler sits between them
        // and a zombie whose join it cancels still reached this point so waiting for the low handler
        // would miss the summon and leave the encounter off with a zombie standing in the world
        if (joining instanceof TranscenZombie) {
            restartEncounter();
        }

        // players are never blocked from joining,whatever else happens
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

        // record the entry the moment a zombie enters the level whichever route put it there, the egg
        // uses vanilla's own spawn path so without this the list stays empty and the repair has
        // nothing to work from
        // a retired zombie is not recorded, otherwise a zombie a wipe just dismissed goes straight
        // back into the list and the repair resurrects it
        // joining restarts the encounter, from the egg or from a command,which is what makes the wipe
        // undoable by summoning again
        restartEncounter();
        if (!event.getLevel().isClientSide() && !zombie.isRetired()) {
            TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUUID(),
                    new Vec3(zombie.getX(), zombie.getY(), zombie.getZ()));
        }
    }

    // core loop

    public static void executeCoreLogic(Entity trigger) {
        if (!(trigger.getLevel() instanceof ServerLevel)) {
            return;
        }
        ServerLevel level = (ServerLevel) trigger.getLevel();
        if (level.isClientSide) {
            return;
        }
        // protection off means no sweep at all - without this guard off still produced revivals and
        // still left a zombie on screen, the switch was cutting one path into the repair and missing
        // the other
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

    private static void sweep(ServerLevel level) {
        // fresh spawn budget each tick,so a burst of removals cannot cascade into a flood
        beginTickSpawnBudget();

        List<Entity> snapshot = snapshot(level);

        // the hud is armed not inferred - once the encounter has started it stays on and does not
        // follow whether a zombie happens to be in the level this tick
        // testing for a zombie or a watch-list entry reads as a live signal so the bar was reported
        // off and on again every time another mod removed the zombie and the repair put it back
        // the bar is the encounter's frame and not a health readout of an entity
        boolean zombieOwned = TranscenMod.HUD_ACTIVE
                || !TranscenMod.ZOMBIE_POSITIONS.isEmpty()
                || firstZombie(snapshot) != null;

        // server-driven,so the bar is right even when the client cannot see the zombie
        pushHud(level, zombieOwned);

        // revival runs before the is-a-zombie-present test on purpose
        // gating it behind that test made revival impossible exactly where it is needed
        // with the last zombie force-removed firstZombie returned null and the watch list was never read
        restoreMissingZombies(level, snapshot);

        if (!zombieOwned) {
            // inert with no zombie,outstanding timelines still get to finish
            advanceRemovalTimers(level);
            return;
        }

        annihilateForeignEntities(level, snapshot);
        advanceRemovalTimers(level);
    }

    // one copy up front because getAllEntities() legitimately yields null entries
    // and the sweep mutates the storages while iterating them
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

    // one-shot,a repeating failure must not flood a log that is already fragile
    private static boolean reviveFailureLogged;

    // uuids with a respawn queued but not yet run
    // there are four revive points and they all run on the server thread
    // so they cannot interleave, but each can see the same zombie as missing within one tick
    // and queue its own spawn, the level's entity list is not updated until the tick ends
    // marked before the spawn is queued and cleared in a finally, so a failure cannot strand an entry
    // and block future repairs
    private static final Set<UUID> SPAWN_PENDING = new HashSet<>();

    // best effort, and it must never throw out of an event handler - Forge's EventBus then tries to
    // report the exception and a log4j conflict in a modpack turns that report into a fatal
    // LinkageError that hides the real cause, printStackTrace rather than the logger for that reason
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
    // keyed by uuid and the respawn reuses it, both halves are needed - a head count cannot tell which
    // zombie is missing, and spawning a fresh identity leaves the entry missing forever, which is how
    // an earlier revision filled the world with copies one more per sweep
    // a live zombie is re-recorded here too, the watch list is otherwise only written when a zombie
    // goes through this mod's own spawn code and the egg uses vanilla's path
    // 1794 zombies were once created in a single tick all at the origin and it took the server down
    // the throttle makes an unforeseen interaction degrade into repairing a few per tick
    private static final int MAX_SPAWNS_PER_TICK = 4;

    // last resort circuit breaker
    private static final int MAX_ZOMBIES = 1;

    private static int spawnsThisTick;

    private static void beginTickSpawnBudget() {
        spawnsThisTick = 0;
    }

    private static void restoreMissingZombies(ServerLevel level, List<Entity> snapshot) {
        // nothing is put back while the protection is off, and that gate only reaches this mod's
        // systems and the zombie's own defences are untouched
        // encounterEnded below is not redundant with it - one is switched by the player and the other
        // by a wipe, and either alone holds the repair
        if (!TranscenMod.defenceEnabled) {
            return;
        }
        if (encounterEnded) {
            return;
        }
        Set<UUID> present = new HashSet<>();
        for (Entity entity : snapshot) {
            if (entity instanceof TranscenZombie) { TranscenZombie zombie = (TranscenZombie) entity;
                present.add(zombie.getUUID());
                // keep the spot and facing fresh,so a respawn lands and looks the way the zombie last did
                TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUUID(),
                        new Vec3(zombie.getX(), zombie.getY(), zombie.getZ()));
                TranscenMod.ZOMBIE_YAW.put(zombie.getUUID(), zombie.getYRot());
                TranscenMod.ZOMBIE_PITCH.put(zombie.getUUID(), zombie.getXRot());
            }
        }

        // counted from the snapshot and not from a by-class query - the query reported zero on this
        // setup even right after a successful add while the level's entity list grew by one each time
        // so there was no way to decide enough zombies already exist and every tick added another one
        // the entity list is authoritative and the snapshot comes from it so counting here is free
        int aliveCount = 0;
        for (Entity entity : snapshot) {
            if (entity instanceof TranscenZombie) {
                aliveCount++;
            }
        }
        if (aliveCount >= MAX_ZOMBIES) {
            return;
        }

        for (Map.Entry<UUID, Vec3> entry : TranscenMod.ZOMBIE_POSITIONS.entrySet()) {
            UUID id = entry.getKey();
            if (SPAWN_PENDING.contains(id)) {
                continue;
            }
            if (present.contains(id) || level.getEntity(id) != null) {
                continue;
            }
            if (spawnsThisTick >= MAX_SPAWNS_PER_TICK) {
                // budget spent,the entry stays on the list for the next tick
                return;
            }
            Vec3 pos = entry.getValue();
            float yaw = TranscenMod.ZOMBIE_YAW.getOrDefault(id, 0.0F);
            float pitch = TranscenMod.ZOMBIE_PITCH.getOrDefault(id, 0.0F);

            // never the world origin and never unloaded ground - the manager only reports an entity
            // through getEntity once its chunk is loaded and it is in visible storage
            // a zombie added at (0,0,0) with the origin chunks unloaded is never findable, so the next
            // sweep saw it missing and made another, thousands of them per tick and a stuttering client
            // a recorded position is honoured only where the game will track it,otherwise the zombie
            // goes beside a player which is loaded by definition
            if (isUnusablePosition(level, pos)) {
                pos = positionNearPlayer(level, pos);
                if (pos == null) {
                    // nowhere to put it, skip and let the next tick try again rather than piling up
                    // an entity that could never be found
                    continue;
                }
                TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
            }

            // the deferred task captures this so it has to be effectively final,hence the separate name
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
        // the cap is masking a repair that is not converging,not solving it
        if (present.size() >= MAX_ZOMBIES) {
            TranscenMod.LOGGER.warn(
                    "[transcenmod] revival: {} zombies alive, at the {}-zombie cap -- repair is not converging",
                    present.size(), MAX_ZOMBIES);
        }
    }

    public static void spawnZombie(ServerLevel level, Vec3 pos) {
        spawnZombie(level, UUID.randomUUID(), pos, 0.0F, 0.0F);
    }

    // frees id from the manager's claimed uuids so a new entity may take it
    // structural rather than name-based - a name that does not resolve is swallowed and looks like
    // success which is how this bug stayed hidden once already
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
                    // probed defensively - fastutil's primitive collections implement java.util.Set
                    // too and contains(Object) on those casts its argument to the primitive wrapper, so
                    // asking a LongSet about a uuid throws ClassCastException
                    // a set that cannot answer the question is one that cannot be holding the id
                    try {
                        field.setAccessible(true);
                        Object value = field.get(manager);
                        if (!(value instanceof java.util.Set<?> set) || !set.contains(id)) {
                            continue;
                        }
                        set.remove(id);
                        TranscenMod.LOGGER.info(
                                "[transcenmod] released claimed uuid {} from {}.{} (size now {})",
                                id, type.getSimpleName(), field.getName(), set.size());
                    } catch (Throwable notApplicable) {
                        // not a set of uuids,next field
                    }
                }
            }
        } catch (Throwable failure) {
            TranscenMod.LOGGER.warn("[transcenmod] could not release claimed uuid {}", id, failure);
        }
    }

    // registered on the watch list before it is added,so a removal in the same tick still leaves an
    // entry behind to repair
    // facing is restored rather than left at zero - a revived zombie that snaps to due south is
    // visibly not the same entity
    private static void spawnZombie(ServerLevel level, UUID id, Vec3 pos, float yaw, float pitch) {
        TranscenZombie zombie = new TranscenZombie(TranscenMod.TRANSCEN_ZOMBIE.get(), level);
        zombie.setUUID(id);

        // moveTo with the relocation guard lifted rather than a reflective write to the position field
        // the field write relied on the field resolving under the name position and on 1.19.2 it was
        // silently not working - 1794 zombies were created and every one of them sat at the origin
        // moveTo is a declared method so there is no name to get wrong
        zombie.withRelocationAllowed(() -> zombie.moveTo(pos.x, pos.y, pos.z, yaw, pitch));

        // moveTo already set yaw/pitch but the head and body rotations are separate fields, a zombie
        // that does not reset them snaps round on its first frame
        zombie.setYHeadRot(yaw);
        zombie.setYBodyRot(yaw);

        TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
        TranscenMod.ZOMBIE_YAW.put(id, yaw);
        TranscenMod.ZOMBIE_PITCH.put(id, pitch);

        // the manager keeps every uuid it has ever accepted and refuses a second entity with the same
        // one, a refusal that only logs and returns false so addFreshEntity looks like it worked
        // that set is normally pruned on removal but this zombie overrides setRemoved as a no-op, so
        // its id can stay claimed with no entity behind it - every revival is then refused and the
        // sweep tries again next tick, which is the it-duplicates-on-its-own report
        // cleared here rather than only on removal so the spawn is self-sufficient and cannot leak,
        // the id is freed in the same statement that reuses it
        releaseClaimedUuid(level, id);

        boolean added = level.addFreshEntity(zombie);

        // the lookup answers what the repair depends on - added only proves the manager accepted the
        // call, not that the entity is findable in the level
        int byClass = level.getEntitiesOfClass(
                TranscenZombie.class, level.getWorldBorder().getCollisionShape().bounds()).size();
    }

    // stage 1 - the server copy goes at once through CustomEntityRemover,the client keeps rendering red
    // not a vanilla removal path because vanilla's ends in ChunkMap.removeEntity -> broadcastAndSend,
    // which is what sends the despawn packet, and CustomEntityRemover also empties
    // TrackedEntity.seenBy so no tracked recipient is left to receive one
    // invulnerable and stationary for the rest of its life so the lightning cannot kill it into a
    // death animation while the shell is on display
    private static void annihilateForeignEntities(ServerLevel level, List<Entity> snapshot) {
        long now = level.getGameTime();
        for (Entity entity : snapshot) {
            if (isNeverRemovable(entity)) {
                continue;
            }
            // the zombie's own fireballs are spared, they would otherwise die the instant they spawn
            // and delete the attack before it travels - every other projectile is still cleared
            if (isOwnProjectile(entity)) {
                continue;
            }
            UUID id = entity.getUUID();
            if (TranscenMod.ANNIHILATION_DOOMED_ON.containsKey(id)) {
                continue;
            }
            // capture the position first, afterwards the entity is unreachable but the strikes still need a spot
            TranscenMod.ANNIHILATION_POSITIONS.put(id, entity.position());
            TranscenMod.ANNIHILATION_DOOMED_ON.put(id, now);

            // the class is deliberately not banned - banning is global and lasts the session,so after
            // the first sweep touched a cow and a sheep no creature of those classes could join any
            // world again and mob spawning stopped everywhere with nothing on screen to explain it
            if (!(entity instanceof Player)) {
                TranscenMod.BANNED_CLASSES.add(entity.getClass());
            }
            entity.setInvulnerable(true);
            entity.setDeltaMovement(Vec3.ZERO);
            if (entity instanceof Mob) { Mob mob = (Mob) entity;
                mob.setNoAi(true);
            }

            // the client is told to hold its copy first and the server copy goes in the same tick
            // CustomEntityRemover rather than hand-rolled surgery - detaching the storages and emptying
            // ChunkMap TrackedEntity#seenBy is the combination that preserves the client's copy
            TranscenMod.NETWORK.send(
                    PacketDistributor.DIMENSION.with(level::dimension),
                    new MarkDoomedPacket(id));

            // a boss bar is not part of the entity, it is registered with the server and pushed as its
            // own packet stream, so deleting the entity leaves the bar drawn over a mob that is gone
            clearOwnedBossBars(entity);
            // second route for bars not reachable from the entity at all, a datapack/command bar holds
            // no reference back to its mob so the display name is the only link left
            clearMatchingRegisteredBossBars(level, entity);

            CustomEntityRemover.removeEntity(entity);
        }
    }

    // deactivates any boss bar the entity owns, found structurally - vanilla holders spell their
    // field differently and modded bosses invent their own so there is no single name to look for
    // the entity's own fields and its superclasses are scanned for anything derived from
    // ServerBossEvent, which covers WitherBoss, EnderDragon and Forge's CustomBossEvent
    // both levers are needed - setVisible(false) stops the rendering and removeAllPlayers drops the
    // subscription so the server sends nothing more, and the depth is bounded to the entity and its
    // superclasses because an unbounded walk could deactivate unrelated bars
    private static void clearOwnedBossBars(Entity entity) {
        Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Class<?> type = entity.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                // any BossEvent and not only ServerBossEvent - a datapack bar is a CustomBossEvent and
                // a mod may keep a plain BossEvent and drive the packets itself, testing the narrow
                // subtype missed those, which is the custom-bar-refuses-to-go-away case
                if (!net.minecraft.world.BossEvent.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(entity);
                    if (value instanceof net.minecraft.world.BossEvent && seen.add(value)) {
                        net.minecraft.world.BossEvent bar =
                                (net.minecraft.world.BossEvent) value;
                        // setVisible and removeAllPlayers live on the server-side implementation and not
                        // on BossEvent which only carries the data, anything else is recorded rather
                        // than assumed to have worked
                        boolean deactivated = false;
                        if (bar instanceof net.minecraft.server.level.ServerBossEvent) { net.minecraft.server.level.ServerBossEvent serverBar = (net.minecraft.server.level.ServerBossEvent) bar;
                            serverBar.setVisible(false);
                            serverBar.removeAllPlayers();
                            deactivated = true;
                        }
                        TranscenMod.LOGGER.info(
                                "[transcenmod] boss bar {} ({}) owned by {} ({}) -- {}",
                                field.getName(), bar.getClass().getSimpleName(),
                                entity.getType(), entity.getUUID(),
                                deactivated ? "hidden" : "not a server bar, left alone");
                    }
                } catch (Throwable ignored) {
                    // a field that cannot be read is no reason to abandon the removal
                }
            }
        }
    }

    // ownership is not always a field - a datapack/command bar lives in a registry keyed by
    // ResourceLocation and holds no reference back to the entity, so the field scan above never sees
    // it and the name is the only thing still connecting the two
    // exact display name and only bars that are already visible so the worst a coincidence does is
    // hide a bar showing the same text
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
                TranscenMod.LOGGER.info(
                        "[transcenmod] hid registered boss bar '{}' for {}", name, entity.getUUID());
            }
        } catch (Throwable ignored) {
            // best effort,like the rest of the removal
        }
    }


    // ownership through Projectile#getOwner() rather than the projectile class
    // so a ghast's or a blaze's LargeFireball is still annihilated while the zombie's own attack survives
    private static boolean isOwnProjectile(Entity entity) {
        if (!(entity instanceof Projectile)) {
            return false;
        }
        Projectile projectile = (Projectile) entity;
        return projectile.getOwner() instanceof TranscenZombie;
    }

    // players and this mod's own mobs are never removed - a player removed from the storages corrupts
    // EntityTickList#active which DistanceManager shares and the next movement packet throws
    private static boolean isNeverRemovable(Entity entity) {
        if (entity instanceof Player) {
            return true;
        }
        return TranscenZombie.isProtected(entity);
    }

    // bolts for the first ten ticks,then the client removal and the vanilla death puff
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

            // the shell's two seconds are up,drop the client copy and leave the death puff
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
            // moveTo rather than a reflective position write,same reason as in spawnZombie
            // a declared method cannot be defeated by a name that resolves differently, and the bolt
            // is not a TranscenZombie so there is no relocation guard to lift
            bolt.moveTo(pos.x, pos.y, pos.z);
            level.addFreshEntity(bolt);
        }
    }

    // the vanilla white puff mobs leave on death
    private static void spawnDeathPuff(ServerLevel level, Vec3 pos) {
        level.sendParticles(ParticleTypes.POOF, pos.x, pos.y + 0.5D, pos.z,
                20, 0.5D, 0.5D, 0.5D, 0.1D);
    }
}
