package com.example.transcenmod.client;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// red hurt overlay stays latched until the entity is actually gone
// a class and not a record, the live latch is ClientEvents#keepDoomedShellsRed on every client tick
// RenderSystem.setShaderColor in RenderLivingEvent.Pre does nothing,vanilla never tints a hurt mob through the shader colour
// it packs an overlay texture coordinate instead - LivingEntityRenderer#getOverlayCoords then OverlayTexture.pack(u(0), v(hurtTime > 0 || deathTime > 0))
// a positive hurtTime is what makes the model sample the red texel
public class DoomedRenderer {

    private static final int HURT_LATCH = 10;



    // latched right before the draw,not from the client tick
    // LivingEntity.tick() decrements hurtTime every tick so a value written during the tick is already 0 when the renderer reads it
    // the model samples the white overlay, not the red one
    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!AnnihilationClientState.isDoomed(event.getEntity().getUUID())) {
            return;
        }
        LivingEntity living = event.getEntity();

        // both fields OverlayTexture#v reads - hurtTime drives the red damage overlay
        // deathTime is forced alongside it,so a reset hurt timer between tick and draw cannot unlatch the tint
        living.hurtTime = HURT_LATCH;
        living.deathTime = HURT_LATCH;

    }
}
