package com.example.transcenmod.client;

import com.example.transcenmod.entity.TranscenZombie;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Zombie;

/**
 * Renders the Transcendence Zombie.
 *
 * <p>{@code ZombieRenderer} is {@code public} in vanilla, so this class exists mostly to give the
 * entity its own renderer slot. The texture override points at the mod's own asset path; drop a PNG
 * at {@code assets/transcenmod/textures/entity/transcenzombie.png} to use it.
 */
public class TranscenZombieRenderer extends ZombieRenderer {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("transcenmod:textures/entity/transcenzombie.png");

    public TranscenZombieRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(Zombie entity) {
        return TEXTURE;
    }
}
