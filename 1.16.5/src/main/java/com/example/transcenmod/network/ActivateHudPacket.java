package com.example.transcenmod.network;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

public class ActivateHudPacket
{

    private static Runnable clientHook = () -> {};

    public static void setClientHook(Runnable hook)
    {
        clientHook = hook;
    }

    public ActivateHudPacket() {}

    public ActivateHudPacket(PacketBuffer buf) {}

    public void encode(PacketBuffer buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(clientHook);
        ctx.get().setPacketHandled(true);
    }
}
