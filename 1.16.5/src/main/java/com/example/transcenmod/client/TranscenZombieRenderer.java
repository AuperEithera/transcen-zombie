package com.example.transcenmod.client;

import net.minecraft.client.renderer.entity.EntityRendererManager;

import com.example.transcenmod.entity.TranscenZombie;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.monster.ZombieEntity;

public class TranscenZombieRenderer extends ZombieRenderer
{

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("transcenmod:textures/entity/transcenzombie.png");

    public TranscenZombieRenderer(EntityRendererManager context)
    {
        super(context);
    }

    @Override
    public ResourceLocation getEntityTexture(ZombieEntity entity)
    {
        return TEXTURE;
    }
}
