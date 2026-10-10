package com.example.transcenmod.client;

public final class AnnihilationClientState {

    private static volatile boolean protectionOff;

    private static volatile boolean hudActive;

    private static volatile float hudProgress = 1.0F;

    private static volatile boolean hudLatched;

    private AnnihilationClientState() {}

    public static void setProtectionOff(boolean value) {
        protectionOff = value;
    }

    public static boolean isProtectionOff() {
        return protectionOff;
    }

    public static void setHud(boolean active, float progress) {
        if (active) {
            hudLatched = true;
        }
        hudActive = hudLatched || active;
        hudProgress = progress;
    }

    public static void latchHud() {
        hudLatched = true;
        hudActive = true;
    }

    public static boolean isHudLatched() {
        return hudLatched;
    }

    public static void unlatchHud() {
        hudLatched = false;
        hudActive = false;
    }

    public static boolean isHudActive() {
        return hudActive;
    }

    public static float hudProgress() {
        return hudProgress;
    }

    public static void reset() {
        hudActive = false;
        hudLatched = false;
        hudProgress = 1.0F;
        protectionOff = false;
    }
}
