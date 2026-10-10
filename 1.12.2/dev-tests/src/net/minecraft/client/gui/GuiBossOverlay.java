package net.minecraft.client.gui;

/**
 * Minimal stand-in for the real 1.12.2 GuiBossOverlay, used only by {@code verifyTransformer}.
 *
 * <p>{@code drawCount} is a static counter rather than an instance one so the harness can read it without
 * holding the instance it invoked the method on.
 */
public class GuiBossOverlay {

    public static int drawCount = 0;

    public void renderBossHealth() {
        drawCount++;
    }
}
