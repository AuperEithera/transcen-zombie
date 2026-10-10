package com.example.transcenmod.mixin;

import com.example.transcenmod.client.HudSuppression;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.gui.ForgeIngameGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ForgeIngameGui.class, remap = false)
public abstract class HudSuppressionMixin
{

    @Inject(method = "pre", at = @At("HEAD"), cancellable = true, remap = false)
    private void transcenmod$suppressOverlayPre(RenderGameOverlayEvent.ElementType type, MatrixStack matrixStack,
                                                CallbackInfoReturnable<Boolean> cir)
                                                {
        if (HudSuppression.isEncounterPresent())
        {
            cir.setReturnValue(Boolean.FALSE);
        }
    }

    @Inject(method = "post", at = @At("HEAD"), cancellable = true, remap = false)
    private void transcenmod$suppressOverlayPost(RenderGameOverlayEvent.ElementType type, MatrixStack matrixStack,
                                                 CallbackInfo ci)
                                                 {
        if (HudSuppression.isEncounterPresent())
        {
            if (type == RenderGameOverlayEvent.ElementType.HOTBAR)
            {
                com.example.transcenmod.client.ClientEvents.renderOwnBarDirectly(matrixStack);
            }
            ci.cancel();
        }
    }
}
