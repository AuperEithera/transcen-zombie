package com.example.transcenmod.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class HudSyncMessage implements IMessage {

    private boolean active;
    private float progress;

    public HudSyncMessage() {}

    public HudSyncMessage(boolean active, float progress) {
        this.active = active;
        this.progress = progress;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.active = buf.readBoolean();
        this.progress = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(this.active);
        buf.writeFloat(this.progress);
    }

    public static class Handler implements IMessageHandler<HudSyncMessage, IMessage> {

        @Override
        public IMessage onMessage(HudSyncMessage message, MessageContext ctx) {
            ClientPacketHooks.setHud(message.active, message.progress);
            return null;
        }
    }
}
