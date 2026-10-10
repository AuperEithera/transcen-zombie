package com.example.transcenmod.client.bar;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

// own boss_bars.png,not vanilla's - vanilla's sheet is pre-coloured per style so setShaderColor
// multiplies tint into near-black,this one is white at u=0 182px wide,ten rows per style
// fill is always full - zombie health is a fixed constant and setHealth is a no-op
public class TranscenBossBar {

    private static final ResourceLocation BARS =
            new ResourceLocation("transcenmod:textures/gui/boss_bars.png");

    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;
    // style 0: background v=0 bar v=5,this sheet has no notch row
    private static final int V_BACKGROUND = 0;
    private static final int V_BAR = 5;

    private static final int[] COLORS = {
            0xFF0000,
            0xFFFF00,
            0x0000FF,
            0xFFFFFF,
            0x800080,
            0x00FF00,
            0x00FFFF,
    };
    // tick counter not frame time 15 lands just under a second
    private static final int COLOR_INTERVAL = 15;

    private static final int TITLE_WHITE_TICKS = 4;
    private static final int TITLE_COLOR_TICKS = 2;

    // each pop draws a fresh random entry,order is never the same twice
    private static final int[] TITLE_POP_COLORS = {
            0xFF0000,
            0x0000FF,
            0xFFFF00,
            0x00FFFF,
            0x800080,
            0x00FF00,
    };

    private final RandomSource random = RandomSource.create();

    private int titleColor = 0xFFFFFF;
    private int titleTicksLeft = TITLE_WHITE_TICKS;
    private boolean titleShowingWhite = true;

    private float displayed = 1.0F;
    private float target = 1.0F;
    private boolean visible;
    private int colorTicks;

    public void setProgress(float progress) {
        this.target = Mth.clamp(progress, 0.0F, 1.0F);
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

    // white -> random pop -> white,colour is redrawn each cycle so the order never repeats
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

    public void render(GuiGraphics gui, Component title, int screenWidth, int y) {
        if (!this.visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int x = screenWidth / 2 - BAR_WIDTH / 2;

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        blit(gui, x, y, V_BACKGROUND);

        applyCycleColor();
        int filled = (int) (this.displayed * BAR_WIDTH);
        if (filled > 0) {
            // two blits so the rounded caps survive a partial width,as vanilla does
            gui.blit(BARS, x, y, 0, V_BAR, filled, BAR_HEIGHT);
            gui.blit(BARS, x + filled, y, BAR_WIDTH - filled, V_BAR,
                    BAR_WIDTH - filled, BAR_HEIGHT);
        }

        // no notch overlay - this sheet has no notch row to mark the fill with
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        gui.drawCenteredString(mc.font, flashingTitle(title), screenWidth / 2, y - 9,
                currentTitleColor());
    }

    private void blit(GuiGraphics gui, int x, int y, int v) {
        gui.blit(BARS, x, y, 0, v, BAR_WIDTH, BAR_HEIGHT);
    }

    private void applyCycleColor() {
        int rgb = COLORS[(this.colorTicks / COLOR_INTERVAL) % COLORS.length];
        RenderSystem.setShaderColor(
                ((rgb >> 16) & 0xFF) / 255.0F,
                ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F,
                1.0F);
    }

    private int currentTitleColor() {
        return this.titleColor;
    }

    private MutableComponent flashingTitle(Component title) {
        return title.copy().withStyle(Style.EMPTY.withColor(currentTitleColor()));
    }
}
