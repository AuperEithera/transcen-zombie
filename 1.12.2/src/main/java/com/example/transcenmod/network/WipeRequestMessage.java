package com.example.transcenmod.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

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
