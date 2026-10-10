package com.example.transcenmod.network;

import com.example.transcenmod.TranscenMod;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Tells the client to raise the death overlay.
 *
 * <p>Sent from {@code TranscenZombie#hurt} through
 * {@code ServerPlayerEntity.connection.connection}, i.e. it bypasses the vanilla
 * {@code ServerPlayerEntity#connection} accessor chain on purpose.
 */
public class DeathScreenPacket {

    public DeathScreenPacket() {}

    public DeathScreenPacket(PacketBuffer buf) {}

    public void encode(PacketBuffer buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> TranscenMod.SHOW_DEATH_SCREEN = true);
        ctx.get().setPacketHandled(true);
    }
}
