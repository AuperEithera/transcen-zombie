package com.example.transcenmod.client;

import com.example.transcenmod.entity.TranscenZombie;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Zombie;

public class TranscenZombieRenderer extends ZombieRenderer
{

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("transcenmod:textures/entity/transcenzombie.png");

    public TranscenZombieRenderer(EntityRendererProvider.Context context)
    {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(Zombie entity)
    {
        return TEXTURE;
    }
}
