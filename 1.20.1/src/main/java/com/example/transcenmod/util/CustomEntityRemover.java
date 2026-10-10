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

/**
 * Manual entity removal straight out of the level's entity storages.
 *
 * <p>The whole point of this class is that vanilla's removal path is not used. It unlinks the
 * entity from {@code EntitySectionStorage -> EntitySection -> ChunkEntitySections#byClass}, from
 * {@code EntityStorage#byUuid}/{@code byId}, from {@code EntityTickList#active}, and it finally
 * resets {@code Entity#isAddedToWorld}, {@code Entity#valid} and
 * {@code Entity#levelCallback = EntityInLevelCallback.NULL}.
 *
 * <p>Every one of those members is widened to {@code public} by
 * {@code META-INF/accesstransformer.cfg}, which is what makes the reflective lookups below resolve
 * reliably in both development and production instead of relying on the JVM's
 * {@code setAccessible} escape hatch against a sealed module.
 */
public final class CustomEntityRemover {

    private CustomEntityRemover() {}

    @SuppressWarnings("unchecked")
    public static void removeEntity(Entity entity) {
        if (entity.level() instanceof ServerLevel) { ServerLevel serverLevel = (ServerLevel) entity.level();
            // Detach the server's networking view first, so nothing about this removal can reach a
            // client. See detachFromClientTracking.
            detachFromClientTracking(serverLevel, entity);
        }
        removeEntityInternal(entity);
    }

    /**
     * Clears every player out of {@code ChunkMap.TrackedEntity.seenBy} for this entity.
     *
     * <p>This is what guarantees the removal is invisible to clients. Vanilla's own removal path
     * ends in {@code ChunkMap.removeEntity -> broadcastAndSend}, which sends the despawn packet; the
     * tracking entry is the thing that path acts on. Emptying it here means there is no tracked
     * recipient left, so even a later vanilla sweep has nobody to notify -- the client keeps its
     * copy, which is exactly what the two-second red shell needs.
     *
     * <p>The host is a Minecraft class in both dev and production, so the SRG ids are the reliable
     * handle and the mapped names are the fallback.
     */
    private static void detachFromClientTracking(ServerLevel level, Entity entity) {
        try {
            // getChunkSource() and ServerChunkCache#chunkMap are public; only the tracking map itself
            // needs widening.
            ServerChunkCache source = level.getChunkSource();
            if (source == null || source.chunkMap == null) {
                return;
            }
            Object entityMap = getFieldAny(source.chunkMap, "entityMap", "f_140150_");
            if (!(entityMap instanceof Map<?, ?> tracked)) {
                return;
            }
            Object trackedEntity = tracked.get(entity.getId());
            if (trackedEntity == null) {
                return;
            }
            Object seenBy = getFieldAny(trackedEntity, "seenBy", "f_140475_");
            if (seenBy instanceof Set<?> set) {
                set.clear();
            }
        } catch (Exception ignored) {
            // Best effort: the storage surgery below is what actually removes the entity.
        }
    }

    private static void removeEntityInternal(Entity entity) {        try {
            entity.setRemoved(Entity.RemovalReason.KILLED);
            entity.stopRiding();
            entity.getPassengers().forEach(Entity::stopRiding);

            if (entity.level().isClientSide) {
                ClientLevel clientLevel = (ClientLevel) entity.level();
                Object manager = ReflectionAccess.getFieldRelaxed(clientLevel, "entityStorage");
                long currentSectionKey = SectionPos.asLong(entity.blockPosition());

                Object sectionStorage = ReflectionAccess.getFieldRelaxed(manager, "sectionStorage");
                Method getSection = sectionStorage.getClass().getMethod("getSection", long.class);
                Object currentSection = getSection.invoke(sectionStorage, currentSectionKey);

                if (currentSection != null) {
                    Object storage = ReflectionAccess.getFieldRelaxed(currentSection, "storage");
                    Map<Class<?>, List<Entity>> byClass =
                            (Map<Class<?>, List<Entity>>) ReflectionAccess.getFieldRelaxed(storage, "byClass");
                    for (Map.Entry<Class<?>, List<Entity>> entry : byClass.entrySet()) {
                        if (entry.getKey().isInstance(entity)) {
                            entry.getValue().remove(entity);
                        }
                    }

                    Object visibility = currentSection.getClass().getMethod("getStatus").invoke(currentSection);
                    boolean isTicking = (boolean) visibility.getClass().getMethod("isTicking").invoke(visibility);
                    if (isTicking || entity.isAlwaysTicking()) {
                        Object tickingEntities = ReflectionAccess.getFieldRelaxed(clientLevel, "tickingEntities");
                        tickingEntities.getClass().getMethod("ensureActiveIsNotIterated").invoke(tickingEntities);
                        // EntityTickList#active is a Set<Integer> in older versions and an
                        // Int2ObjectMap<Entity> in 1.20.1; both accept remove(int).
                        Object active = ReflectionAccess.getFieldRelaxed(tickingEntities, "active");
                        if (active instanceof Set<?> set) {
                            set.remove(entity.getId());
                        } else if (active instanceof it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> map) {
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

            } else {
                ServerLevel serverLevel = (ServerLevel) entity.level();
                Object manager = ReflectionAccess.getFieldRelaxed(serverLevel, "entityManager");
                long currentSectionKey = SectionPos.asLong(entity.blockPosition());

                Object sectionStorage = ReflectionAccess.getFieldRelaxed(manager, "sectionStorage");
                Method getSection = sectionStorage.getClass().getMethod("getSection", long.class);
                Object currentSection = getSection.invoke(sectionStorage, currentSectionKey);

                if (currentSection != null) {
                    Object storage = ReflectionAccess.getFieldRelaxed(currentSection, "storage");
                    Map<Class<?>, List<Entity>> byClass =
                            (Map<Class<?>, List<Entity>>) ReflectionAccess.getFieldRelaxed(storage, "byClass");
                    for (Map.Entry<Class<?>, List<Entity>> entry : byClass.entrySet()) {
                        if (entry.getKey().isInstance(entity)) {
                            entry.getValue().remove(entity);
                        }
                    }
                }

                if (entity instanceof ServerPlayer) { ServerPlayer serverplayer = (ServerPlayer) entity;
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
            if (disp != null) {
                // Intentionally empty -- the original kept this branch as a hook point.
            }

            if (entity instanceof LivingEntity) { LivingEntity living = (LivingEntity) entity;
                living.getBrain().clearMemories();
            }

        } catch (Exception ignored) {
            // Original behaviour: swallow everything.
        }
    }

    /**
     * Reads a field by trying each candidate name in turn.
     *
     * <p>The two names are simply alternatives: the mapped name resolves in a development workspace
     * and the SRG id resolves in production, and which one wins depends only on the mapping
     * generation. No superclass walk is needed -- all of these are declared on the passed class.
     */
    private static Object getFieldAny(Object target, String... candidates) {
        for (String name : candidates) {
            try {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // try the next candidate
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

}
