package com.example.transcenmod.item;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.network.ActivateHudPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.network.PacketDistributor;

/**
 * Spawn egg for the Transcendence Zombie.
 *
 * <p>Right-clicking restores the protection and spawns the zombie, in that order, and says so in chat.
 *
 * <p>The order is the whole point of the method. The protection used to be switched on after the spawn
 * had already happened, which left the new zombie undefended for the rest of that tick -- long enough for
 * a removal landing in the same tick to take, and for the zombie to be gone before the protection that
 * was meant to keep it existed. Enabling first removes that window entirely.
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
    public InteractionResult useOn(UseOnContext context) {
        // Protection on, before the zombie exists.
        //
        // Done ahead of super.useOn deliberately: that call is what creates and places the entity, so
        // anything set afterwards is set too late to cover it.
        if (context.getPlayer() instanceof ServerPlayer) { ServerPlayer player = (ServerPlayer) context.getPlayer();
            TranscenMod.defenceEnabled = true;
        }

        InteractionResult result = super.useOn(context);

        if (result.consumesAction() && context.getPlayer() instanceof ServerPlayer) { ServerPlayer player = (ServerPlayer) context.getPlayer();
            player.sendSystemMessage(Component.literal("\u00a7a\u9632\u5fa1\u5df2\u5f00\u542f"));
            TranscenMod.NETWORK.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new ActivateHudPacket());
        }
        return result;
    }
}
