package com.example.transcenmod.network;

import com.example.transcenmod.TranscenMod;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

public class DeathScreenPacket
{

    public DeathScreenPacket() {}

    public DeathScreenPacket(PacketBuffer buf) {}

    public void encode(PacketBuffer buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> TranscenMod.SHOW_DEATH_SCREEN = true);
        ctx.get().setPacketHandled(true);
    }
}
