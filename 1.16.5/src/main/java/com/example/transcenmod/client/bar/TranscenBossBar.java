package com.example.transcenmod.client.bar;
import net.minecraft.client.gui.AbstractGui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import java.util.Random;

// own sheet,not vanilla's - vanilla's first bar sprite is pre-coloured magenta (173,0,135)
// setShaderColor multiplies tint by colour so tinting vanilla collapses to near-black
// that reads as an empty bar,this sheet's sprite is pure white so the tint lands as asked
// layout matches vanilla ten rows a style,five background five bar,182px wide at u=0
// fill is always full - health is a fixed constant and setHealth is a no-op,colour carries identity
// predates GuiGraphics 1.20,so blit plus Font#drawShadow and the title is centred by hand here
public class TranscenBossBar {

    private static final ResourceLocation BARS =
            new ResourceLocation("transcenmod:textures/gui/boss_bars.png");

    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;
    private static final int V_BACKGROUND = 0;
    private static final int V_BAR = 5;
    private static final int SHEET_WIDTH = 256;
    private static final int SHEET_HEIGHT = 256;

    private static final int[] COLORS = {
            0xFF0000,
            0xFFFF00,
            0x0000FF,
            0xFFFFFF,
            0x800080,
            0x00FF00,
            0x00FFFF,
    };
    private static final int COLOR_INTERVAL = 15;

    private static final int TITLE_WHITE_TICKS = 4;
    private static final int TITLE_COLOR_TICKS = 2;

    private static final int[] TITLE_POP_COLORS = {
            0xFF0000,
            0x0000FF,
            0xFFFF00,
            0x00FFFF,
            0x800080,
            0x00FF00,
    };

    private final java.util.Random random = new java.util.Random();

    private int titleColor = 0xFFFFFF;
    private int titleTicksLeft = TITLE_WHITE_TICKS;
    private boolean titleShowingWhite = true;

    private float displayed = 1.0F;
    private float target = 1.0F;
    private boolean visible;
    private int colorTicks;

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
        advanceTitleColor();
    }

    // pulse-hold-pop white then one random colour then white again
    // drawn fresh each cycle so the flash order is never the same twice
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

    public void render(MatrixStack poseStack, ITextComponent title, int screenWidth, int y) {
        if (!this.visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int x = screenWidth / 2 - BAR_WIDTH / 2;

        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture( BARS);
        AbstractGui.blit(poseStack, x, y, 0, V_BACKGROUND, BAR_WIDTH, BAR_HEIGHT,
                SHEET_WIDTH, SHEET_HEIGHT);

        applyCycleColor();
        int filled = (int) (this.displayed * BAR_WIDTH);
        if (filled > 0) {
            // two blits so the rounded caps survive a partial width,same as vanilla
            AbstractGui.blit(poseStack, x, y, 0, V_BAR, filled, BAR_HEIGHT,
                    SHEET_WIDTH, SHEET_HEIGHT);
            AbstractGui.blit(poseStack, x + filled, y, BAR_WIDTH - filled, V_BAR,
                    BAR_WIDTH - filled, BAR_HEIGHT, SHEET_WIDTH, SHEET_HEIGHT);
        }

        // no notch overlay - the sheet has no notch row,so nothing to draw over the fill
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        IFormattableTextComponent text = flashingTitle(title);
        int textWidth = mc.fontRenderer.getStringWidth(text.getString());
        mc.fontRenderer.drawStringWithShadow(poseStack, text.getString(),
                screenWidth / 2.0F - textWidth / 2.0F, (float) (y - 9),
                currentTitleColor());
    }

    private void applyCycleColor() {
        int rgb = COLORS[(this.colorTicks / COLOR_INTERVAL) % COLORS.length];
        RenderSystem.color4f(
                ((rgb >> 16) & 0xFF) / 255.0F,
                ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F,
                1.0F);
    }

    private int currentTitleColor() {
        return this.titleColor;
    }

    private IFormattableTextComponent flashingTitle(ITextComponent title) {
        return title.deepCopy().mergeStyle(net.minecraft.util.text.Style.EMPTY.setColor(
                net.minecraft.util.text.Color.fromInt(currentTitleColor())));
    }
}
