package com.example.transcenmod.client;

import net.minecraft.entity.LivingEntity;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class DoomedRenderer
{


    private static final int HURT_LATCH = 10;



    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event)
    {
        if (!AnnihilationClientState.isDoomed(event.getEntity().getUniqueID()))
        {
            return;
        }
        LivingEntity living = event.getEntity();

        living.hurtTime = HURT_LATCH;
        living.deathTime = HURT_LATCH;

    }
}
