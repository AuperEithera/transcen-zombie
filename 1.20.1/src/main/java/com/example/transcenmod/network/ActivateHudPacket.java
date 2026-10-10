package com.example.transcenmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Turns the Transcendence HUD on for the receiving client.
 *
 * <p>Sent when a player right-clicks the spawn egg: the requirement is that the health bar and the
 * screen rendering only appear after that click, not merely because a zombie happens to exist. The
 * client also arms the HUD on its own when it spots a zombie entity, so zombies brought in by other
 * means still get a bar.
 *
 * <p>The client half is reached through a {@link Runnable} registered from the client side rather
 * than a direct reference to a client-only class. This class is loaded on dedicated servers too,
 * and naming a client-only type in its constant pool would break that.
 */
public class ActivateHudPacket {

    private static Runnable clientHook = () -> {};

    /** Called from the client setup so the server side never needs to name a client class. */
    public static void setClientHook(Runnable hook) {
        clientHook = hook;
    }

    public ActivateHudPacket() {}

    public ActivateHudPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(clientHook);
        ctx.get().setPacketHandled(true);
    }
}
