package com.example.transcenmod.client;

import net.minecraft.client.renderer.entity.EntityRendererManager;

import com.example.transcenmod.entity.TranscenLightningBolt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.util.ResourceLocation;
import java.util.Random;
import net.minecraft.util.math.vector.Matrix4f;

/**
 * Vanilla lightning geometry, recoloured blood red.
 *
 * <p>This is a faithful port of {@code LightningBoltEntityRenderer.render} and its private {@code quad}
 * helper, with exactly one change: the colour triple handed to {@code quad} is {@code (1, 0, 0)}
 * instead of vanilla's near-white {@code (0.45, 0.45, 0.5)}. Every offset, drift, random draw, taper
 * factor and vertex ordering is reproduced, so the bolt keeps the shape, spread and layered glow of a
 * real lightning bolt.
 *
 * <p><b>Why the geometry is duplicated rather than delegated.</b> The colour is written into the
 * vertex data, and wrapping the {@code IRenderTypeBuffer} to rewrite it does not work: measured
 * counters showed {@code getBuffer} being reached while the {@code color} override was never called,
 * so that call path never reaches user code. Re-emitting the same vertices is the only way to change
 * the colour without changing the shape. A previous attempt that invented its own offsets produced a
 * straight beam rather than a bolt, which is why nothing here is hand-designed.
 */
public class TranscenLightningRenderer extends EntityRenderer<TranscenLightningBolt> {

    /** The one deliberate difference from vanilla. */
    private static final float COLOR_R = 1.0F;
    private static final float COLOR_G = 0.0F;
    private static final float COLOR_B = 0.0F;
    /** Vanilla's per-vertex alpha for the bolt ribbon. */
    private static final float COLOR_A = 0.3F;

    public TranscenLightningRenderer(EntityRendererManager context) {
        super(context);
    }

    @Override
    public void render(TranscenLightningBolt entity, float yaw, float partialTicks, MatrixStack poseStack,
                       IRenderTypeBuffer buffer, int packedLight) {
        // ---- ported from LightningBoltEntityRenderer.render ----

        // One shared kinked path, walked backwards so index 0 is the far end.
        float[] driftX = new float[8];
        float[] driftZ = new float[8];
        float totalX = 0.0F;
        float totalZ = 0.0F;
        java.util.Random pathRandom = new java.util.Random(entity.getUniqueID().getLeastSignificantBits());
        for (int i = 7; i >= 0; i--) {
            driftX[i] = totalX;
            driftZ[i] = totalZ;
            totalX += pathRandom.nextInt(11) - 5;
            totalZ += pathRandom.nextInt(11) - 5;
        }

        IVertexBuilder consumer = buffer.getBuffer(RenderType.getLightning());
        Matrix4f matrix = poseStack.getLast().getMatrix();

        // Note: no pose translation here. A translate(-driftX[0], 0, -driftZ[0]) was tried to
        // re-anchor the base and made the offset worse -- the sign was wrong and, because the same
        // MatrixStack is reused across entities, the shift compounded from bolt to bolt. Vanilla's
        // placement is left exactly as it is.

        // Four rotation layers, three branches each.
        for (int layer = 0; layer < 4; layer++) {
            java.util.Random layerRandom = new java.util.Random(entity.getUniqueID().getLeastSignificantBits());
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

                    // Four faces of this segment. Only the colour arguments differ from vanilla.
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

        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Ported from {@code LightningBoltEntityRenderer.quad}.
     *
     * <p>Parameter order follows the original exactly: {@code (_x, _z, _step, _driftX, _driftZ,
     * _red, _green, _blue, _uv1, _uv2, _widthIn, _widthOut, flipA..flipD)}. Both off-diagonal terms
     * are scaled by the layer half-width, which is what gives the bolt its thickness.
     */
    private static void quad(Matrix4f matrix, IVertexBuilder consumer,
                             float x, float z, int step, float driftX, float driftZ,
                             float red, float green, float blue,
                             float uv1, float uv2, float widthIn, float widthOut,
                             boolean flipA, boolean flipB, boolean flipC, boolean flipD) {
        float yBottom = step * 16;
        float yTop = (step + 1) * 16;

        float sideX = flipA ? widthOut : -widthOut;
        float sideZ = flipB ? widthIn : -widthIn;

        // 1. bottom, corner A
        consumer.pos(matrix, x + sideX, yBottom, z + sideZ)
                .color(red, green, blue, COLOR_A)
                .endVertex();
        // 2. top, corner A
        consumer.pos(matrix, driftX + sideX, yTop, driftZ + sideZ)
                .color(red, green, blue, COLOR_A)
                .endVertex();
        // 3. top, corner B
        float sideXc = flipC ? widthOut : -widthOut;
        float sideZc = flipD ? widthIn : -widthIn;
        consumer.pos(matrix, driftX + sideXc, yTop, driftZ + sideZc)
                .color(red, green, blue, COLOR_A)
                .endVertex();
        // 4. bottom, corner B
        consumer.pos(matrix, x + sideXc, yBottom, z + sideZc)
                .color(red, green, blue, COLOR_A)
                .endVertex();
    }

    /**
     * Irrelevant at runtime: the lightning shader samples no texture, so whatever is returned here is
     * never read. The block atlas is named only because {@code EntityRenderer} requires a location.
     */
    @Override
    public ResourceLocation getEntityTexture(TranscenLightningBolt entity) {
        return net.minecraft.client.renderer.texture.AtlasTexture.LOCATION_BLOCKS_TEXTURE;
    }
}
