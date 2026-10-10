package com.example.transcenmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class WipeEndedPacket
{

    private static Runnable clientHook = () -> {};

    public static void setClientHook(Runnable hook)
    {
        clientHook = hook;
    }

    public WipeEndedPacket() {}

    public WipeEndedPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(clientHook);
        ctx.get().setPacketHandled(true);
    }
}
