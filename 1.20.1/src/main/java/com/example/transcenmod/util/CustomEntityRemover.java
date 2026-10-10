package com.example.transcenmod.util;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CustomEntityRemover
{

    private CustomEntityRemover() {}

    @SuppressWarnings("unchecked")
    public static void removeEntity(Entity entity)
    {
        if (entity.level() instanceof ServerLevel)
        {
            ServerLevel serverLevel = (ServerLevel) entity.level();
            detachFromClientTracking(serverLevel, entity);
        }
        removeEntityInternal(entity);
    }

    private static void detachFromClientTracking(ServerLevel level, Entity entity)
    {
        try
        {
            ServerChunkCache source = level.getChunkSource();
            if (source == null || source.chunkMap == null)
            {
                return;
            }
            Object entityMap = getFieldAny(source.chunkMap, "entityMap", "f_140150_");
            if (!(entityMap instanceof Map))
            {
                return;
            }
            Map<?, ?> tracked = (Map<?, ?>) entityMap;
            Object trackedEntity = tracked.get(entity.getId());
            if (trackedEntity == null)
            {
                return;
            }
            Object seenBy = getFieldAny(trackedEntity, "seenBy", "f_140475_");
            if (seenBy instanceof Set)
            {
                Set<?> set = (Set<?>) seenBy;
                set.clear();
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private static void removeEntityInternal(Entity entity) {        try
    {
            entity.setRemoved(Entity.RemovalReason.KILLED);
            entity.stopRiding();
            entity.getPassengers().forEach(Entity::stopRiding);

            if (entity.level().isClientSide)
            {
                ClientLevel clientLevel = (ClientLevel) entity.level();
                Object manager = ReflectionAccess.getFieldRelaxed(clientLevel, "entityStorage");
                long currentSectionKey = SectionPos.asLong(entity.blockPosition());

                Object sectionStorage = ReflectionAccess.getFieldRelaxed(manager, "sectionStorage");
                Method getSection = sectionStorage.getClass().getMethod("getSection", long.class);
                Object currentSection = getSection.invoke(sectionStorage, currentSectionKey);

                if (currentSection != null)
                {
                    Object storage = ReflectionAccess.getFieldRelaxed(currentSection, "storage");
                    Map<Class<?>, List<Entity>> byClass =
                            (Map<Class<?>, List<Entity>>) ReflectionAccess.getFieldRelaxed(storage, "byClass");
                    for (Map.Entry<Class<?>, List<Entity>> entry : byClass.entrySet())
                    {
                        if (entry.getKey().isInstance(entity))
                        {
                            entry.getValue().remove(entity);
                        }
                    }

                    Object visibility = currentSection.getClass().getMethod("getStatus").invoke(currentSection);
                    boolean isTicking = (boolean) visibility.getClass().getMethod("isTicking").invoke(visibility);
                    if (isTicking || entity.isAlwaysTicking())
                    {
                        Object tickingEntities = ReflectionAccess.getFieldRelaxed(clientLevel, "tickingEntities");
                        tickingEntities.getClass().getMethod("ensureActiveIsNotIterated").invoke(tickingEntities);
                        Object active = ReflectionAccess.getFieldRelaxed(tickingEntities, "active");
                        if (active instanceof Set)
                        {
                            Set<?> set = (Set<?>) active;
                            set.remove(entity.getId());
                        }
                        else if (active instanceof it.unimi.dsi.fastutil.ints.Int2ObjectMap)
                        {
                            it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> map =
                                    (it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>) active;
                            map.remove(entity.getId());
                        }
                    }
                }

                entity.unRide();
                List<Player> players = (List<Player>) ReflectionAccess.getFieldRelaxed(clientLevel, "players");
                players.remove(entity);

                Field isAddedToWorld = Entity.class.getDeclaredField("isAddedToWorld");
                isAddedToWorld.setAccessible(true);
                isAddedToWorld.set(entity, false);

                Object entityStorage = ReflectionAccess.getFieldRelaxed(manager, "entityStorage");
                Map<UUID, Entity> byUuid = (Map<UUID, Entity>) ReflectionAccess.getFieldRelaxed(entityStorage, "byUuid");
                Map<Integer, Entity> byId = (Map<Integer, Entity>) ReflectionAccess.getFieldRelaxed(entityStorage, "byId");
                byUuid.remove(entity.getUUID());
                byId.remove(entity.getId());

                Field levelCallback = Entity.class.getDeclaredField("levelCallback");
                levelCallback.setAccessible(true);
                levelCallback.set(entity, EntityInLevelCallback.NULL);

            }
            else
            {
                ServerLevel serverLevel = (ServerLevel) entity.level();
                Object manager = ReflectionAccess.getFieldRelaxed(serverLevel, "entityManager");
                long currentSectionKey = SectionPos.asLong(entity.blockPosition());

                Object sectionStorage = ReflectionAccess.getFieldRelaxed(manager, "sectionStorage");
                Method getSection = sectionStorage.getClass().getMethod("getSection", long.class);
                Object currentSection = getSection.invoke(sectionStorage, currentSectionKey);

                if (currentSection != null)
                {
                    Object storage = ReflectionAccess.getFieldRelaxed(currentSection, "storage");
                    Map<Class<?>, List<Entity>> byClass =
                            (Map<Class<?>, List<Entity>>) ReflectionAccess.getFieldRelaxed(storage, "byClass");
                    for (Map.Entry<Class<?>, List<Entity>> entry : byClass.entrySet())
                    {
                        if (entry.getKey().isInstance(entity))
                        {
                            entry.getValue().remove(entity);
                        }
                    }
                }

                if (entity instanceof ServerPlayer)
                {
                    ServerPlayer serverplayer = (ServerPlayer) entity;
                    List<ServerPlayer> players = (List<ServerPlayer>) ReflectionAccess.getFieldRelaxed(serverLevel, "players");
                    players.remove(serverplayer);
                    serverLevel.updateSleepingPlayerList();
                }

                Field isAddedToWorld = Entity.class.getDeclaredField("isAddedToWorld");
                isAddedToWorld.setAccessible(true);
                isAddedToWorld.set(entity, false);

                Object visibleEntityStorage = ReflectionAccess.getFieldRelaxed(manager, "visibleEntityStorage");
                visibleEntityStorage.getClass().getMethod("remove", Object.class).invoke(visibleEntityStorage, entity);
                serverLevel.getScoreboard().entityRemoved(entity);

                Field levelCallback = Entity.class.getDeclaredField("levelCallback");
                levelCallback.setAccessible(true);
                levelCallback.set(entity, EntityInLevelCallback.NULL);
            }

            Field valid = Entity.class.getDeclaredField("valid");
            valid.setAccessible(true);
            valid.set(entity, false);

            CapabilityDispatcher disp = (CapabilityDispatcher) ReflectionAccess.getFieldRelaxed(entity, "capabilities");
            if (disp != null)
            {
            }

            if (entity instanceof LivingEntity)
            {
                LivingEntity living = (LivingEntity) entity;
                living.getBrain().clearMemories();
            }

        }
        catch (Exception ignored)
        {
        }
    }

    private static Object getFieldAny(Object target, String... candidates)
    {
        for (String name : candidates)
        {
            try
            {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            }
            catch (NoSuchFieldException ignored)
            {
            }
            catch (Exception ignored)
            {
                return null;
            }
        }
        return null;
    }

}
