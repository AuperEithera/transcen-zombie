package com.example.transcenmod.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class DeathScreenMessage implements IMessage {

    public DeathScreenMessage() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        // no payload
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // no payload
    }

    public static class Handler implements IMessageHandler<DeathScreenMessage, IMessage> {

        @Override
        public IMessage onMessage(DeathScreenMessage message, MessageContext ctx) {
            ClientPacketHooks.showDeathScreen();
            return null;
        }
    }
}
