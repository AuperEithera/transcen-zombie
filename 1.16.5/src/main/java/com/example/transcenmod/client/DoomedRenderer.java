package com.example.transcenmod.client;

import net.minecraft.entity.LivingEntity;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// shader colour does nothing here,vanilla packs an overlay coord instead
// getOverlayCoords -> v(hurtTime > 0 || deathTime > 0) so a positive hurtTime is the whole trick
// re-latched in ClientEvents#keepDoomedShellsRed every client tick
public class DoomedRenderer {


    private static final int HURT_LATCH = 10;



    // must be latched at draw time not in the client tick. LivingEntity.tick() decrements hurtTime
    // so a tick time write is already 0 when the overlay coord gets packed,white texel instead of red
    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!AnnihilationClientState.isDoomed(event.getEntity().getUniqueID())) {
            return;
        }
        LivingEntity living = event.getEntity();

        // both fields OverlayTexture#v reads. deathTime forced too in case the hurt timer gets
        // reset between the tick and the draw
        living.hurtTime = HURT_LATCH;
        living.deathTime = HURT_LATCH;

        // nothing else needs setting - the overlay texture carries the red,not the shader colour
    }
}
