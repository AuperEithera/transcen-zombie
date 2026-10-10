package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderZombie;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

// Extending RenderZombie rather than RenderLiving is the trick: the parent already builds the biped model,
// the armour layers, the name tag and the baby scaling, so one override buys a fully featured renderer.
//
// The inversion lives in the texture, not in the blend state. ONE_MINUS_DST_COLOR against ZERO was tried in
// doRender and changed nothing visible - the model came out looking exactly like a vanilla zombie. The png is
// the vanilla zombie skin with each channel taken to 255 - v, and a texture cannot be ignored the way a blend
// tweak can.
//
// @SideOnly(Side.CLIENT) because the class extends a client-only vanilla type; without it a dedicated server
// loading the class would fail on the missing parent.
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
