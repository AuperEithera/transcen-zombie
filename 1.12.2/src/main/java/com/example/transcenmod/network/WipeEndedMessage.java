package com.example.transcenmod.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Tells every client the encounter is over, so each one releases its sticky HUD latch.
 *
 * <p>Needed because that latch is deliberately immune to the ordinary "no zombies right now" updates --
 * that immunity is what stops the bar flickering when a zombie is briefly removed and put back. A wipe is
 * the one case that must get past it, and nothing else the server sends can, so it needs its own signal.
 */
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
