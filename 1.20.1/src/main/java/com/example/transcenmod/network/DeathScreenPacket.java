package com.example.transcenmod.network;

import com.example.transcenmod.TranscenMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Tells the client to raise the death overlay.
 *
 * <p>Sent from {@code TranscenZombie#hurt} through
 * {@code ServerPlayer.connection.connection}, i.e. it bypasses the vanilla
 * {@code ServerPlayer#connection} accessor chain on purpose.
 */
public class DeathScreenPacket {

    public DeathScreenPacket() {}

    public DeathScreenPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> TranscenMod.SHOW_DEATH_SCREEN = true);
        ctx.get().setPacketHandled(true);
    }
}
