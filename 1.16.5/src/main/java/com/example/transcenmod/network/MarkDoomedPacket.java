package com.example.transcenmod.network;

import com.example.transcenmod.client.AnnihilationClientState;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class MarkDoomedPacket
{

    private final UUID entityId;

    public MarkDoomedPacket(UUID entityId)
    {
        this.entityId = entityId;
    }

    public MarkDoomedPacket(PacketBuffer buf)
    {
        this.entityId = buf.readUniqueId();
    }

    public void encode(PacketBuffer buf)
    {
        buf.writeUniqueId(this.entityId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> AnnihilationClientState.markDoomed(this.entityId));
        ctx.get().setPacketHandled(true);
    }
}
