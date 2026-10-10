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

public class ClientRemovePacket
{

    private final UUID entityId;

    public ClientRemovePacket(UUID entityId)
    {
        this.entityId = entityId;
    }

    public ClientRemovePacket(FriendlyByteBuf buf)
    {
        this.entityId = buf.readUUID();
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUUID(this.entityId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> {
            ClientLevel level = Minecraft.getInstance().level;
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
            double x = entity.getX();
            double y = entity.getY() + entity.getBbHeight() * 0.5D;
            double z = entity.getZ();
            CustomEntityRemover.removeEntity(entity);
            level.addParticle(ParticleTypes.POOF, x, y, z, 0.0D, 0.0D, 0.0D);
            AnnihilationClientState.clearDoomed(this.entityId);
        });
        ctx.get().setPacketHandled(true);
    }

    private static Entity findById(ClientLevel level, UUID id)
    {
        for (Entity candidate : level.entitiesForRendering())
        {
            if (candidate.getUUID().equals(id))
            {
                return candidate;
            }
        }
        return null;
    }
}
