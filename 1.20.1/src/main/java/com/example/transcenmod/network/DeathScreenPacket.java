package com.example.transcenmod.network;

import com.example.transcenmod.TranscenMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class DeathScreenPacket
{

    public DeathScreenPacket() {}

    public DeathScreenPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> TranscenMod.SHOW_DEATH_SCREEN = true);
        ctx.get().setPacketHandled(true);
    }
}
