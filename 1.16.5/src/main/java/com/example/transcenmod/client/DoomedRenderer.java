package com.example.transcenmod.client;

import net.minecraft.entity.LivingEntity;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Keeps a doomed entity's red overlay latched until it is actually removed.
 *
 * <p>This is the live implementation, not a record. An earlier revision tinted doomed entities with
 * {@code RenderSystem.setShaderColor} in
 * {@code RenderLivingEvent.Pre}. That has no effect on a living entity: vanilla never tints a hurt
 * mob through the shader colour. It packs the overlay texture coordinate instead --
 * {@code LivingEntityRenderer#getOverlayCoords} ->
 * {@code OverlayTexture.pack(u(0), v(hurtTime > 0 || deathTime > 0))} -- and a positive
 * {@code hurtTime} makes the model sample the red overlay texel.
 *
 * <p>The working implementation now lives in
 * {@link ClientEvents#keepDoomedShellsRed(net.minecraft.client.Minecraft)}, which re-latches
 * {@code hurtTime} every client tick.
 */
public class DoomedRenderer {

    /** A positive value that survives until the overlay coordinate is packed. */
    private static final int HURT_LATCH = 10;



    /**
     * Latches the red hurt overlay immediately before the entity is drawn.
     *
     * <p>This has to happen here rather than in the client tick. {@code LivingEntity.tick()} decrements
     * {@code hurtTime} every tick, so a value written during the tick is already back to zero by the
     * time {@code LivingEntityRenderer#getOverlayCoords} reads it, and the model samples the white
     * overlay instead of the red one. Writing it in the render event means the value is still positive
     * at the moment the overlay coordinate is packed.
     */
    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!AnnihilationClientState.isDoomed(event.getEntity().getUniqueID())) {
            return;
        }
        LivingEntity living = event.getEntity();

        // Both fields that OverlayTexture#v reads, latched as late as possible. hurtTime drives the
        // red damage overlay; deathTime is forced alongside it so the overlay stays latched even if
        // something resets the hurt timer between the tick and the draw.
        living.hurtTime = HURT_LATCH;
        living.deathTime = HURT_LATCH;

        // Vanilla tints a hurt mob through the overlay texture, not through the shader colour, so
        // this is the only piece of state that needs setting. It is applied here rather than in the
        // client tick because LivingEntity#baseTick decrements hurtTime every tick.
    }
}
