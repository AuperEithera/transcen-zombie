package com.example.transcenmod.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Server to client HUD state: whether an encounter is running, and what fraction of health to show.
 *
 * <p>Driven by the server rather than sniffed from the client's entity list, because the bar has to be
 * right even when the client's rendering view does not contain the zombie. Sent only when the values
 * change, so it costs nothing while idle.
 */
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
