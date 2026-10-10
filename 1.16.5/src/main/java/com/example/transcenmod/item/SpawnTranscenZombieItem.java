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

/**
 * Spawn egg for the Transcendence ZombieEntity.
 *
 * <p>Right-clicking restores the protection and spawns the zombie, in that order, and says so in chat.
 *
 * <p>The order is the whole point of the method. The protection used to be switched on after the spawn
 * had already happened, which left the new zombie undefended for the rest of that tick -- long enough for
 * a removal that landed in the same tick to take, and for the zombie to be gone before the protection
 * that was meant to keep it existed. Enabling first removes that window entirely.
 *
 * <p>The message mirrors the one left-click prints. The two gestures are one toggle, so both ends of it
 * report the state; a switch that is silent in one direction is a switch the player has to test to
 * understand.
 *
 * <p>Holding this egg is also what exempts a player from annihilation; see
 * {@code TranscenEvents#isAnnihilationExempt}.
 */
public class SpawnTranscenZombieItem extends ForgeSpawnEggItem {

    public SpawnTranscenZombieItem(Properties properties, int primaryColor, int secondaryColor) {
        super(TranscenMod.TRANSCEN_ZOMBIE, primaryColor, secondaryColor, properties);
    }

    @Override
    public ActionResultType onItemUse(ItemUseContext context) {
        // Protection on, before the zombie exists.
        //
        // Done ahead of super.useOn deliberately: that call is what creates and places the entity, so
        // anything set afterwards is set too late to cover it.
        if (context.getPlayer() instanceof ServerPlayerEntity) { ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
            TranscenMod.defenceEnabled = true;
            com.example.transcenmod.event.TranscenEvents.restartEncounter();
        }

        ActionResultType result = super.onItemUse(context);

        if (result == ActionResultType.SUCCESS && context.getPlayer() instanceof ServerPlayerEntity) { ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
            player.sendMessage(new StringTextComponent("\u00a7a\u9632\u5fa1\u5df2\u5f00\u542f"), java.util.UUID.randomUUID());
            TranscenMod.NETWORK.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new ActivateHudPacket());
        }
        return result;
    }
}
