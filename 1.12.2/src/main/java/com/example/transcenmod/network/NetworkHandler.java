package com.example.transcenmod.network;

import com.example.transcenmod.TranscenMod;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * The 1.12.2 replacement for the 1.16.5 build's {@code NetworkRegistry.newSimpleChannel}.
 *
 * <p>The packet set is unchanged: the death overlay, the client removal, the HUD arm, the HUD sync, the
 * wipe request and the wipe ended signal. What changed is the plumbing -- this generation's
 * {@code SimpleNetworkWrapper} has no {@code ProtocolVersion} object to build, no
 * {@code Supplier<NetworkEvent.Context>} to unwrap and no {@code PacketDistributor} to target a dimension
 * with, so the dimension send walks the player list itself.
 *
 * <p>Ids are explicit rather than inferred. {@code registerMessage} derives a discriminator from a class
 * hash when it is not given one, and that value differs between the development and production jars, which
 * would desynchronise a client and a server running the same version.
 *
 * <p>{@link #init()} must be called from the {@code @Mod} constructor, not from {@code preInit}. See
 * {@code TranscenMod}'s constructor for why.
 */
public final class NetworkHandler {

    /** The channel name. */
    private static final String CHANNEL_NAME = TranscenMod.MODID + ":main";

    private static SimpleNetworkWrapper channel;

    private NetworkHandler() {}

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);

        int id = 0;
        channel.registerMessage(DeathScreenMessage.Handler.class, DeathScreenMessage.class,
                id++, Side.CLIENT);
        channel.registerMessage(ClientRemoveMessage.Handler.class, ClientRemoveMessage.class,
                id++, Side.CLIENT);
        channel.registerMessage(ActivateHudMessage.Handler.class, ActivateHudMessage.class,
                id++, Side.CLIENT);
        channel.registerMessage(HudSyncMessage.Handler.class, HudSyncMessage.class,
                id++, Side.CLIENT);
        // Left-click with the egg asks the server to end the encounter.
        channel.registerMessage(WipeRequestMessage.Handler.class, WipeRequestMessage.class,
                id++, Side.SERVER);
        channel.registerMessage(WipeEndedMessage.Handler.class, WipeEndedMessage.class,
                id++, Side.CLIENT);
    }

    /** Sends to one player. */
    public static void sendToPlayer(EntityPlayerMP player, IMessage message) {
        if (channel != null && player != null) {
            channel.sendTo(message, player);
        }
    }

    /**
     * Sends to every player in one dimension.
     *
     * <p>This is the 1.16.5 build's {@code PacketDistributor.DIMENSION}, which this generation does not
     * have. Each dimension is its own {@code WorldServer}, so the walk is exact rather than a filter over
     * all players.
     */
    public static void sendToDimension(int dimension, IMessage message) {
        if (channel == null) {
            return;
        }
        net.minecraft.server.MinecraftServer server =
                FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            // Client side: there is no server to walk. The single-player connection is reached through the
            // client channel instead.
            channel.sendToServer(message);
            return;
        }
        net.minecraft.world.WorldServer level = server.getWorld(dimension);
        if (level == null) {
            return;
        }
        // Copied before iterating: sending can cause a player to leave, and the live list would then be
        // mutated underneath the loop.
        java.util.List<net.minecraft.entity.Entity> players =
                new java.util.ArrayList<net.minecraft.entity.Entity>(level.playerEntities);
        for (net.minecraft.entity.Entity entity : players) {
            if (entity instanceof EntityPlayerMP) {
                channel.sendTo(message, (EntityPlayerMP) entity);
            }
        }
    }

    /** Sends from the client to the server. */
    public static void sendToServer(IMessage message) {
        if (channel != null) {
            channel.sendToServer(message);
        }
    }
}
