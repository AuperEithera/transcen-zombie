package com.example.transcenmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class WipeRequestPacket
{

    private static Runnable clientHook = () -> {};

    public static void setClientHook(Runnable hook)
    {
        clientHook = hook;
    }

    public WipeRequestPacket() {}

    public WipeRequestPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null)
            {
                return;
            }
            com.example.transcenmod.event.TranscenEvents.toggleDefence(sender, sender.serverLevel());
        });
        ctx.get().setPacketHandled(true);
    }
}
