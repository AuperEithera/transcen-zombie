package com.example.transcenmod.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Turns the Transcendence HUD on for the receiving client.
 *
 * <p>Sent when a player right-clicks the spawn egg: the requirement is that the health bar and the screen
 * rendering only appear after that click, not merely because a zombie happens to exist. The client also
 * arms the HUD on its own when it spots a zombie entity, so zombies brought in by other means still get a
 * bar.
 */
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
