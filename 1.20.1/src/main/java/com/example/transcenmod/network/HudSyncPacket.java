package com.example.transcenmod.network;

import com.example.transcenmod.client.AnnihilationClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client HUD state: whether a Transcendence Zombie is present, and what fraction of its
 * health to show.
 *
 * <p>Driven by the server rather than sniffed from the client's entity list, because the bar has to
 * be right even when the client's rendering view does not contain the zombie. Sent only when the
 * values change, so it costs nothing while idle.
 */
public class HudSyncPacket {

    private final boolean active;
    private final float progress;

    public HudSyncPacket(boolean active, float progress) {
        this.active = active;
        this.progress = progress;
    }

    public HudSyncPacket(FriendlyByteBuf buf) {
        this.active = buf.readBoolean();
        this.progress = buf.readFloat();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.active);
        buf.writeFloat(this.progress);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> AnnihilationClientState.setHud(this.active, this.progress));
        ctx.get().setPacketHandled(true);
    }
}
