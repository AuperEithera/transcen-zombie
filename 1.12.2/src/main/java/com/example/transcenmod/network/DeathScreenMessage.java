package com.example.transcenmod.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Tells the client to raise the death overlay.
 *
 * <p>Sent from {@code TranscenZombie#attackEntityFrom} on the retaliation path, before the player is
 * detached from the level's storages. It has to go first: once the player is detached their entity
 * tracking set is empty, and a per-player send resolves its target through exactly that tracking, so
 * anything sent afterwards would go nowhere.
 *
 * <p>No payload. The message <em>is</em> the instruction, which is why the 1.16.5 build had the same
 * empty encode and decode.
 */
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
