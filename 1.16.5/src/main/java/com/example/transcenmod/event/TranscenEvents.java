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

// one sweep per server tick,executeCoreLogic is reachable from every living tick and walks the whole entity list
// without the latch that is tens of thousands of traversals a tick and an OutOfMemoryError
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
    public static void onEntityTickHighest(LivingEvent.LivingUpdateEvent event)
    {
        event.setCanceled(true);

        Entity entity = event.getEntity();

        if (entity.getEntityWorld().isRemote
                && com.example.transcenmod.client.DoomedShell.isDoomed(entity.getUniqueID()))
                {
            return;
        }

        executeCoreLogic(entity);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityTickLowest(LivingEvent.LivingUpdateEvent event)
    {
        event.setCanceled(false);
    }


    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onLevelTickHighest(TickEvent.WorldTickEvent event)
    {
        if (event.phase != TickEvent.Phase.START)
        {
            return;
        }
        if (event.world instanceof ServerWorld)
        {
            ServerWorld level = (ServerWorld) event.world;
            guardedRevive(level);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLevelTickLowest(TickEvent.WorldTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        if (event.world instanceof ServerWorld)
        {
            ServerWorld level = (ServerWorld) event.world;
            guardedRevive(level);
            forceNightAndStorm(level);
        }
    }

    private static final long LOCKED_TIME = 18000L;

    private static void forceNightAndStorm(ServerWorld level)
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
            level.setWeather(0, 6000, true, true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onPlayerTickHighest(TickEvent.PlayerTickEvent event)
    {
        executeCoreLogic(event.player);
        keepEggHolderAlive(event.player);
    }


    public static void toggleDefence(ServerPlayerEntity player, ServerWorld level)
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

        player.sendMessage(new StringTextComponent(
                on ? "\u00a7aDefence enabled"
                   : "\u00a7cDefence disabled"), java.util.UUID.randomUUID());
    }

    public static void clearAllZombies(ServerWorld level)
    {
        TranscenMod.defenceEnabled = false;

        int deep = 0;
        for (int pass = 0; pass < 8; pass++)
        {
            List<Entity> batch = new ArrayList<>();
            for (Entity entity : level.getEntitiesIteratable())
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
                    entity.remove(false);
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
                PacketDistributor.DIMENSION.with(level::getDimensionKey),
                new HudSyncPacket(false, 0.0F));
        TranscenMod.NETWORK.send(
                PacketDistributor.DIMENSION.with(level::getDimensionKey),
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

    private static boolean isUnusablePosition(ServerWorld level, Vector3d pos)
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
            return !level.isBlockLoaded(new net.minecraft.util.math.BlockPos(
                    net.minecraft.util.math.MathHelper.floor(pos.x),
                    net.minecraft.util.math.MathHelper.floor(pos.y),
                    net.minecraft.util.math.MathHelper.floor(pos.z)));
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    private static Vector3d positionNearPlayer(ServerWorld level, Vector3d fallback)
    {
        for (net.minecraft.entity.player.ServerPlayerEntity player : level.getPlayers())
        {
            return new Vector3d(player.getPosX() + 2.0D, player.getPosY(), player.getPosZ() + 2.0D);
        }
        if (fallback != null && level.isBlockLoaded(level.getSpawnPoint()))
        {
            net.minecraft.util.math.BlockPos spawn = level.getSpawnPoint();
            return new Vector3d(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D);
        }
        return null;
    }

    private static void keepEggHolderAlive(PlayerEntity player)
    {
        if (player.getEntityWorld().isRemote || player.isCreative() || player.isSpectator())
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
        player.setAir(player.getMaxAir());
        if (player.isBurning())
        {
            player.extinguish();
        }
    }

    public static boolean isCarryingEgg(PlayerEntity player)
    {
        long tick = player.getEntityWorld().getGameTime();
        if (player.getUniqueID().equals(eggCachePlayer) && tick == eggCacheTick)
        {
            return eggCacheValue;
        }
        boolean carried = false;
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++)
        {
            if (player.inventory.getStackInSlot(slot).getItem() == TranscenMod.TRANSCEN_SPAWN_EGG.get())
            {
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

    public static boolean isHoldingEgg(PlayerEntity player)
    {
        return player.getHeldItemMainhand().getItem() == TranscenMod.TRANSCEN_SPAWN_EGG.get()
                || player.getHeldItemOffhand().getItem() == TranscenMod.TRANSCEN_SPAWN_EGG.get();
    }

    public static float eggHolderHealth()
    {
        return EGG_HOLDER_HEALTH;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingHurtLowest(LivingHurtEvent event)
    {
        if (!(event.getEntity() instanceof PlayerEntity))
        {
            return;
        }
        PlayerEntity player = (PlayerEntity) event.getEntity();
        if (player.getEntityWorld().isRemote || !isCarryingEgg(player))
        {
            return;
        }
        TranscenMod.LOGGER.info(
                "[transcenmod] refused {} damage to egg holder (amount {})",
                event.getSource().getDamageType(), event.getAmount());
        event.setCanceled(true);
        player.setHealth(EGG_HOLDER_HEALTH);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDamageLowest(LivingDamageEvent event)
    {
        if (!(event.getEntity() instanceof PlayerEntity))
        {
            return;
        }
        PlayerEntity player = (PlayerEntity) event.getEntity();
        if (player.getEntityWorld().isRemote || !isCarryingEgg(player))
        {
            return;
        }
        player.setHealth(EGG_HOLDER_HEALTH);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDeathLowest(LivingDeathEvent event)
    {
        if (!(event.getEntity() instanceof PlayerEntity))
        {
            return;
        }
        PlayerEntity player = (PlayerEntity) event.getEntity();
        if (player.getEntityWorld().isRemote || !isCarryingEgg(player))
        {
            return;
        }
        player.setHealth(EGG_HOLDER_HEALTH);
        event.setCanceled(true);
    }


    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityJoinHighest(EntityJoinWorldEvent event)
    {
        Entity joining = event.getEntity();

        if (joining instanceof TranscenZombie)
        {
            restartEncounter();
        }

        if (joining instanceof PlayerEntity)
        {
            return;
        }
        if (TranscenMod.BANNED_CLASSES.contains(joining.getClass()))
        {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityJoinLowest(EntityJoinWorldEvent event)
    {
        if (!(event.getEntity() instanceof TranscenZombie))
        {
            return;
        }
        TranscenZombie zombie = (TranscenZombie) event.getEntity();
        event.setCanceled(false);

        restartEncounter();
        if (!event.getWorld().isRemote() && !zombie.isRetired())
        {
            TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUniqueID(),
                    new Vector3d(zombie.getPosX(), zombie.getPosY(), zombie.getPosZ()));
        }
    }


    public static void executeCoreLogic(Entity trigger)
    {
        if (!(trigger.getEntityWorld() instanceof ServerWorld))
        {
            return;
        }
        ServerWorld level = (ServerWorld) trigger.getEntityWorld();
        if (level.isRemote)
        {
            return;
        }
        // protection off means no sweep at all - the switch was cutting one path into the repair and missing the other
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

    private static void sweep(ServerWorld level)
    {
        beginTickSpawnBudget();

        List<Entity> snapshot = snapshot(level);

        // tying it to a zombie being present made the bar strobe off and on every time another mod removed the zombie
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

    private static List<Entity> snapshot(ServerWorld level)
    {
        List<Entity> out = new ArrayList<>();
        for (Entity entity : level.getEntitiesIteratable())
        {
            if (entity != null && !entity.removed)
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

    private static void pushHud(ServerWorld level, boolean zombieOwned)
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
                PacketDistributor.DIMENSION.with(level::getDimensionKey),
                new HudSyncPacket(active, progress));
    }

    private static boolean reviveFailureLogged;

    private static final Set<UUID> SPAWN_PENDING = new HashSet<>();

    private static void guardedRevive(ServerWorld level)
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

    private static void reviveNow(ServerWorld level)
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

    private static void restoreMissingZombies(ServerWorld level, List<Entity> snapshot)
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
                present.add(zombie.getUniqueID());
                TranscenMod.ZOMBIE_POSITIONS.put(zombie.getUniqueID(),
                        new Vector3d(zombie.getPosX(), zombie.getPosY(), zombie.getPosZ()));
                TranscenMod.ZOMBIE_YAW.put(zombie.getUniqueID(), zombie.rotationYaw);
                TranscenMod.ZOMBIE_PITCH.put(zombie.getUniqueID(), zombie.rotationPitch);
            }
        }

        // that query reported zero even right after a successful add on this setup, so the limit never held and every tick added another
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

        for (Map.Entry<UUID, Vector3d> entry : TranscenMod.ZOMBIE_POSITIONS.entrySet())
        {
            UUID id = entry.getKey();
            if (SPAWN_PENDING.contains(id))
            {
                continue;
            }
            if (present.contains(id) || level.getEntityByUuid(id) != null)
            {
                continue;
            }
            if (spawnsThisTick >= MAX_SPAWNS_PER_TICK)
            {
                return;
            }
            Vector3d pos = entry.getValue();
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

            final Vector3d target = pos;
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

    public static void spawnZombie(ServerWorld level, Vector3d pos)
    {
        spawnZombie(level, UUID.randomUUID(), pos, 0.0F, 0.0F);
    }


    private static void spawnZombie(ServerWorld level, UUID id, Vector3d pos, float yaw, float pitch)
    {
        TranscenZombie zombie = new TranscenZombie(TranscenMod.TRANSCEN_ZOMBIE.get(), level);
        zombie.setUniqueId(id);

        // the field write looked obvious but silently did not work on 1.19.2, 1794 zombies all at the origin
        zombie.withRelocationAllowed(() -> zombie.setLocationAndAngles(pos.x, pos.y, pos.z, yaw, pitch));

        zombie.rotationYawHead = yaw;
        zombie.renderYawOffset = yaw;

        TranscenMod.ZOMBIE_POSITIONS.put(id, pos);
        TranscenMod.ZOMBIE_YAW.put(id, yaw);
        TranscenMod.ZOMBIE_PITCH.put(id, pitch);


        boolean added = level.addEntity(zombie);
        if (added)
        {
            TranscenMod.LOGGER.info("[transcenmod] repair: re-added {}", id);
        }
    }

    private static void annihilateForeignEntities(ServerWorld level, List<Entity> snapshot)
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
            UUID id = entity.getUniqueID();
            if (TranscenMod.ANNIHILATION_DOOMED_ON.containsKey(id))
            {
                continue;
            }
            TranscenMod.ANNIHILATION_POSITIONS.put(id, entity.getPositionVec());
            TranscenMod.ANNIHILATION_DOOMED_ON.put(id, now);

            if (!(entity instanceof PlayerEntity))
            {
                TranscenMod.BANNED_CLASSES.add(entity.getClass());
            }
            entity.setInvulnerable(true);
            entity.setMotion(Vector3d.ZERO);
            if (entity instanceof MobEntity)
            {
                MobEntity mob = (MobEntity) entity;
                mob.setNoAI(true);
            }

            TranscenMod.NETWORK.send(
                    PacketDistributor.DIMENSION.with(level::getDimensionKey),
                    new MarkDoomedPacket(id));

            clearOwnedBossBars(entity);
            clearMatchingRegisteredBossBars(level, entity);

            entity.remove(false);
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
                // testing the narrow subtype missed those and the bar looked like it refused to go away
                if (!net.minecraft.world.BossInfo.class.isAssignableFrom(field.getType()))
                {
                    continue;
                }
                try
                {
                    field.setAccessible(true);
                    Object value = field.get(entity);
                    if (value instanceof net.minecraft.world.BossInfo && seen.add(value))
                    {
                        net.minecraft.world.BossInfo bar =
                                (net.minecraft.world.BossInfo) value;
                        boolean deactivated = false;
                        if (bar instanceof net.minecraft.world.server.ServerBossInfo)
                        {
                            net.minecraft.world.server.ServerBossInfo serverBar = (net.minecraft.world.server.ServerBossInfo) bar;
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
                }
                catch (Throwable ignored)
                {
                }
            }
        }
    }

    private static void clearMatchingRegisteredBossBars(ServerWorld level, Entity entity)
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
                if (!(candidate instanceof net.minecraft.world.server.ServerBossInfo))
                {
                    continue;
                }
                net.minecraft.world.server.ServerBossInfo bar = (net.minecraft.world.server.ServerBossInfo) candidate;
                if (!bar.isVisible() || !bar.getName().getString().equals(name))
                {
                    continue;
                }
                bar.setVisible(false);
                TranscenMod.LOGGER.info(
                        "[transcenmod] hid registered boss bar '{}' for {}", name, entity.getUniqueID());
            }
        }
        catch (Throwable ignored)
        {
        }
    }


    private static boolean isOwnProjectile(Entity entity)
    {
        if (!(entity instanceof ProjectileEntity))
        {
            return false;
        }
        ProjectileEntity projectile = (ProjectileEntity) entity;
        return projectile.getShooter() instanceof TranscenZombie;
    }

    // players are never removed from the storages - corrupts EntityTickList#active which DistanceManager shares
    // the next movement packet then throws inside vanilla
    private static boolean isNeverRemovable(Entity entity)
    {
        if (entity instanceof PlayerEntity)
        {
            return true;
        }
        return TranscenZombie.isProtected(entity);
    }

    private static void advanceRemovalTimers(ServerWorld level)
    {
        long now = level.getGameTime();
        Iterator<Map.Entry<UUID, Long>> it = TranscenMod.ANNIHILATION_DOOMED_ON.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<UUID, Long> entry = it.next();
            UUID id = entry.getKey();
            long elapsed = now - entry.getValue();
            Vector3d pos = TranscenMod.ANNIHILATION_POSITIONS.getOrDefault(id, Vector3d.ZERO);

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
                    PacketDistributor.DIMENSION.with(level::getDimensionKey),
                    new ClientRemovePacket(id));
            spawnDeathPuff(level, pos);
            TranscenMod.ANNIHILATION_POSITIONS.remove(id);
            it.remove();
        }
    }

    private static void strikeAt(ServerWorld level, Vector3d pos)
    {
        for (int i = 0; i < BOLTS_PER_TICK; i++)
        {
            TranscenLightningBolt bolt =
                    new TranscenLightningBolt(TranscenMod.TRANSCEN_LIGHTNING.get(), level);
            bolt.setLocationAndAngles(pos.x, pos.y, pos.z, 0.0F, 0.0F);
            level.addEntity(bolt);
        }
    }

    private static void spawnDeathPuff(ServerWorld level, Vector3d pos)
    {
        level.spawnParticle(ParticleTypes.POOF, pos.x, pos.y + 0.5D, pos.z,
                20, 0.5D, 0.5D, 0.5D, 0.1D);
    }
}
