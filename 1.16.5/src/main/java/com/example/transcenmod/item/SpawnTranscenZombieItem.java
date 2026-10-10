package com.example.transcenmod.item;
import net.minecraft.util.text.StringTextComponent;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.network.ActivateHudPacket;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ActionResultType;
import net.minecraft.item.ItemUseContext;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.fml.network.PacketDistributor;

public class SpawnTranscenZombieItem extends ForgeSpawnEggItem
{

    public SpawnTranscenZombieItem(Properties properties, int primaryColor, int secondaryColor)
    {
        super(TranscenMod.TRANSCEN_ZOMBIE, primaryColor, secondaryColor, properties);
    }

    @Override
    public ActionResultType onItemUse(ItemUseContext context)
    {
        if (context.getPlayer() instanceof ServerPlayerEntity)
        {
            ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
            TranscenMod.defenceEnabled = true;
            com.example.transcenmod.event.TranscenEvents.restartEncounter();
        }

        ActionResultType result = super.onItemUse(context);

        if (result == ActionResultType.SUCCESS && context.getPlayer() instanceof ServerPlayerEntity)
        {
            ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
            player.sendMessage(new StringTextComponent("\u00a7aDefence enabled"), java.util.UUID.randomUUID());
            TranscenMod.NETWORK.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new ActivateHudPacket());
        }
        return result;
    }
}
