package com.example.transcenmod.client;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class DoomedRenderer
{

    private static final int HURT_LATCH = 10;



    // render time not tick time - LivingEntity#tick decrements hurtTime,so a value written there is
    // already zero when getOverlayCoords reads it and the model samples the white texel
    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event)
    {
        if (!AnnihilationClientState.isDoomed(event.getEntity().getUUID()))
        {
            return;
        }
        LivingEntity living = event.getEntity();

        living.hurtTime = HURT_LATCH;
        living.deathTime = HURT_LATCH;

    }
}
