package com.example.transcenmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Left-click with the egg: asks the server to toggle the protection, and switches it off on this client
 * at once.
 *
 * <p>Sent client to server. A packet is required because the case that matters most -- left-clicking with
 * nothing under the crosshair -- raises a client-only event, so the server never learns about it. The
 * other left-click routes send the same packet, so a swing behaves the same whatever it landed on.
 *
 * <p>The local HUD is switched off immediately rather than waiting for the server to answer. The player
 * performed a deliberate action and expects it to take effect now; making it wait a round trip would show
 * the bar for a moment after the gesture that dismissed it.
 */
public class WipeRequestPacket {

    private static Runnable clientHook = () -> {};

    /** Called from the client setup so the server side never needs to name a client class. */
    public static void setClientHook(Runnable hook) {
        clientHook = hook;
    }

    public WipeRequestPacket() {}

    public WipeRequestPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // The client half is deliberately NOT run here.
            //
            // This is a client-to-server packet, so the handler executes on the server. Calling the
            // client hook from it invoked Minecraft.getInstance() and the client level on the server
            // thread, and any throw from that took the toggle below down with it: the swipe cleared
            // the client's rendering (the hook is separately registered on the receiving side) and
            // then nothing else happened -- no chat message, no server-side clear. The client is told
            // to stand down by WipeEndedPacket, which the server sends once the toggle has run.
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) {
                return;
            }
            com.example.transcenmod.event.TranscenEvents.toggleDefence(sender, sender.serverLevel());
        });
        ctx.get().setPacketHandled(true);
    }
}
