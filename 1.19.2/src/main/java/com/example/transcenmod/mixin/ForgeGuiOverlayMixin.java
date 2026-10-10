package com.example.transcenmod.mixin;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.client.HudSuppression;
import com.example.transcenmod.client.SuperHudRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ForgeGui.class, remap = false)
public abstract class ForgeGuiOverlayMixin
{

    @Inject(method = "m_93030_", at = @At("HEAD"), cancellable = true, remap = false)
    private void transcenmod$suppressForeignHud(PoseStack poseStack, float partialTick, CallbackInfo ci)
    {
        if (!HudSuppression.isEncounterPresent())
        {
            return;
        }
        if (SuperHudRenderer.isUnavailable())
        {
            return;
        }
        SuperHudRenderer.render((Gui) (Object) this, poseStack, partialTick);
        ci.cancel();
    }
}
