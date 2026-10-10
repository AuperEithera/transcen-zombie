package com.example.transcenmod.network;

import com.example.transcenmod.client.AnnihilationClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class MarkDoomedPacket
{

    private final UUID entityId;

    public MarkDoomedPacket(UUID entityId)
    {
        this.entityId = entityId;
    }

    public MarkDoomedPacket(FriendlyByteBuf buf)
    {
        this.entityId = buf.readUUID();
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUUID(this.entityId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> AnnihilationClientState.markDoomed(this.entityId));
        ctx.get().setPacketHandled(true);
    }
}
