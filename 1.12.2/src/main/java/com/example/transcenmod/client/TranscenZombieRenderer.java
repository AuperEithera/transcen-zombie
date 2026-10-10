package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderZombie;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class TranscenZombieRenderer extends RenderZombie {

    // built from MODID so the path cannot drift from it
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(TranscenMod.MODID, "textures/entity/transcenzombie.png");

    public TranscenZombieRenderer(RenderManager manager) {
        super(manager);
    }

    // Render#bindEntityTexture reads this, and RenderZombie declares it as
    // protected ResourceLocation getEntityTexture(EntityZombie). The SRG name func_110775_a would declare a
    // new method instead and fail the build on the abstract parent.
    @Override
    protected ResourceLocation getEntityTexture(EntityZombie entity) {
        return TEXTURE;
    }
}
