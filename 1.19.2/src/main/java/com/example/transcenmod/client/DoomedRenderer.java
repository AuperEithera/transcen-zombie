package com.example.transcenmod.client;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// RenderSystem.setShaderColor in RenderLivingEvent.Pre does nothing,vanilla never tints a hurt mob through the shader colour
public class DoomedRenderer
{

    private static final int HURT_LATCH = 10;



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
