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

/**
 * Keeps the vanilla HUD during an encounter and lets nothing else draw.
 *
 * <p>This is the 1.16.5 shape of the rule "block every third-party rendering, preserve the vanilla HUD".
 * The generation's HUD entry point is Forge's {@code ForgeIngameGui}, which overrides
 * {@code IngameGui.renderIngameGui} to fire {@code RenderGameOverlayEvent} around each element and then
 * draws that element itself. Every third-party HUD therefore reaches the screen through exactly two
 * private methods on this class:
 *
 * <ul>
 *   <li>{@code pre(type, matrixStack)} fires {@code RenderGameOverlayEvent.Pre} and reports back whether a
 *       listener cancelled the element. Its return value is what decides whether the vanilla element is
 *       drawn, so returning {@code false} without firing the event keeps vanilla drawing and gives no
 *       listener a chance to run.</li>
 *   <li>{@code post(type, matrixStack)} fires {@code RenderGameOverlayEvent.Post}, which is where add-on
 *       overlays draw themselves on top of the HUD.</li>
 * </ul>
 *
 * <p>Silencing both while an encounter is present therefore removes every event-driven overlay while
 * leaving {@code renderIngameGui}'s own vanilla drawing untouched. Nothing is named, so no third-party mod
 * has to be known about for this to hold; the boundary is the event, not a list.
 *
 * <p>{@code remap = false} throughout: these are Forge members, added to the class after obfuscation, so
 * their names are already literal and the refmap has nothing to resolve.
 */
@Mixin(value = ForgeIngameGui.class, remap = false)
public abstract class HudSuppressionMixin {

    /**
     * Suppresses the per-element pre-event. A {@code false} return means "nobody cancelled this element",
     * which is what lets the vanilla HUD keep drawing.
     */
    @Inject(method = "pre", at = @At("HEAD"), cancellable = true, remap = false)
    private void transcenmod$suppressOverlayPre(RenderGameOverlayEvent.ElementType type, MatrixStack matrixStack,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (HudSuppression.isEncounterPresent()) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }

    /**
     * Suppresses the per-element post-event, which is the hook every add-on overlay draws through.
     *
     * <p>This mod's own bar is drawn from a {@code RenderGameOverlayEvent.Post} handler, so silencing the
     * event would silence the bar as well. It is therefore redrawn here instead: the encounter keeps its
     * own overlay, and no third-party listener is given the event either way.
     *
     * <p>{@code HOTBAR} is the trigger because it is the last of the status elements, so the bar lands
     * above the finished vanilla HUD rather than underneath whatever is drawn later.
     */
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
