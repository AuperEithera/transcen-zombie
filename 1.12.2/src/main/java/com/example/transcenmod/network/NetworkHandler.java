package com.example.transcenmod.network;

import com.example.transcenmod.TranscenMod;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class NetworkHandler {

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

    public static void sendToPlayer(EntityPlayerMP player, IMessage message) {
        if (channel != null && player != null) {
            channel.sendTo(message, player);
        }
    }

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

    public static void sendToServer(IMessage message) {
        if (channel != null) {
            channel.sendToServer(message);
        }
    }
}
