package com.example.transcenmod.mixin;

import com.example.transcenmod.client.HudSuppression;
import com.example.transcenmod.client.SuperHudRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// ForgeGui.render is the whole HUD pass: RenderGuiEvent.Pre, the vanilla Gui.render call, the overlay registry walk
// cancelling it kills all three, so suppression costs one branch per frame,no per frame listener scan
// event routes lost: RenderGuiOverlayEvent.Pre sees only registered overlays, a RenderGuiEvent.Pre cancel is read after its listeners drew
// listener array filtering does work but re-derives whose listener this is on every event and the HUD stuttered at once
// vanilla interface still draws because inherited Gui.render is invoked directly - a method call,not a listener
// only while the encounter runs. if the vanilla pass is unreachable,suppression is dropped rather than blanking the screen
// remap=false - ForgeGui is Forge's own unobfuscated class so the mapping lookup is skipped and the target needs the runtime id
// m_280421_ on 1.20.1, read off this version's bytecode; 1.19.2 carries a different id and a different parameter type
@Mixin(value = ForgeGui.class, remap = false)
public abstract class ForgeGuiOverlayMixin {

    @Inject(method = "m_280421_", at = @At("HEAD"), cancellable = true, remap = false)
    private void transcenmod$suppressForeignHud(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        if (!HudSuppression.isEncounterPresent()) {
            return;
        }
        if (SuperHudRenderer.isUnavailable()) {
            // vanilla pass cannot be redrawn, so suppressing leaves an empty screen - drawing everything is the lesser fault
            return;
        }
        SuperHudRenderer.render((Gui) (Object) this, graphics, partialTick);
        ci.cancel();
    }
}
