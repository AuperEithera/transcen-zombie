package com.example.transcenmod.client.bar;

import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;

/**
 * The mod's own boss bar: one background strip, one filled strip, and a title that cycles colour.
 *
 * <p>Hand-drawn rather than built on {@code BossInfo} for a 1.12.2-specific reason. This generation's
 * {@code BossInfo} is a server-side object rendered by {@code GuiBossOverlay} from the server's own list,
 * which means the progress and colour would have to be pushed through the vanilla packet and the whole
 * thing would appear in the tab list; worse, the overlay draws through the same {@code BOSSHEALTH} element
 * this mod cancels, so a vanilla bar would be suppressing itself. A sprite blitted from this mod's own
 * sheet has none of those couplings.
 *
 * <p>The instance holds the animation state (fill, cycle counter, title colour) while the geometry is
 * static, so one instance is created per process in {@code ClientEvents} and reused forever.
 */
public class TranscenBossBar {

    /** {@code assets/transcenmod/textures/gui/boss_bars.png}: a 256x256 sheet with the two strips stacked. */
    private static final ResourceLocation BARS =
            new ResourceLocation("transcenmod", "textures/gui/boss_bars.png");

    /** Width of both strips, and the width the fill percentage is measured against. */
    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;

    /** Row inside the sheet holding the empty background strip. */
    private static final int V_BACKGROUND = 0;

    /** Row inside the sheet holding the solid fill strip. */
    private static final int V_BAR = 5;

    /**
     * The sheet's dimensions.
     *
     * <p>Not passed to either blit -- {@link Gui#drawTexturedModalRect(int, int, int, int, int, int)} takes
     * the source rectangle directly -- but kept as named constants because {@code V_BACKGROUND},
     * {@code V_BAR} and the title's v-coordinate are only meaningful relative to them, and anyone editing
     * the sheet needs the size these numbers were laid out for.
     */
    private static final int SHEET_WIDTH = 256;
    private static final int SHEET_HEIGHT = 256;

    /** Colours the fill cycles through, as 0xRRGGBB. */
    private static final int[] COLORS = {
            0xFF0000, 0xFFFF00, 0x0000FF, 0xFFFFFF, 0x800080, 0x00FF00, 0x00FFFF
    };

    /** Ticks each fill colour is held. With seven colours the fill takes a little over two seconds to loop. */
    private static final int COLOR_INTERVAL = 15;

    /** Ticks the title stays white before it pops to a colour. */
    private static final int TITLE_WHITE_TICKS = 4;

    /** Ticks the title stays coloured before it returns to white. */
    private static final int TITLE_COLOR_TICKS = 2;

    /** The colours the title pops to; not the same list as the bar, which has no white in it. */
    private static final int[] TITLE_POP_COLORS = {
            0xFF0000, 0x0000FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x00FF00
    };

    private final Random random = new Random();

    /** Current title colour as 0xRRGGBB. */
    private int titleColor = 0xFFFFFF;

    /** Ticks remaining before the title switches between white and a pop colour. */
    private int titleTicksLeft = TITLE_WHITE_TICKS;

    /** Whether the title is in its white half of the cycle. */
    private boolean titleShowingWhite = true;

    /** Eased fill, 0..1. This is what is drawn. */
    private float displayed = 1.0F;

    /** Fill the server last asked for, 0..1. */
    private float target = 1.0F;

    private boolean visible;

    /** Free-running counter behind both the fill colour and the fog tint; wraps by itself, harmlessly. */
    private int colorTicks;

    /**
     * One {@link Gui} instance shared by every bar.
     *
     * <p>{@code drawTexturedModalRect} is an instance method on {@code Gui} even though it reads no instance
     * state, so a bar needs a {@code Gui} to call it. Allocating one per frame would be a needless object
     * per frame per bar; one static instance is enough for the whole game because the method is
     * effectively pure.
     */
    private static final Gui BLIT = new Gui();

    public void setProgress(float progress) {
        this.target = MathHelper.clamp(progress, 0.0F, 1.0F);
    }

    /**
     * Shows or hides the bar.
     *
     * <p>Hiding also snaps the eased fill to the target and resets the colour counter. Both matter for the
     * next encounter: a bar hidden mid-drain would otherwise start its next appearance by animating down
     * from where it was, and the counter would make the first frames an arbitrary colour.
     */
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

    /**
     * Advances the animation by one client tick.
     *
     * <p>The ease is a fixed 10% of the remaining distance per tick, with a snap once the gap is under
     * 0.005. The snap is what stops the fill from creeping towards the target forever and flickering
     * between two pixel widths at the end.
     */
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

    /**
     * Flips the title between white and a pop colour.
     *
     * <p>A countdown rather than a modulo on {@code colorTicks}: the two halves of the cycle are different
     * lengths (white is held longer than the colour), which a single modulus cannot express. The
     * {@code --titleTicksLeft <= 0} form is deliberate, so the new colour starts on the tick the counter
     * runs out rather than one tick later.
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

        // White before the texture, or the blend left over from a previous colour would tint the sheet.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture(BARS);
        BLIT.drawTexturedModalRect(x, y, 0, V_BACKGROUND, BAR_WIDTH, BAR_HEIGHT);

        this.applyCycleColor();
        int filled = (int) (this.displayed * BAR_WIDTH);
        if (filled > 0) {
            // Drawn as two blits of the same strip rather than one, so the source rectangle can be given
            // the same width as the destination. Stretching a single one-pixel-wide source across the
            // whole strip would smear the gradient the sheet carries across its length.
            BLIT.drawTexturedModalRect(x, y, 0, V_BAR, filled, BAR_HEIGHT);
            BLIT.drawTexturedModalRect(x + filled, y, BAR_WIDTH - filled, V_BAR,
                    BAR_WIDTH - filled, BAR_HEIGHT);
        }

        // Back to white for the text: FontRenderer multiplies by the current colour, so a leftover cycle
        // colour would tint the title twice.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        float textWidth = mc.fontRenderer.getStringWidth(title);
        float startX = scaledWidth / 2.0F - textWidth / 2.0F;
        drawColouredTitle(mc, title, startX, y - 9.0F, this.currentTitleColor());
    }

    /** Pushes the current entry of the fill cycle into the GL colour state. */
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

    /**
     * Draws the title one character at a time.
     *
     * <p>This is not a workaround for the colour argument being ignored -- it is the only way to draw a line
     * whose colour changes per frame while every character still gets a shadow, which is why it is written
     * out rather than passed to a single {@code drawString}. There is no way to make one call do it:
     * {@code FontRenderer} scans the string for {@code §} formatting codes and, if it finds any, uses the
     * colour from the code and ignores the argument for those characters. So either the string carries a
     * code -- in which case the colour argument this method exists to apply is ignored -- or it does not,
     * in which case the whole line takes one colour. Colour and shadow cannot both be chosen by the caller
     * through a single call, so the caller does the loop.
     *
     * <p>The advance is {@code getCharWidth}, not a fixed width or {@code getStringWidth} of the character
     * as a string: proportional characters in the default font have different advances, and
     * {@code getCharWidth} is the value {@code drawString} itself uses, so the glyphs land exactly where a
     * single call would have put them.
     */
    private static void drawColouredTitle(Minecraft mc, String title, float x, float y, int color) {
        float cursor = x;
        for (int i = 0; i < title.length(); i++) {
            String character = String.valueOf(title.charAt(i));
            mc.fontRenderer.drawStringWithShadow(character, cursor, y, color);
            cursor += mc.fontRenderer.getCharWidth(title.charAt(i));
        }
    }
}
