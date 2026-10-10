package com.example.transcenmod.network;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Tells every client the encounter is over, so each one releases its sticky HUD latch.
 *
 * <p>Needed because that latch is deliberately immune to the ordinary "no zombies right now" updates --
 * that immunity is what stops the bar flickering when a zombie is briefly removed and put back. A wipe is
 * the one case that must get past it, and nothing else the server sends can, so it needs its own signal.
 *
 * <p>The client half is reached through a {@link Runnable} registered from the client side, so this class
 * stays loadable on a dedicated server.
 */
public class WipeEndedPacket {

    private static Runnable clientHook = () -> {};

    public static void setClientHook(Runnable hook) {
        clientHook = hook;
    }

    public WipeEndedPacket() {}

    public WipeEndedPacket(PacketBuffer buf) {}

    public void encode(PacketBuffer buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(clientHook);
        ctx.get().setPacketHandled(true);
    }
}
