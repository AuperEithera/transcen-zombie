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

// replaces the whole HUD pass during the encounter, vanilla interface kept, third party overlays dropped
// RenderGuiOverlayEvent.Pre only reaches overlays in Forge's own registry
// RenderGuiEvent.Pre reaches nothing - the cancel flag is read while listeners already run so a drawing handler has drawn
// filtering the listener array at dispatch works but re-derives whose listener is whose every frame,cost showed as HUD stutter
// ForgeGui.render is the pass itself: event, inherited Gui.render and the overlay walk in one method,cancel skips all three
// fires only while the encounter runs and a third party overlay is registered, if the vanilla pass is unreachable suppression is abandoned
// remap=false since ForgeGui is not obfuscated so the target keeps its real runtime id m_93030_ read off 1.19.2
@Mixin(value = ForgeGui.class, remap = false)
public abstract class ForgeGuiOverlayMixin {

    @Inject(method = "m_93030_", at = @At("HEAD"), cancellable = true, remap = false)
    private void transcenmod$suppressForeignHud(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (!HudSuppression.isEncounterPresent()) {
            return;
        }
        if (SuperHudRenderer.isUnavailable()) {
            // cannot redraw the vanilla pass,so suppressing leaves a blank screen - drawing everything is the lesser fault
            return;
        }
        SuperHudRenderer.render((Gui) (Object) this, poseStack, partialTick);
        ci.cancel();
    }
}
