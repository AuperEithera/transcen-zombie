package com.example.transcenmod.network;

import com.example.transcenmod.util.EntityWiper;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumParticleTypes;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

/**
 * Drops the client's copy of an annihilated entity and leaves the death puff behind.
 *
 * <p>Broadcast in the same tick as the server-side removal. The server copy is unlinked from the world list
 * and its chunk directly, which is faster than the entity tracker but also bypasses it, so the client is
 * never told through the vanilla path and has to be told here. The puff is spawned on the client rather than
 * broadcast from the server so the visual lands at the exact moment the entity disappears locally.
 */
public class ClientRemoveMessage implements IMessage {

    private UUID entityId;

    public ClientRemoveMessage() {}

    public ClientRemoveMessage(UUID entityId) {
        this.entityId = entityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = new UUID(buf.readLong(), buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.entityId.getMostSignificantBits());
        buf.writeLong(this.entityId.getLeastSignificantBits());
    }

    public static class Handler implements IMessageHandler<ClientRemoveMessage, IMessage> {

        @Override
        public IMessage onMessage(final ClientRemoveMessage message, MessageContext ctx) {
            final Minecraft mc = Minecraft.getMinecraft();
            // Scheduled onto the client thread: a packet handler runs on the netty thread, and both the
            // world's entity list and the particle engine are client-thread state.
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    WorldClient level = mc.world;
                    if (level == null) {
                        return;
                    }
                    Entity entity = findById(level, message.entityId);
                    if (entity == null) {
                        return;
                    }
                    double x = entity.posX;
                    double y = entity.posY + entity.height * 0.5D;
                    double z = entity.posZ;
                    // The flag alone leaves the copy in the client's world list, and the renderer draws
                    // what the list holds -- this is what put several models on screen for one entity.
                    entity.setDead();
                    EntityWiper.wipe(entity);
                    level.spawnParticle(EnumParticleTypes.CLOUD, x, y, z, 0.0D, 0.0D, 0.0D);
                }
            });
            return null;
        }

        /**
         * Looks the entity up by UUID.
         *
         * <p>1.12.2's {@code WorldClient} keeps its entity set private, so the walk goes through the
         * inherited public {@code getLoadedEntityList()}. That is a live view of the same set the renderer
         * walks, which is what the lookup needs.
         */
        private static Entity findById(WorldClient level, UUID id) {
            for (Entity candidate : level.getLoadedEntityList()) {
                if (candidate.getUniqueID().equals(id)) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
