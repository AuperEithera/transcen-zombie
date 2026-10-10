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

// keeps the vanilla HUD during an encounter lets nothing else draw
// every third party HUD reaches the screen through just two private methods here, pre and post
// pre returns whether a listener cancelled the element,so false with no event fired keeps vanilla drawing and runs nobody
// post is where add on overlays draw on top of the HUD,silencing both drops only the event driven ones
// nothing is named so the boundary is the event,not a mod list
// remap=false throughout - these are Forge members added after obfuscation,names already literal,refmap has nothing to resolve
@Mixin(value = ForgeIngameGui.class, remap = false)
public abstract class HudSuppressionMixin {

    @Inject(method = "pre", at = @At("HEAD"), cancellable = true, remap = false)
    private void transcenmod$suppressOverlayPre(RenderGameOverlayEvent.ElementType type, MatrixStack matrixStack,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (HudSuppression.isEncounterPresent()) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }

    // this mod's own bar rides RenderGameOverlayEvent.Post too,so silencing post would silence it
    // redrawn here instead - encounter keeps its overlay and no third party listener gets the event
    // HOTBAR is the trigger,last status element so the bar lands above the finished vanilla HUD
    @Inject(method = "post", at = @At("HEAD"), cancellable = true, remap = false)
    private void transcenmod$suppressOverlayPost(RenderGameOverlayEvent.ElementType type, MatrixStack matrixStack,
                                                 CallbackInfo ci) {
        if (HudSuppression.isEncounterPresent()) {
            if (type == RenderGameOverlayEvent.ElementType.HOTBAR) {
                com.example.transcenmod.client.ClientEvents.renderOwnBarDirectly(matrixStack);
            }
            ci.cancel();
        }
    }
}
