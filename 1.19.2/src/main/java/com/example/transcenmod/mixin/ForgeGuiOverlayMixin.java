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

/**
 * Replaces the whole HUD pass during the encounter, so nothing but the game's own interface is drawn.
 *
 * <p><b>Why the event approaches were abandoned.</b> Three were tried. Cancelling
 * {@code RenderGuiOverlayEvent.Pre} reaches only overlays in Forge's registry. Cancelling
 * {@code RenderGuiEvent.Pre} reaches nothing at all -- a cancel flag is read while the listeners are
 * already running, so a handler that draws has drawn before anyone can react. Filtering the listener
 * array at dispatch time does work, but it is the wrong layer: it re-derives "whose listener is this"
 * for every event on every frame, and the cost showed up immediately as a stuttering HUD.
 *
 * <p><b>What this does instead.</b> {@code ForgeGui.render} <i>is</i> the HUD pass. It raises
 * {@code RenderGuiEvent.Pre}, calls the inherited {@code Gui.render} for the vanilla interface, and then
 * walks the overlay registry -- all in one method. Cancelling that method means none of the three
 * happens, which is exactly "no third-party rendering", at the cost of one branch per frame rather than
 * a scan per frame.
 *
 * <p>The vanilla interface is then drawn by invoking the inherited implementation directly. That is the
 * piece the event approaches could not do: the game's own HUD is a method call rather than a listener, so
 * it can be kept while everything routed through events is skipped.
 *
 * <p><b>Scope.</b> Only while the encounter is running <i>and</i> only when a third-party overlay is
 * actually registered, so with no such mod installed the frame is exactly what it would have been. If the
 * vanilla pass cannot be reached, suppression is abandoned rather than leaving a blank screen.
 *
 * <p><b>Naming.</b> {@code remap = false} because {@code ForgeGui} is Forge's own class and is not
 * obfuscated. That also disables the mapping lookup, so the target is spelled with the id the method
 * really carries at runtime -- {@code m_93030_}, read off the 1.19.2 bytecode rather than assumed.
 */
@Mixin(value = ForgeGui.class, remap = false)
public abstract class ForgeGuiOverlayMixin {

    @Inject(method = "m_93030_", at = @At("HEAD"), cancellable = true, remap = false)
    private void transcenmod$suppressForeignHud(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (!HudSuppression.isEncounterPresent()) {
            return;
        }
        if (SuperHudRenderer.isUnavailable()) {
            // The vanilla pass cannot be redrawn, so suppressing would leave an empty screen. Drawing
            // everything is the lesser fault.
            return;
        }
        SuperHudRenderer.render((Gui) (Object) this, poseStack, partialTick);
        ci.cancel();
    }
}
