package com.example.transcenmod.util;

import com.example.transcenmod.TranscenMod;
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

// manual removal straight out of the level's entity storages,vanilla's path is not used
// unlinks sectionStorage -> EntitySection -> byClass then byUuid/byId,EntityTickList#active
// resets isAddedToWorld,valid and levelCallback = EntityInLevelCallback.NULL
// every member touched is widened to public by META-INF/accesstransformer.cfg
// that AT is what makes the lookups resolve in dev and prod - setAccessible alone is not enough
public final class CustomEntityRemover {

    private CustomEntityRemover() {}

    @SuppressWarnings("unchecked")
    public static void removeEntity(Entity entity) {
        if (entity.getLevel() instanceof ServerLevel) { ServerLevel serverLevel = (ServerLevel) entity.getLevel();
            // detach client tracking first,otherwise this removal can still send a despawn packet
            detachFromClientTracking(serverLevel, entity);
        }
        removeEntityInternal(entity);
    }

    // empties ChunkMap.TrackedEntity.seenBy so no client is told about the removal
    // vanilla removal ends in ChunkMap.removeEntity -> broadcastAndSend,which acts on this tracking entry
    // with seenBy empty no recipient is left - a later vanilla sweep has nobody to notify
    // the client keeps its copy,that is what the two second red shell needs
    // SRG ids are the reliable handle since the host is a Minecraft class,mapped names are the fallback
    private static void detachFromClientTracking(ServerLevel level, Entity entity) {
        try {
            // getChunkSource() and ServerChunkCache#chunkMap are public,only the tracking map needs widening
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
            // best effort,the storage surgery below is what actually removes the entity
        }
    }

    private static void removeEntityInternal(Entity entity) {        try {
            entity.setRemoved(Entity.RemovalReason.KILLED);
            entity.stopRiding();
            entity.getPassengers().forEach(Entity::stopRiding);

            if (entity.getLevel().isClientSide) {
                ClientLevel clientLevel = (ClientLevel) entity.getLevel();
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
                        // EntityTickList#active is Set<Integer> in older versions,Int2ObjectMap<Entity> in 1.20.1
                        // both take remove(int)
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
                ServerLevel serverLevel = (ServerLevel) entity.getLevel();
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

                // releasing this set is what lets the uuid be reused,vanilla drops the id during removal
                // hand surgery skips that,so the id stayed claimed forever and addEntityUuid then refused
                // every later add of that uuid - silently,with one log warning - the revived zombie never existed
                // failures here are logged on purpose: swallowing them once is what hid exactly this bug
                try {
                    Object knownUuids = null;
                    try {
                        knownUuids = ReflectionAccess.getFieldRelaxed(manager, "knownUuids");
                    } catch (Throwable notFound) {
                        // named lookup preferred,a hole in the alias table already hid this bug once
                        // so walk the declared fields for any Set<UUID> instead of trusting one name
                        for (Class<?> c = manager.getClass(); c != null && c != Object.class;
                                c = c.getSuperclass()) {
                            for (Field f : c.getDeclaredFields()) {
                                if (!Set.class.isAssignableFrom(f.getType())) {
                                    continue;
                                }
                                // fastutil primitive sets implement java.util.Set yet throw ClassCastException on a UUID
                                // one unanswerable field must not abort the search
                                try {
                                    f.setAccessible(true);
                                    Object v = f.get(manager);
                                    if (v instanceof Set<?> candidate
                                            && candidate.contains(entity.getUUID())) {
                                        knownUuids = v;
                                        TranscenMod.LOGGER.warn(
                                                "[transcenmod] knownUuids found by scan as {} on {}",
                                                f.getName(), c.getSimpleName());
                                        break;
                                    }
                                } catch (Throwable notApplicable) {
                                    // unreadable or not a uuid set,keep scanning
                                }
                            }
                            if (knownUuids != null) {
                                break;
                            }
                        }
                    }
                    if (knownUuids instanceof Set<?> uuids) {
                        boolean removed = uuids.remove(entity.getUUID());
                        TranscenMod.LOGGER.info(
                                "[transcenmod] release uuid {} -> {} (set size {})",
                                entity.getUUID(), removed ? "removed" : "was not present", uuids.size());
                    } else {
                        TranscenMod.LOGGER.warn(
                                "[transcenmod] knownUuids not located on {} -- uuid stays claimed",
                                manager.getClass().getName());
                    }
                } catch (Throwable failure) {
                    TranscenMod.LOGGER.warn(
                            "[transcenmod] could not reach knownUuids; uuid stays claimed", failure);
                }

                Field levelCallback = Entity.class.getDeclaredField("levelCallback");
                levelCallback.setAccessible(true);
                levelCallback.set(entity, EntityInLevelCallback.NULL);
            }

            Field valid = Entity.class.getDeclaredField("valid");
            valid.setAccessible(true);
            valid.set(entity, false);

            CapabilityDispatcher disp = (CapabilityDispatcher) ReflectionAccess.getFieldRelaxed(entity, "capabilities");
            if (disp != null) {
                // intentionally empty,the original kept this branch as a hook point
            }

            if (entity instanceof LivingEntity) { LivingEntity living = (LivingEntity) entity;
                // 1.19.2 Brain has no clearMemories,stopAll is the equivalent teardown
                // cast needed: getBrain() returns a wildcard capture,not assignable to Brain<LivingEntity>
                @SuppressWarnings("unchecked")
                net.minecraft.world.entity.ai.Brain<LivingEntity> brain =
                        (net.minecraft.world.entity.ai.Brain<LivingEntity>) living.getBrain();
                brain.stopAll((ServerLevel) living.getLevel(), living);
            }

        } catch (Exception ignored) {
            // original behaviour,swallow everything
        }
    }

    // each candidate name is tried in turn,mapped name resolves in dev and the SRG id in production
    // which one wins depends only on the mapping generation
    // no superclass walk,every field here is declared on the passed class
    private static Object getFieldAny(Object target, String... candidates) {
        for (String name : candidates) {
            try {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // next candidate
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

}
