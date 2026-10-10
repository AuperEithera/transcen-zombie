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

public class TranscenEvents
{

    private static final int STRIKE_TICKS = 10;
    private static final int BOLTS_PER_TICK = 2;
    private static final int CLIENT_REMOVE_DELAY_TICKS = 40;

    private static boolean sweeping;
    private static long lastSweptTick = Long.MIN_VALUE;

    private static Boolean lastHudActive;
    private static float lastHudProgress = -1.0F;

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityTickHighest(LivingEvent.LivingTickEvent event)
    {
        event.setCanceled(true);

        Entity entity = event.getEntity();

        if (entity.getLevel().isClientSide
                && com.example.transcenmod.client.DoomedShell.isDoomed(entity.getUUID()))
                {
            return;
        }

        executeCoreLogic(entity);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityTickLowest(LivingEvent.LivingTickEvent event)
    {
        event.setCanceled(false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onLevelTickHighest(TickEvent.LevelTickEvent event)
    {
        if (event.phase != TickEvent.Phase.START)
        {
            return;
        }
        if (event.level instanceof ServerLevel)
        {
            ServerLevel level = (ServerLevel) event.level;
            guardedRevive(level);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLevelTickLowest(TickEvent.LevelTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        if (event.level instanceof ServerLevel)
        {
            ServerLevel level = (ServerLevel) event.level;
            guardedRevive(level);
            forceNightAndStorm(level);
        }
    }

    private static final long LOCKED_TIME = 18000L;

    private static void forceNightAndStorm(ServerLevel level)
    {
        boolean present = false;
        for (Entity entity : snapshot(level))
        {
            if (entity instanceof TranscenZombie)
            {
                present = true;
                break;
            }
        }
        if (!present && !TranscenMod.ZOMBIE_POSITIONS.isEmpty())
        {
            present = true;
        }
        if (!present)
        {
            return;
        }
        if (level.getDayTime() != LOCKED_TIME)
        {
            level.setDayTime(LOCKED_TIME);
        }
        if (!level.isRaining() || !level.isThundering())
        {
            level.setWeatherParameters(0, 6000, true, true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onPlayerTickHighest(TickEvent.PlayerTickEvent event)
    {
        executeCoreLogic(event.player);
        keepEggHolderAlive(event.player);
    }


    public static void toggleDefence(ServerPlayer player, ServerLevel level)
    {
        long now = level.getGameTime();
        if (lastWipeTick != Long.MIN_VALUE && now - lastWipeTick < WIPE_COOLDOWN_TICKS)
        {
            return;
        }
        lastWipeTick = now;

        boolean on = !TranscenMod.defenceEnabled;

        TranscenMod.defenceEnabled = on;
        if (!on)
        {
            clearAllZombies(level);
        }

        player.sendSystemMessage(Component.literal(
                on ? "\u00a7aDefence enabled"
                   : "\u00a7cDefence disabled"));
    }

    public static void clearAllZombies(ServerLevel level)
    {
        TranscenMod.defenceEnabled = false;

        int deep = 0;
        for (int pass = 0; pass < 8; pass++)
        {
            List<Entity> batch = new ArrayList<>();
            for (Entity entity : level.getAllEntities())
            {
                if (entity instanceof TranscenZombie)
                {
                    batch.add(entity);
                }
            }
            if (batch.isEmpty())
            {
                break;
            }
            for (Entity entity : batch)
            {
                try
                {
                    CustomEntityRemover.removeEntity(entity);
                    deep++;
                }
                catch (Throwable failure)
                {
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

        lastSweptTick = Long.MIN_VALUE;
        lastHudActive = null;
        lastHudProgress = -1.0F;

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

    private static final long WIPE_COOLDOWN_TICKS = 5L;

    private static long lastWipeTick = Long.MIN_VALUE;

    private static volatile boolean encounterEnded;

    public static void endEncounter()
    {
        encounterEnded = true;
    }

    public static void restartEncounter()
    {
        encounterEnded = false;
    }

    private static boolean isUnusablePosition(ServerLevel level, Vec3 pos)
    {
        if (pos == null)
        {
            return true;
        }
        if (pos.x == 0.0D && pos.y == 0.0D && pos.z == 0.0D)
        {
            return true;
        }
        try
        {
            return !level.hasChunkAt(new net.minecraft.core.BlockPos(
                    net.minecraft.util.Mth.floor(pos.x),
                    net.minecraft.util.Mth.floor(pos.y),
                    net.minecraft.util.Mth.floor(pos.z)));
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    private static Vec3 positionNearPlayer(ServerLevel level, Vec3 fallback)
    {
        for (net.minecraft.server.level.ServerPlayer player : level.players())
        {
            return new Vec3(player.getX() + 2.0D, player.getY(), player.getZ() + 2.0D);
        }
        if (fallback != null && level.hasChunkAt(level.getSharedSpawnPos()))
        {
            net.minecraft.core.BlockPos spawn = level.getSharedSpawnPos();
            return new Vec3(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D);
        }
        return null;
    }

    private static void keepEggHolderAlive(Player player)
    {
        if (player.getLevel().isClientSide || player.isCreative() || player.isSpectator())
        {
            return;
        }
        if (!isCarryingEgg(player))
        {
            return;
        }
        if (player.getHealth() < EGG_HOLDER_HEALTH)
        {
            player.setHealth(EGG_HOLDER_HEALTH);
        }
        player.setAirSupply(player.getMaxAirSupply());
        if (player.isOnFire())
        {
            player.clearFire();
        }
    }

    public static boolean isCarryingEgg(Player player)
    {
        long tick = player.getLevel().getGameTime();
        if (player.getUUID().equals(eggCachePlayer) && tick == eggCacheTick)
        {
            return eggCacheValue;
        }
        boolean carried = false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
        {
            if (player.getInventory().getItem(slot).is(TranscenMod.TRANSCEN_SPAWN_EGG.get()))
            {
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

    public static boolean isHoldingEgg(Player player)
    {
        return player.getMainHandItem().is(TranscenMod.TRANSCEN_SPAWN_EGG.get())
                || player.getOffhandItem().is(TranscenMod.TRANSCEN_SPAWN_EGG.get());
    }

    public static float eggHolderHealth()
    {
        return EGG_HOLDER_HEALTH;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingHurtLowest(LivingHurtEvent event)
    {
        if (!(event.getEntity() instanceof Player))
        {
            return;
        }
        Player player = (Player) event.getEntity();
        if (player.getLevel().isClientSide || !isCarryingEgg(player))
        {
            return;
        }
        TranscenMod.LOGGER.info(
                "[transcenmod] refused {} damage to egg holder (amount {})",
                event.getSource().getMsgId(), event.getAmount());
        event.setCanceled(true);
        player.setHealth(EGG_HOLDER_HEALTH);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDamageLowest(LivingDamageEvent event)
    {
        if (!(event.getEntity() instanceof Player))
        {
            return;
        }
        Player player = (Player) event.getEntity();
        if (player.getLevel().isClientSide || !isCarryingEgg(player))
        {
            return;
        }
        player.setHealth(EGG_HOLDER_HEALTH);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDeathLowest(LivingDeathEvent event)
    {
        if (!(event.getEntity() instanceof Player))
        {
            return;
        }
        Player player = (Player) event.getEntity();
        if (player.getLevel().isClientSide || !isCarryingEgg(player))
        {
            return;
        }
        player.setHealth(EGG_HOLDER_HEALTH);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityJoinHighest(EntityJoinLevelEvent event)
    {
        Entity joining = event.getEntity();

        if (joining instanceof TranscenZombie)
        {
            restartEncounter();
        }

        if (joining instanceof Player)
        {
            return;
        }
        if (TranscenMod.BANNED_CLASSES.contains(joining.getClass()))
        {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityJoinLowest(EntityJoinLevelEvent event)
    {
        if (!(event.getEntity() instanceof TranscenZombie))
        {
            return;
        }
        TranscenZombie zombie = (TranscenZombie) event.getEntity();
        event.setCanceled(false);

        restartEncounter();
        if (!event.getLevel().isClientSide() && !zombie.isRetired())
        {
            TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUUID(),
                    new Vec3(zombie.getX(), zombie.getY(), zombie.getZ()));
        }
    }

    public static void executeCoreLogic(Entity trigger)
    {
        if (!(trigger.getLevel() instanceof ServerLevel))
        {
            return;
        }
        ServerLevel level = (ServerLevel) trigger.getLevel();
        if (level.isClientSide)
        {
            return;
        }
        if (!TranscenMod.defenceEnabled)
        {
            return;
        }
        long tick = level.getGameTime();
        if (sweeping || tick == lastSweptTick)
        {
            return;
        }
        sweeping = true;
        lastSweptTick = tick;
        try
        {
            sweep(level);
        }
        finally
        {
            sweeping = false;
        }
    }

    private static void sweep(ServerLevel level)
    {
        beginTickSpawnBudget();

        List<Entity> snapshot = snapshot(level);

        boolean zombieOwned = TranscenMod.HUD_ACTIVE
                || !TranscenMod.ZOMBIE_POSITIONS.isEmpty()
                || firstZombie(snapshot) != null;

        pushHud(level, zombieOwned);

        restoreMissingZombies(level, snapshot);

        if (!zombieOwned)
        {
            advanceRemovalTimers(level);
            return;
        }

        annihilateForeignEntities(level, snapshot);
        advanceRemovalTimers(level);
    }

    private static List<Entity> snapshot(ServerLevel level)
    {
        List<Entity> out = new ArrayList<>();
        for (Entity entity : level.getAllEntities())
        {
            if (entity != null && !entity.isRemoved())
            {
                out.add(entity);
            }
        }
        return out;
    }

    private static TranscenZombie firstZombie(List<Entity> entities)
    {
        for (Entity entity : entities)
        {
            if (entity instanceof TranscenZombie)
            {
                TranscenZombie zombie = (TranscenZombie) entity;
                return zombie;
            }
        }
        return null;
    }

    private static void pushHud(ServerLevel level, boolean zombieOwned)
    {
        boolean active = zombieOwned;
        float progress = 1.0F;
        if (lastHudActive != null && lastHudActive == active
                && Math.abs(lastHudProgress - progress) < 0.001F)
                {
            return;
        }
        lastHudActive = active;
        lastHudProgress = progress;
        TranscenMod.NETWORK.send(
                PacketDistributor.DIMENSION.with(level::dimension),
                new HudSyncPacket(active, progress));
    }

    private static boolean reviveFailureLogged;

    private static final Set<UUID> SPAWN_PENDING = new HashSet<>();

    // best effort, and it must never throw out of an event handler - Forge's EventBus then tries to
    // report the exception and a log4j conflict in a modpack turns that report into a fatal
    // LinkageError that hides the real cause, printStackTrace rather than the logger for that reason
    private static void guardedRevive(ServerLevel level)
    {
        try
        {
            reviveNow(level);
        }
        catch (Throwable failure)
        {
            if (!reviveFailureLogged)
            {
                reviveFailureLogged = true;
                failure.printStackTrace();
            }
        }
    }

    private static void reviveNow(ServerLevel level)
    {
        restoreMissingZombies(level, snapshot(level));
    }

    private static final int MAX_SPAWNS_PER_TICK = 4;

    private static final int MAX_ZOMBIES = 1;

    private static int spawnsThisTick;

    private static void beginTickSpawnBudget()
    {
        spawnsThisTick = 0;
    }

    private static void restoreMissingZombies(ServerLevel level, List<Entity> snapshot)
    {
        if (!TranscenMod.defenceEnabled)
        {
            return;
        }
        if (encounterEnded)
        {
            return;
        }
        Set<UUID> present = new HashSet<>();
        for (Entity entity : snapshot)
        {
            if (entity instanceof TranscenZombie)
            {
                TranscenZombie zombie = (TranscenZombie) entity;
                present.add(zombie.getUUID());
                TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUUID(),
                        new Vec3(zombie.getX(), zombie.getY(), zombie.getZ()));
                TranscenMod.ZOMBIE_YAW.put(zombie.getUUID(), zombie.getYRot());
                TranscenMod.ZOMBIE_PITCH.put(zombie.getUUID(), zombie.getXRot());
            }
        }

        int aliveCount = 0;
        for (Entity entity : snapshot)
        {
            if (entity instanceof TranscenZombie)
            {
                aliveCount++;
            }
        }
        if (aliveCount >= MAX_ZOMBIES)
        {
            return;
        }

        for (Map.Entry<UUID, Vec3> entry : TranscenMod.ZOMBIE_POSITIONS.entrySet())
        {
            UUID id = entry.getKey();
            if (SPAWN_PENDING.contains(id))
            {
                continue;
            }
            if (present.contains(id) || level.getEntity(id) != null)
            {
                continue;
            }
            if (spawnsThisTick >= MAX_SPAWNS_PER_TICK)
            {
                return;
            }
            Vec3 pos = entry.getValue();
            float yaw = TranscenMod.ZOMBIE_YAW.getOrDefault(id, 0.0F);
            float pitch = TranscenMod.ZOMBIE_PITCH.getOrDefault(id, 0.0F);

            if (isUnusablePosition(level, pos))
            {
                pos = positionNearPlayer(level, pos);
                if (pos == null)
                {
                    continue;
                }
                TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
            }

            final Vec3 target = pos;
            SPAWN_PENDING.add(id);
            spawnsThisTick++;
            level.getServer().execute(() -> {
                try
                {
                    spawnZombie(level, id, target, yaw, pitch);
                }
                finally
                {
                    SPAWN_PENDING.remove(id);
                }
            });
        }
        if (present.size() >= MAX_ZOMBIES)
        {
            TranscenMod.LOGGER.warn(
                    "[transcenmod] revival: {} zombies alive, at the {}-zombie cap -- repair is not converging",
                    present.size(), MAX_ZOMBIES);
        }
    }

    public static void spawnZombie(ServerLevel level, Vec3 pos)
    {
        spawnZombie(level, UUID.randomUUID(), pos, 0.0F, 0.0F);
    }

    private static void releaseClaimedUuid(ServerLevel level, UUID id)
    {
        try
        {
            Object manager = com.example.transcenmod.util.ReflectionAccess
                    .getFieldRelaxed(level, "entityManager");
            for (Class<?> type = manager.getClass(); type != null && type != Object.class;
                    type = type.getSuperclass())
                    {
                for (Field field : type.getDeclaredFields())
                {
                    if (!java.util.Set.class.isAssignableFrom(field.getType()))
                    {
                        continue;
                    }
                    try
                    {
                        field.setAccessible(true);
                        Object value = field.get(manager);
                        if (!(value instanceof java.util.Set))
                        {
                            continue;
                        }
                        java.util.Set<?> set = (java.util.Set<?>) value;
                        if (!set.contains(id))
                        {
                            continue;
                        }
                        set.remove(id);
                        TranscenMod.LOGGER.info(
                                "[transcenmod] released claimed uuid {} from {}.{} (size now {})",
                                id, type.getSimpleName(), field.getName(), set.size());
                    }
                    catch (Throwable notApplicable)
                    {
                    }
                }
            }
        }
        catch (Throwable failure)
        {
            TranscenMod.LOGGER.warn("[transcenmod] could not release claimed uuid {}", id, failure);
        }
    }

    private static void spawnZombie(ServerLevel level, UUID id, Vec3 pos, float yaw, float pitch)
    {
        TranscenZombie zombie = new TranscenZombie(TranscenMod.TRANSCEN_ZOMBIE.get(), level);
        zombie.setUUID(id);

        zombie.withRelocationAllowed(() -> zombie.moveTo(pos.x, pos.y, pos.z, yaw, pitch));

        zombie.setYHeadRot(yaw);
        zombie.setYBodyRot(yaw);

        TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
        TranscenMod.ZOMBIE_YAW.put(id, yaw);
        TranscenMod.ZOMBIE_PITCH.put(id, pitch);

        releaseClaimedUuid(level, id);

        boolean added = level.addFreshEntity(zombie);

        int byClass = level.getEntitiesOfClass(
                TranscenZombie.class, level.getWorldBorder().getCollisionShape().bounds()).size();
    }

    private static void annihilateForeignEntities(ServerLevel level, List<Entity> snapshot)
    {
        long now = level.getGameTime();
        for (Entity entity : snapshot)
        {
            if (isNeverRemovable(entity))
            {
                continue;
            }
            if (isOwnProjectile(entity))
            {
                continue;
            }
            UUID id = entity.getUUID();
            if (TranscenMod.ANNIHILATION_DOOMED_ON.containsKey(id))
            {
                continue;
            }
            TranscenMod.ANNIHILATION_POSITIONS.put(id, entity.position());
            TranscenMod.ANNIHILATION_DOOMED_ON.put(id, now);

            if (!(entity instanceof Player))
            {
                TranscenMod.BANNED_CLASSES.add(entity.getClass());
            }
            entity.setInvulnerable(true);
            entity.setDeltaMovement(Vec3.ZERO);
            if (entity instanceof Mob)
            {
                Mob mob = (Mob) entity;
                mob.setNoAi(true);
            }

            TranscenMod.NETWORK.send(
                    PacketDistributor.DIMENSION.with(level::dimension),
                    new MarkDoomedPacket(id));

            clearOwnedBossBars(entity);
            clearMatchingRegisteredBossBars(level, entity);

            CustomEntityRemover.removeEntity(entity);
        }
    }

    private static void clearOwnedBossBars(Entity entity)
    {
        Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Class<?> type = entity.getClass(); type != null && type != Object.class;
                type = type.getSuperclass())
                {
            for (Field field : type.getDeclaredFields())
            {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                {
                    continue;
                }
                if (!net.minecraft.world.BossEvent.class.isAssignableFrom(field.getType()))
                {
                    continue;
                }
                try
                {
                    field.setAccessible(true);
                    Object value = field.get(entity);
                    if (value instanceof net.minecraft.world.BossEvent && seen.add(value))
                    {
                        net.minecraft.world.BossEvent bar =
                                (net.minecraft.world.BossEvent) value;
                        boolean deactivated = false;
                        if (bar instanceof net.minecraft.server.level.ServerBossEvent)
                        {
                            net.minecraft.server.level.ServerBossEvent serverBar = (net.minecraft.server.level.ServerBossEvent) bar;
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
                }
                catch (Throwable ignored)
                {
                }
            }
        }
    }

    private static void clearMatchingRegisteredBossBars(ServerLevel level, Entity entity)
    {
        try
        {
            Object events = level.getServer().getCustomBossEvents();
            if (events == null)
            {
                return;
            }
            java.lang.reflect.Method getEvents = events.getClass().getMethod("getEvents");
            Object list = getEvents.invoke(events);
            if (!(list instanceof java.util.Collection))
            {
                return;
            }
            java.util.Collection<?> bars = (java.util.Collection<?>) list;
            String name = entity.getDisplayName().getString();
            for (Object candidate : bars)
            {
                if (!(candidate instanceof net.minecraft.server.bossevents.CustomBossEvent))
                {
                    continue;
                }
                net.minecraft.server.bossevents.CustomBossEvent bar = (net.minecraft.server.bossevents.CustomBossEvent) candidate;
                if (!bar.isVisible() || !bar.getName().getString().equals(name))
                {
                    continue;
                }
                bar.setVisible(false);
                TranscenMod.LOGGER.info(
                        "[transcenmod] hid registered boss bar '{}' for {}", name, entity.getUUID());
            }
        }
        catch (Throwable ignored)
        {
        }
    }


    private static boolean isOwnProjectile(Entity entity)
    {
        if (!(entity instanceof Projectile))
        {
            return false;
        }
        Projectile projectile = (Projectile) entity;
        return projectile.getOwner() instanceof TranscenZombie;
    }

    // players and this mod's own mobs are never removed - a player removed from the storages corrupts
    // EntityTickList#active which DistanceManager shares and the next movement packet throws
    private static boolean isNeverRemovable(Entity entity)
    {
        if (entity instanceof Player)
        {
            return true;
        }
        return TranscenZombie.isProtected(entity);
    }

    private static void advanceRemovalTimers(ServerLevel level)
    {
        long now = level.getGameTime();
        Iterator<Map.Entry<UUID, Long>> it = TranscenMod.ANNIHILATION_DOOMED_ON.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<UUID, Long> entry = it.next();
            UUID id = entry.getKey();
            long elapsed = now - entry.getValue();
            Vec3 pos = TranscenMod.ANNIHILATION_POSITIONS.getOrDefault(id, Vec3.ZERO);

            if (elapsed < STRIKE_TICKS)
            {
                strikeAt(level, pos);
                continue;
            }
            if (elapsed < CLIENT_REMOVE_DELAY_TICKS)
            {
                continue;
            }

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

    private static void strikeAt(ServerLevel level, Vec3 pos)
    {
        for (int i = 0; i < BOLTS_PER_TICK; i++)
        {
            TranscenLightningBolt bolt =
                    new TranscenLightningBolt(TranscenMod.TRANSCEN_LIGHTNING.get(), level);
            bolt.moveTo(pos.x, pos.y, pos.z);
            level.addFreshEntity(bolt);
        }
    }

    private static void spawnDeathPuff(ServerLevel level, Vec3 pos)
    {
        level.sendParticles(ParticleTypes.POOF, pos.x, pos.y + 0.5D, pos.z,
                20, 0.5D, 0.5D, 0.5D, 0.1D);
    }
}
