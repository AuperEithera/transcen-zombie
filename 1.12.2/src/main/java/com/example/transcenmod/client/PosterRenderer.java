package com.example.transcenmod.client;

import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

// Draws the fake death screen straight onto the framebuffer with lwjgl. No Tessellator, no BufferBuilder, no
// GlStateManager and no TextureManager: the png is uploaded to a GL texture by hand and the quad is emitted
// with glBegin/glEnd. 1.12.2 is lwjgl 2 (2.9.4-nightly-20150209 per the version json), so the immediate mode
// calls exist; on lwjgl 3 none of this would compile.
public final class PosterRenderer {

    private PosterRenderer() {}

    private static final String RESOURCE = "/assets/transcenmod/textures/gui/deathgui.png";

    private static int textureId;
    // set when the first attempt fails, so a broken png does not retry and log every frame
    private static boolean textureFailed;
    // one shot, so a working build stays quiet in the log
    private static boolean reported;

    // runs once per game, not once per frame. this is the only way to tell "the texture never uploaded"
    // apart from "the quad was drawn but nothing showed": glGetError is the driver's own verdict, and the
    // id and the source size say whether the upload produced anything to sample.
    private static void reportOnce() {
        if (reported) {
            return;
        }
        reported = true;
        int error = GL11.glGetError();
        System.out.println("[transcenmod] death poster drawn: textureId=" + textureId
                + " source=" + sourceWidth + "x" + sourceHeight
                + " glError=" + error + " (" + errorName(error) + ")");
    }

    private static String errorName(int error) {
        switch (error) {
            case GL11.GL_NO_ERROR: return "no error";
            case GL11.GL_INVALID_ENUM: return "GL_INVALID_ENUM";
            case GL11.GL_INVALID_VALUE: return "GL_INVALID_VALUE";
            case GL11.GL_INVALID_OPERATION: return "GL_INVALID_OPERATION";
            case GL11.GL_OUT_OF_MEMORY: return "GL_OUT_OF_MEMORY";
            default: return "code " + error;
        }
    }

    private static int sourceWidth;
    private static int sourceHeight;

    // Call after the vanilla hud is done. The ortho is built from displayWidth/displayHeight, the physical
    // pixel size, not ScaledResolution: this call sits below minecraft's gui scaling, so the projection has to
    // be set up in the coordinates the framebuffer is actually in. That is the opposite of what a hud drawn
    // through the Tessellator needs, and it is why using the scaled size here would leave the image as a
    // small rectangle in one corner.
    public static void draw(Minecraft mc) {
        ensureTexture();
        if (textureId <= 0) {
            return;
        }

        int width = mc.displayWidth;
        int height = mc.displayHeight;
        if (width <= 0 || height <= 0) {
            return;
        }

        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        // bottom and top swapped so y grows downwards like every other screen coordinate
        GL11.glOrtho(0.0D, (double) width, (double) height, 0.0D, -1.0D, 1.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();

        // every one of these is captured before it is touched. restoring a hardcoded value instead would
        // leave gl and GlStateManager's cache disagreeing, and GlStateManager skips the call when its cache
        // already matches - so the next frame's hud would render with whatever this method left behind.
        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean wasFog = GL11.glIsEnabled(GL11.GL_FOG);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean wasTexture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);

        // blend off is the opacity guarantee: the image's own alpha is never consulted, so there is no way for
        // the world behind to show through, not even where the png is transparent.
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_FOG);
        // cull face is the one that made this invisible. vanilla's own blit emits its quad in the opposite
        // winding to the obvious one (Gui#drawTexturedModalRect goes bottom-left, bottom-right, top-right,
        // top-left), which is how it survives a cull face left enabled by the world pass. the same winding is
        // used below, and culling is switched off as well so the quad cannot be dropped either way.
        GL11.glDisable(GL11.GL_CULL_FACE);
        // fixed function texturing is off unless it is asked for. without this the quad is drawn as flat
        // colour and the texture is never sampled at all, which looks exactly like "nothing rendered".
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        // winding copied from Gui#drawTexturedModalRect: bottom-left, bottom-right, top-right, top-left.
        // t=0 is the top of the image and is bound to the y=0 edge, so the poster is not upside down.
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0.0F, 1.0F);
        GL11.glVertex2f(0.0F, (float) height);
        GL11.glTexCoord2f(1.0F, 1.0F);
        GL11.glVertex2f((float) width, (float) height);
        GL11.glTexCoord2f(1.0F, 0.0F);
        GL11.glVertex2f((float) width, 0.0F);
        GL11.glTexCoord2f(0.0F, 0.0F);
        GL11.glVertex2f(0.0F, 0.0F);
        GL11.glEnd();

        reportOnce();

        // gl state is global. everything above is put back exactly as it was found, or the next thing
        // minecraft renders inherits a projection of 0..displayWidth and comes out wrong.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        restore(GL11.GL_TEXTURE_2D, wasTexture);
        restore(GL11.GL_CULL_FACE, wasCull);
        restore(GL11.GL_FOG, wasFog);
        restore(GL11.GL_ALPHA_TEST, wasAlpha);
        restore(GL11.GL_BLEND, wasBlend);
        restore(GL11.GL_LIGHTING, wasLighting);
        restore(GL11.GL_DEPTH_TEST, wasDepth);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
    }

    private static void restore(int capability, boolean enabled) {
        if (enabled) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }

    // Reads the png out of the jar and uploads it. getResourceAsStream, not a File: the resource is a file on
    // disk in the dev workspace and an entry inside the jar once built, and only the stream form works for
    // both. Alpha is forced to 0xff on the way in so the texture itself is opaque as well as being drawn with
    // blending off.
    private static void ensureTexture() {
        if (textureId > 0 || textureFailed) {
            return;
        }
        InputStream stream = null;
        try {
            stream = PosterRenderer.class.getResourceAsStream(RESOURCE);
            if (stream == null) {
                textureFailed = true;
                System.err.println("[transcenmod] death poster not found at " + RESOURCE);
                return;
            }
            BufferedImage image = ImageIO.read(stream);
            if (image == null) {
                textureFailed = true;
                System.err.println("[transcenmod] death poster is not a readable image");
                return;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            sourceWidth = width;
            sourceHeight = height;
            int[] argb = new int[width * height];
            image.getRGB(0, 0, width, height, argb, 0, width);

            ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
            for (int i = 0; i < argb.length; i++) {
                int pixel = argb[i];
                buffer.put((byte) ((pixel >> 16) & 0xFF));
                buffer.put((byte) ((pixel >> 8) & 0xFF));
                buffer.put((byte) (pixel & 0xFF));
                buffer.put((byte) 0xFF);
            }
            buffer.flip();

            IntBuffer ids = BufferUtils.createIntBuffer(1);
            GL11.glGenTextures(ids);
            textureId = ids.get(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            // logged once at upload time as well: an id of 0 or an error here means the draw call has nothing
            // to sample, which is a different fault from a projection that puts the quad off screen
            int uploadError = GL11.glGetError();
            System.out.println("[transcenmod] death poster uploaded: id=" + textureId
                    + " size=" + width + "x" + height + " bytes=" + (width * height * 4)
                    + " glError=" + uploadError + " (" + errorName(uploadError) + ")");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        } catch (Throwable failure) {
            textureFailed = true;
            System.err.println("[transcenmod] death poster upload failed: " + failure);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
