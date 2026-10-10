package com.example.transcenmod.network;

import com.example.transcenmod.client.AnnihilationClientState;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

public class HudSyncPacket
{

    private final boolean active;
    private final float progress;

    public HudSyncPacket(boolean active, float progress)
    {
        this.active = active;
        this.progress = progress;
    }

    public HudSyncPacket(PacketBuffer buf)
    {
        this.active = buf.readBoolean();
        this.progress = buf.readFloat();
    }

    public void encode(PacketBuffer buf)
    {
        buf.writeBoolean(this.active);
        buf.writeFloat(this.progress);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> AnnihilationClientState.setHud(this.active, this.progress));
        ctx.get().setPacketHandled(true);
    }
}
