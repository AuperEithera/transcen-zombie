package com.example.transcenmod.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class ActivateHudMessage implements IMessage {

    public ActivateHudMessage() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        // no payload
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // no payload
    }

    public static class Handler implements IMessageHandler<ActivateHudMessage, IMessage> {

        @Override
        public IMessage onMessage(ActivateHudMessage message, MessageContext ctx) {
            ClientPacketHooks.activateHud();
            return null;
        }
    }
}
