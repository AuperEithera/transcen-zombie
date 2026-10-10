package com.example.transcenmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LightningBolt;
import org.joml.Matrix4f;

// vanilla geometry recoloured blood red, only the colour triple differs from LightningBoltRenderer
// wrapping the MultiBufferSource cannot work - getBuffer is reached but the color override never is
// so the vertices are re-emitted here, same offsets and draws as vanilla
// invented offsets were tried once and produced a straight beam,not a bolt - do not hand design these
public class TranscenLightningRenderer extends EntityRenderer<LightningBolt> {

    // vanilla is near white 0.45 0.45 0.5
    private static final float COLOR_R = 1.0F;
    private static final float COLOR_G = 0.0F;
    private static final float COLOR_B = 0.0F;
    private static final float COLOR_A = 0.3F;

    public TranscenLightningRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(LightningBolt entity, float yaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        // ported from LightningBoltRenderer.render

        // built backwards so index 0 holds the far end
        float[] driftX = new float[8];
        float[] driftZ = new float[8];
        float totalX = 0.0F;
        float totalZ = 0.0F;
        RandomSource pathRandom = RandomSource.create(entity.seed);
        for (int i = 7; i >= 0; i--) {
            driftX[i] = totalX;
            driftZ[i] = totalZ;
            totalX += pathRandom.nextInt(11) - 5;
            totalZ += pathRandom.nextInt(11) - 5;
        }

        VertexConsumer consumer = buffer.getBuffer(RenderType.lightning());
        Matrix4f matrix = poseStack.last().pose();

        // no pose translation here - a translate(-driftX[0], 0, -driftZ[0]) was tried, the sign was
        // wrong and the reused PoseStack compounded the shift from bolt to bolt,vanilla placement stays

        // 4 rotation layers, 3 branches each
        for (int layer = 0; layer < 4; layer++) {
            RandomSource layerRandom = RandomSource.create(entity.seed);
            for (int branch = 0; branch < 3; branch++) {
                int top = branch > 0 ? 7 - branch : 7;
                int span = branch > 0 ? top - 2 : 0;

                float x = driftX[top] - totalX;
                float z = driftZ[top] - totalZ;

                for (int step = top; step >= span; step--) {
                    float x0 = x;
                    float z0 = z;

                    if (branch == 0) {
                        x += layerRandom.nextInt(11) - 5;
                        z += layerRandom.nextInt(11) - 5;
                    } else {
                        x += layerRandom.nextInt(31) - 15;
                        z += layerRandom.nextInt(31) - 15;
                    }

                    float u = 0.5F;
                    float v = 0.45F;
                    float u2 = 0.45F;
                    float v2 = 0.5F;

                    float widthIn = 0.1F + layer * 0.2F;
                    if (branch == 0) {
                        widthIn *= step * 0.1F + 1.0F;
                    }
                    float widthOut = 0.1F + layer * 0.2F;
                    if (branch == 0) {
                        widthOut *= (step - 1) * 0.1F + 1.0F;
                    }

                    // 4 faces per segment, all args but the colour match vanilla
                    quad(matrix, consumer, x0, z0, step, x, z, COLOR_R, COLOR_G, COLOR_B,
                            u, v, widthIn, widthOut, false, false, true, false);
                    quad(matrix, consumer, x0, z0, step, x, z, COLOR_R, COLOR_G, COLOR_B,
                            u, v, widthIn, widthOut, true, false, true, true);
                    quad(matrix, consumer, x0, z0, step, x, z, COLOR_R, COLOR_G, COLOR_B,
                            u, v, widthIn, widthOut, true, true, false, true);
                    quad(matrix, consumer, x0, z0, step, x, z, COLOR_R, COLOR_G, COLOR_B,
                            u, v, widthIn, widthOut, false, true, false, false);
                }
            }
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    // ported from LightningBoltRenderer.quad, argument order kept as vanilla
    // both off diagonal terms scale by the layer half width,that is where the thickness comes from
    private static void quad(Matrix4f matrix, VertexConsumer consumer,
                             float x, float z, int step, float driftX, float driftZ,
                             float red, float green, float blue,
                             float uv1, float uv2, float widthIn, float widthOut,
                             boolean flipA, boolean flipB, boolean flipC, boolean flipD) {
        float yBottom = step * 16;
        float yTop = (step + 1) * 16;

        float sideX = flipA ? widthOut : -widthOut;
        float sideZ = flipB ? widthIn : -widthIn;

        consumer.vertex(matrix, x + sideX, yBottom, z + sideZ)
                .color(red, green, blue, COLOR_A)
                .endVertex();
        consumer.vertex(matrix, driftX + sideX, yTop, driftZ + sideZ)
                .color(red, green, blue, COLOR_A)
                .endVertex();
        float sideXc = flipC ? widthOut : -widthOut;
        float sideZc = flipD ? widthIn : -widthIn;
        consumer.vertex(matrix, driftX + sideXc, yTop, driftZ + sideZc)
                .color(red, green, blue, COLOR_A)
                .endVertex();
        consumer.vertex(matrix, x + sideXc, yBottom, z + sideZc)
                .color(red, green, blue, COLOR_A)
                .endVertex();
    }

    // lightning shader samples no texture,this is never read - EntityRenderer just needs a location
    @Override
    public ResourceLocation getTextureLocation(LightningBolt entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
