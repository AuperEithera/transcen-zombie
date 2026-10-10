package com.example.transcenmod.item;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.network.ActivateHudPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.network.PacketDistributor;

public class SpawnTranscenZombieItem extends ForgeSpawnEggItem
{

    public SpawnTranscenZombieItem(Properties properties, int primaryColor, int secondaryColor)
    {
        super(TranscenMod.TRANSCEN_ZOMBIE, primaryColor, secondaryColor, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context)
    {
        if (context.getPlayer() instanceof ServerPlayer)
        {
            ServerPlayer player = (ServerPlayer) context.getPlayer();
            TranscenMod.defenceEnabled = true;
            com.example.transcenmod.event.TranscenEvents.restartEncounter();
        }

        InteractionResult result = super.useOn(context);

        if (result.consumesAction() && context.getPlayer() instanceof ServerPlayer)
        {
            ServerPlayer player = (ServerPlayer) context.getPlayer();
            player.sendSystemMessage(Component.literal("\u00a7aDefence enabled"));
            TranscenMod.NETWORK.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new ActivateHudPacket());
        }
        return result;
    }
}
