package com.example.transcenmod.mixin;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.event.TranscenEvents;
import com.example.transcenmod.util.RetaliationState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// reports full health for a player carrying this mod's spawn egg and zero while the zombie retaliation runs
// this is the layer that actually keeps the egg carrier unkillable, the event one cannot do it
// the bolt calls hurt then setHealth(0) on the next line so no handler can run between them
// the cancelled LivingHurtEvent is simply overwritten right after
// answering getHealth() beats pinning the field,vanilla asks <=0 every tick plus the death check
// and the client death screen so a living answer never becomes a death decision
@Mixin(LivingEntity.class)
public abstract class PlayerHealthMixin {

    private static final float TRANSCENMOD_FULL_HEALTH = 20.0F;

    @Inject(method = "getHealth()F", at = @At("HEAD"), cancellable = true)
    private void transcenmod$healthOverride(CallbackInfoReturnable<Float> cir) {
        // target is LivingEntity not Player - getHealth is declared there,mixin matches declared methods only
        // Player fails with "could not find any targets matching" and a required config makes that fatal at startup
        // narrowed to players here since only they carry items
        if (!((Object) this instanceof Player)) {
            return;
        }
        Player player = (Player) (Object) this;

        // retaliation first, it must win over the egg - a strike from the zombie kills even an immune carrier
        if (RetaliationState.isActive()) {
            cir.setReturnValue(0.0F);
            return;
        }

        if (!TranscenEvents.isCarryingEgg(player)) {
            return;
        }
        cir.setReturnValue(TRANSCENMOD_FULL_HEALTH);
    }
}
