package com.example.transcenmod.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class WipeEndedMessage implements IMessage {

    public WipeEndedMessage() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        // no payload
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // no payload
    }

    public static class Handler implements IMessageHandler<WipeEndedMessage, IMessage> {

        @Override
        public IMessage onMessage(WipeEndedMessage message, MessageContext ctx) {
            ClientPacketHooks.deactivateHud();
            return null;
        }
    }
}
