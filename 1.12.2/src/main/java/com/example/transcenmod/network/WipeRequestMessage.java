package com.example.transcenmod.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Left-click with the egg: asks the server to end the encounter.
 *
 * <p>Sent client to server. A packet is required because the case that matters most -- left-clicking with
 * nothing under the crosshair -- raises a client-only event, so the server never learns about it. The other
 * left-click routes send the same packet, so a swing behaves the same whatever it landed on.
 *
 * <p>The local HUD is switched off immediately on the client rather than waiting for the server to answer:
 * the player performed a deliberate action and expects it to take effect now, and making it wait a round
 * trip would show the bar for a moment after the gesture that dismissed it. The authoritative clear still
 * happens on the server, and its answer comes back as {@link WipeEndedMessage}.
 */
public class WipeRequestMessage implements IMessage {

    public WipeRequestMessage() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        // no payload
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // no payload
    }

    public static class Handler implements IMessageHandler<WipeRequestMessage, IMessage> {

        @Override
        public IMessage onMessage(WipeRequestMessage message, MessageContext ctx) {
            final EntityPlayerMP sender = ctx.getServerHandler().player;
            if (sender == null) {
                return null;
            }
            // Scheduled rather than run inline. The 1.16.5 build used enqueueWork for the same reason: a
            // packet handler runs on the netty thread, and the wipe walks and mutates the level's entity
            // storages, which is server-thread work only.
            sender.getServerWorld().getMinecraftServer().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (sender.world instanceof net.minecraft.world.WorldServer) {
                        com.example.transcenmod.event.TranscenEvents.toggleDefence(sender,
                                (net.minecraft.world.WorldServer) sender.world);
                    }
                }
            });
            return null;
        }
    }
}
