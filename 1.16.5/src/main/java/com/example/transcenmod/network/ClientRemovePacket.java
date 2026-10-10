package com.example.transcenmod.network;

import com.example.transcenmod.client.AnnihilationClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.network.PacketBuffer;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
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

    public ClientRemovePacket(PacketBuffer buf) {
        this.entityId = buf.readUniqueId();
    }

    public void encode(PacketBuffer buf) {
        buf.writeUniqueId(this.entityId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientWorld level = Minecraft.getInstance().world;
            if (level == null) {
                return;
            }
            Entity entity = findById(level, this.entityId);
            if (entity == null) {
                // Already gone locally; make sure the doom tint does not outlive it.
                AnnihilationClientState.clearDoomed(this.entityId);
                return;
            }
            double x = entity.getPosX();
            double y = entity.getPosY() + entity.getHeight() * 0.5D;
            double z = entity.getPosZ();
            entity.remove(false);
            level.addParticle(ParticleTypes.POOF, x, y, z, 0.0D, 0.0D, 0.0D);
            // End of the forced-red window for this entity.
            AnnihilationClientState.clearDoomed(this.entityId);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * Looks the entity up by UUID. {@code World#getEntities()} is {@code protected}, so the walk goes
     * through {@code ClientWorld}'s public rendering view instead.
     */
    private static Entity findById(ClientWorld level, UUID id) {
        for (Entity candidate : level.getAllEntities()) {
            if (candidate.getUniqueID().equals(id)) {
                return candidate;
            }
        }
        return null;
    }
}
