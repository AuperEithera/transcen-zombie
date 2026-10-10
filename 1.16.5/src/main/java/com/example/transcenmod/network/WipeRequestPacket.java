package com.example.transcenmod.network;

import net.minecraft.network.PacketBuffer;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

public class WipeRequestPacket
{

    private static Runnable clientHook = () -> {};

    public static void setClientHook(Runnable hook)
    {
        clientHook = hook;
    }

    public WipeRequestPacket() {}

    public WipeRequestPacket(PacketBuffer buf) {}

    public void encode(PacketBuffer buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> {
            ServerPlayerEntity sender = ctx.get().getSender();
            if (sender == null)
            {
                return;
            }
            com.example.transcenmod.event.TranscenEvents.toggleDefence(sender, (net.minecraft.world.server.ServerWorld) sender.getEntityWorld());
        });
        ctx.get().setPacketHandled(true);
    }
}
