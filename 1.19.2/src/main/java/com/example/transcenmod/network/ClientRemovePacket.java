package com.example.transcenmod.network;

import com.example.transcenmod.client.AnnihilationClientState;
import com.example.transcenmod.util.CustomEntityRemover;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tells the client to drop an entity through {@link CustomEntityRemover} and leave the vanilla death
 * puff behind.
 *
 * <p>Sent two seconds (ten strike ticks) after the server already removed the same entity, so the
 * server is authoritative first and the client catches up afterwards. The puff is spawned on the
 * client rather than broadcast from the server so the visual lands at the exact moment the entity
 * disappears locally.
 */
public class ClientRemovePacket {

    private final UUID entityId;

    public ClientRemovePacket(UUID entityId) {
        this.entityId = entityId;
    }

    public ClientRemovePacket(FriendlyByteBuf buf) {
        this.entityId = buf.readUUID();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(this.entityId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            Entity entity = findById(level, this.entityId);
            if (entity == null) {
                // Already gone locally; make sure the doom tint does not outlive it.
                AnnihilationClientState.clearDoomed(this.entityId);
                return;
            }
            double x = entity.getX();
            double y = entity.getY() + entity.getBbHeight() * 0.5D;
            double z = entity.getZ();
            CustomEntityRemover.removeEntity(entity);
            level.addParticle(ParticleTypes.POOF, x, y, z, 0.0D, 0.0D, 0.0D);
            // End of the forced-red window for this entity.
            AnnihilationClientState.clearDoomed(this.entityId);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * Looks the entity up by UUID. {@code Level#getEntities()} is {@code protected}, so the walk goes
     * through {@code ClientLevel}'s public rendering view instead.
     */
    private static Entity findById(ClientLevel level, UUID id) {
        for (Entity candidate : level.entitiesForRendering()) {
            if (candidate.getUUID().equals(id)) {
                return candidate;
            }
        }
        return null;
    }
}
