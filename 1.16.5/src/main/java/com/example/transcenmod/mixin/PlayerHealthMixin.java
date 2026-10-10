package com.example.transcenmod.mixin;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.event.TranscenEvents;
import com.example.transcenmod.util.RetaliationState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class PlayerHealthMixin
{

    private static final float TRANSCENMOD_FULL_HEALTH = 20.0F;

    private static volatile boolean transcenmod$reported;

    @Inject(method = "getHealth()F", at = @At("HEAD"), cancellable = true)
    private void transcenmod$healthOverride(CallbackInfoReturnable<Float> cir)
    {
        if (!transcenmod$reported)
        {
            transcenmod$reported = true;
            TranscenMod.LOGGER.info(
                    "[transcenmod] getHealth injection reached on {} (retaliation {})",
                    Thread.currentThread().getName(), RetaliationState.isActive());
        }

        if (!((Object) this instanceof PlayerEntity))
        {
            return;
        }
        PlayerEntity player = (PlayerEntity) (Object) this;

        if (RetaliationState.isActive())
        {
            cir.setReturnValue(0.0F);
            return;
        }

        if (!TranscenEvents.isCarryingEgg(player))
        {
            return;
        }
        cir.setReturnValue(TRANSCENMOD_FULL_HEALTH);
    }
}
