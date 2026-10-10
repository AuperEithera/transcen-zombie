package com.example.transcenmod.network;

import com.example.transcenmod.client.AnnihilationClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tells the client that an entity has been removed from the <em>server</em> storages and is now
 * doomed.
 *
 * <p>The entity still exists client-side at this point and keeps existing for another two seconds,
 * rendered forced red. This packet opens that window; {@link ClientRemovePacket} closes it.
 *
 * <p>This class names a client-only type, but it is only ever loaded on the client: Forge registers
 * it through the connection's packet dispatch, and a dedicated server never handles the
 * client-bound id.
 */
public class MarkDoomedPacket {

    private final UUID entityId;

    public MarkDoomedPacket(UUID entityId) {
        this.entityId = entityId;
    }

    public MarkDoomedPacket(FriendlyByteBuf buf) {
        this.entityId = buf.readUUID();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(this.entityId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> AnnihilationClientState.markDoomed(this.entityId));
        ctx.get().setPacketHandled(true);
    }
}
