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

public class ClientRemovePacket
{

    private final UUID entityId;

    public ClientRemovePacket(UUID entityId)
    {
        this.entityId = entityId;
    }

    public ClientRemovePacket(PacketBuffer buf)
    {
        this.entityId = buf.readUniqueId();
    }

    public void encode(PacketBuffer buf)
    {
        buf.writeUniqueId(this.entityId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> {
            ClientWorld level = Minecraft.getInstance().world;
            if (level == null)
            {
                return;
            }
            Entity entity = findById(level, this.entityId);
            if (entity == null)
            {
                // Already gone locally; make sure the doom tint does not outlive it.
                AnnihilationClientState.clearDoomed(this.entityId);
                return;
            }
            double x = entity.getPosX();
            double y = entity.getPosY() + entity.getHeight() * 0.5D;
            double z = entity.getPosZ();
            entity.remove(false);
            level.addParticle(ParticleTypes.POOF, x, y, z, 0.0D, 0.0D, 0.0D);
            AnnihilationClientState.clearDoomed(this.entityId);
        });
        ctx.get().setPacketHandled(true);
    }

    private static Entity findById(ClientWorld level, UUID id)
    {
        for (Entity candidate : level.getAllEntities())
        {
            if (candidate.getUniqueID().equals(id))
            {
                return candidate;
            }
        }
        return null;
    }
}
