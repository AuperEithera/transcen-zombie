package com.example.transcenmod.client;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// not setShaderColor - vanilla never tints a living entity through the shader colour
// it packs the hurt texel instead,OverlayTexture.pack(u(0),hurtTime>0||deathTime>0)
// live path is ClientEvents.keepDoomedShellsRed
public class DoomedRenderer {

    // has to stay positive until the overlay coord is packed
    private static final int HURT_LATCH = 10;



    // render time not tick time - LivingEntity#tick decrements hurtTime,so a value written there is
    // already zero when getOverlayCoords reads it and the model samples the white texel
    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!AnnihilationClientState.isDoomed(event.getEntity().getUUID())) {
            return;
        }
        LivingEntity living = event.getEntity();

        // both fields OverlayTexture#v reads,hurtTime is the red overlay,deathTime forced with it so
        // a reset between tick and draw cannot drop the latch
        living.hurtTime = HURT_LATCH;
        living.deathTime = HURT_LATCH;

    }
}
