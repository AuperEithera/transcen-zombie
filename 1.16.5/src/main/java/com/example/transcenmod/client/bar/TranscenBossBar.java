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

/**
 * The Transcendence bar.
 *
 * <p>Drawn from this mod's own {@code boss_bars.png}, which is a neutral-grey sheet rather than a
 * copy of vanilla's. Vanilla's sheet is <b>pre-coloured per style</b> -- its first bar sprite is
 * magenta {@code (173, 0, 135)}, the next is blue, then red -- so tinting it with
 * {@code setShaderColor} multiplies tint by colour and collapses to near-black, which is exactly
 * what an "empty bar" looks like. This sheet's bar sprite is pure white, so the tint lands as the
 * colour that was asked for. The sheet's layout matches vanilla's: ten rows per style, five for the
 * background and five for the bar, 182px wide at u=0.
 *
 * <p>The fill is always full: the zombie's health is a fixed constant and {@code setHealth} is a
 * no-op, so its identity comes from colour, not from damage.
 *
 * <p><b>On this version's drawing calls.</b> 1.19.2 predates {@code GuiGraphics}, which was introduced
 * in 1.20. The equivalent here is {@code AbstractGui.blit(MatrixStack, x, y, u, v, w, h, texW, texH,
 * texture)} plus {@code Font#drawShadow}, so this class takes a {@link MatrixStack} rather than a
 * graphics context and centres the title itself instead of calling a {@code drawCenteredString}
 * helper. The semantics are the same; only the plumbing differs.
 */
public class TranscenBossBar {

    private static final ResourceLocation BARS =
            new ResourceLocation("transcenmod:textures/gui/boss_bars.png");

    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;
    /** Style 0: background at v=0, fill at v=5. There is no notch row in the sheet. */
    private static final int V_BACKGROUND = 0;
    private static final int V_BAR = 5;
    /** Texture sheet size, needed by the blit overload that takes explicit dimensions. */
    private static final int SHEET_WIDTH = 256;
    private static final int SHEET_HEIGHT = 256;

    /** The 1.0.0 palette, cycled on a timer. */
    private static final int[] COLORS = {
            0xFF0000, // red
            0xFFFF00, // yellow
            0x0000FF, // blue
            0xFFFFFF, // white
            0x800080, // purple
            0x00FF00, // green
            0x00FFFF, // cyan
    };
    /** Ticks between colour changes. */
    private static final int COLOR_INTERVAL = 15;

    /** Ticks the title spends white between two colour pops. */
    private static final int TITLE_WHITE_TICKS = 4;
    /** Ticks the title spends on each popped colour. */
    private static final int TITLE_COLOR_TICKS = 2;

    /** Colours the title can pop to, in no particular order -- each pop picks one at random. */
    private static final int[] TITLE_POP_COLORS = {
            0xFF0000, // red
            0x0000FF, // blue
            0xFFFF00, // yellow
            0x00FFFF, // cyan
            0x800080, // purple
            0x00FF00, // green
    };

    private final java.util.Random random = new java.util.Random();

    /** Currently displayed title colour. */
    private int titleColor = 0xFFFFFF;
    /** Ticks left before the next colour change. */
    private int titleTicksLeft = TITLE_WHITE_TICKS;
    /** True while white is showing, so the next change pops a random colour. */
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

    /**
     * Pulse-hold-pop: white is held for {@link #TITLE_WHITE_TICKS}, then a random colour is popped
     * for {@link #TITLE_COLOR_TICKS}, then back to white. The colour is drawn fresh from
     * {@link #TITLE_POP_COLORS} each time, so the order is never the same twice.
     */
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

        // 1. background strip
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture( BARS);
        AbstractGui.blit(poseStack, x, y, 0, V_BACKGROUND, BAR_WIDTH, BAR_HEIGHT,
                SHEET_WIDTH, SHEET_HEIGHT);

        // 2. the fill, in the current cycle colour
        applyCycleColor();
        int filled = (int) (this.displayed * BAR_WIDTH);
        if (filled > 0) {
            // Two halves so the rounded caps survive a partial width, as vanilla does.
            AbstractGui.blit(poseStack, x, y, 0, V_BAR, filled, BAR_HEIGHT,
                    SHEET_WIDTH, SHEET_HEIGHT);
            AbstractGui.blit(poseStack, x + filled, y, BAR_WIDTH - filled, V_BAR,
                    BAR_WIDTH - filled, BAR_HEIGHT, SHEET_WIDTH, SHEET_HEIGHT);
        }

        // 3. title, flashing on its own cadence. No notch overlay: the sheet has no notch row, so
        // there are no segment marks to draw on top of the fill.
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

    /** White/red/white/blue/... flashing, driven by {@link #currentTitleColor()}. */
    private IFormattableTextComponent flashingTitle(ITextComponent title) {
        return title.deepCopy().mergeStyle(net.minecraft.util.text.Style.EMPTY.setColor(
                net.minecraft.util.text.Color.fromInt(currentTitleColor())));
    }
}
