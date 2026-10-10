package com.example.transcenmod.client.bar;

import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;

public class TranscenBossBar {

    private static final ResourceLocation BARS =
            new ResourceLocation("transcenmod", "textures/gui/boss_bars.png");

    private static final int BAR_WIDTH = 182;

    // The sheet holds two strips of STRIP_HEIGHT rows each and nothing below them, so a taller bar cannot be
    // sampled directly: the background blit would run straight into the fill strip underneath it.
    // drawTexturedModalRect gives source and destination one shared size, so the height comes from scaling
    // the strip vertically in render instead.
    private static final int STRIP_HEIGHT = 5;
    private static final int BAR_SCALE = 3;

    private static final int V_BACKGROUND = 0;

    private static final int V_BAR = 5;

    private static final int SHEET_WIDTH = 256;
    private static final int SHEET_HEIGHT = 256;

    private static final int[] COLORS = {
            0xFF0000, 0xFFFF00, 0x0000FF, 0xFFFFFF, 0x800080, 0x00FF00, 0x00FFFF
    };

    private static final int COLOR_INTERVAL = 15;

    private static final int TITLE_WHITE_TICKS = 4;

    private static final int TITLE_COLOR_TICKS = 2;

    private static final int[] TITLE_POP_COLORS = {
            0xFF0000, 0x0000FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x00FF00
    };

    private final Random random = new Random();

    private int titleColor = 0xFFFFFF;

    private int titleTicksLeft = TITLE_WHITE_TICKS;

    private boolean titleShowingWhite = true;

    private float displayed = 1.0F;

    private float target = 1.0F;

    private boolean visible;

    private int colorTicks;

    private static final Gui BLIT = new Gui();

    public void setProgress(float progress) {
        this.target = MathHelper.clamp(progress, 0.0F, 1.0F);
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) {
            this.displayed = this.target;
            this.colorTicks = 0;
        }
    }

    public boolean isVisible() {
        return this.visible;
    }

    public void tick() {
        float delta = this.target - this.displayed;
        if (Math.abs(delta) < 0.005F) {
            this.displayed = this.target;
        } else {
            this.displayed += delta * 0.1F;
        }
        this.colorTicks++;
        this.advanceTitleColor();
    }

    private void advanceTitleColor() {
        if (--this.titleTicksLeft > 0) {
            return;
        }
        if (this.titleShowingWhite) {
            this.titleColor = TITLE_POP_COLORS[this.random.nextInt(TITLE_POP_COLORS.length)];
            this.titleTicksLeft = TITLE_COLOR_TICKS;
            this.titleShowingWhite = false;
        } else {
            this.titleColor = 0xFFFFFF;
            this.titleTicksLeft = TITLE_WHITE_TICKS;
            this.titleShowingWhite = true;
        }
    }

    // the width is taken here instead of being passed in, so it cannot be passed wrong
    // displayWidth is physical pixels and the gui works in scaled ones - feeding it in put the bar at
    // x=1189 on a 2560 wide fullscreen window,off screen, and hard against the edge in a small window
    public void render(String title, int y) {
        if (!this.visible) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        int scaledWidth = new ScaledResolution(mc).getScaledWidth();
        int x = scaledWidth / 2 - BAR_WIDTH / 2;

        // The strip is scaled up rather than sampled taller - see STRIP_HEIGHT. The matrix is popped before
        // the title so the text keeps its own scale, and y is divided back so the scaled bar still starts
        // exactly where the caller asked.
        GlStateManager.pushMatrix();
        GlStateManager.scale(1.0F, (float) BAR_SCALE, 1.0F);
        int drawY = y / BAR_SCALE;

        // White before the texture, or the blend left over from a previous colour would tint the sheet.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture(BARS);
        BLIT.drawTexturedModalRect(x, drawY, 0, V_BACKGROUND, BAR_WIDTH, STRIP_HEIGHT);

        this.applyCycleColor();
        int filled = (int) (this.displayed * BAR_WIDTH);
        if (filled > 0) {
            // Drawn as two blits of the same strip rather than one, so the source rectangle can be given
            // the same width as the destination. Stretching a single one-pixel-wide source across the
            // whole strip would smear the gradient the sheet carries across its length.
            BLIT.drawTexturedModalRect(x, drawY, 0, V_BAR, filled, STRIP_HEIGHT);
            BLIT.drawTexturedModalRect(x + filled, drawY, BAR_WIDTH - filled, V_BAR,
                    BAR_WIDTH - filled, STRIP_HEIGHT);
        }

        GlStateManager.popMatrix();

        // Back to white for the text: FontRenderer multiplies by the current colour, so a leftover cycle
        // colour would tint the title twice.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        float textWidth = mc.fontRenderer.getStringWidth(title);
        float startX = scaledWidth / 2.0F - textWidth / 2.0F;
        drawColouredTitle(mc, title, startX, y - 9.0F, this.currentTitleColor());
    }

    private void applyCycleColor() {
        int rgb = COLORS[(this.colorTicks / COLOR_INTERVAL) % COLORS.length];
        GlStateManager.color(((rgb >> 16) & 0xFF) / 255.0F,
                ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F,
                1.0F);
    }

    private int currentTitleColor() {
        return this.titleColor;
    }

    private static void drawColouredTitle(Minecraft mc, String title, float x, float y, int color) {
        float cursor = x;
        for (int i = 0; i < title.length(); i++) {
            String character = String.valueOf(title.charAt(i));
            mc.fontRenderer.drawStringWithShadow(character, cursor, y, color);
            cursor += mc.fontRenderer.getCharWidth(title.charAt(i));
        }
    }
}
