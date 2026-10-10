package com.example.transcenmod.client.audio;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;

/**
 * Looping background track for the Transcendence encounter.
 *
 * <p>Played as a {@code MUSIC}-category sound with looping enabled, so it repeats until the HUD
 * switches off or the level is left. Only one instance is tracked, and the controller is
 * idempotent: calling {@link #start()} while already playing does nothing.
 *
 * <p>The audio file is not shipped with the mod. {@code sounds.json} points at
 * {@code assets/transcenmod/sounds/music/hell_river.ogg}; while that file is absent the controller
 * refuses to start and logs one actionable line, instead of letting {@code SoundManager} flood the
 * console with unknown-sound errors every tick. See the README section "Background music".
 */
public final class TranscenMusic {

    /**
     * The audio file as a resolvable resource, <b>including</b> the {@code sounds/} prefix.
     *
     * <p>{@code SoundManager} resolves sound files as {@code <namespace>:sounds/<entry>}, where
     * {@code <entry>} comes from {@code sounds.json}. So even though the {@code "name"} in
     * {@code sounds.json} is {@code transcenmod:music/hell_river}, the underlying resource is
     * {@code transcenmod:sounds/music/hell_river.ogg} and that is the path that has to be used for
     * an existence check.
     */
    public static final ResourceLocation OGG =
            new ResourceLocation(TranscenMod.MODID + ":sounds/music/hell_river.ogg");

    /** Human-readable location for log messages and docs. */
    public static final String OGG_RELATIVE_PATH =
            "src/main/resources/assets/transcenmod/sounds/music/hell_river.ogg";

    private static SoundInstance playing;
    private static boolean warnedAboutMissingFile;

    private TranscenMusic() {}

    /** Starts the loop if it is not already running. Safe to call every tick. */
    public static void start() {
        if (playing != null) {
            return;
        }
        if (!oggPresent()) {
            if (!warnedAboutMissingFile) {
                warnedAboutMissingFile = true;
                TranscenMod.LOGGER.warn(
                        "[transcenmod] background music is configured but the .ogg is missing, so "
                                + "nothing will play. Drop the track at {} and rebuild.",
                        OGG_RELATIVE_PATH);
            }
            return;
        }
        SimpleSoundInstance instance = SimpleSoundInstance.forMusic(TranscenMod.TRANSCEN_MUSIC);
        playing = instance;
        soundManager().play(instance);
    }

    /** Stops the loop, if one is running. */
    public static void stop() {
        if (playing == null) {
            return;
        }
        soundManager().stop(playing);
        playing = null;
    }

    /**
     * Drives the loop from the client tick.
     *
     * <p>Restarts whenever the sound has actually stopped, not merely whenever the tracked reference is
     * null. Those are different conditions, and the difference is the whole of "other mods interrupt the
     * music": another mod can stop this sound through the shared {@code SoundManager} without this class
     * being told, and the stale reference it leaves behind keeps {@link #start()} believing the track is
     * already running. Asking the sound manager whether it is still playing is the only version that
     * recovers.
     */
    public static void tick(boolean shouldBePlaying) {
        if (!shouldBePlaying) {
            stop();
            return;
        }
        if (playing != null && soundManager().isActive(playing)) {
            return;
        }
        // Always stop the tracked instance before starting another.
        //
        // The previous version only dropped the reference. That is wrong whenever isActive reports false
        // for a sound that is in fact still running: the handle is discarded, a second instance starts,
        // and both play at once. Repeated ticks layered them further, which is exactly the "the music
        // stacks up" report. Stopping first makes the worst case a brief restart instead of an
        // ever-growing pile of the same track.
        stop();
        start();
    }

    public static boolean isPlaying() {
        return playing != null;
    }

    private static SoundManager soundManager() {
        return Minecraft.getInstance().getSoundManager();
    }

    /**
     * True when the OGG exists. Resolved through the client's resource manager, so a resource pack
     * can also supply the file instead of the mod jar.
     */
    private static boolean oggPresent() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getResourceManager() != null
                && mc.getResourceManager().getResource(OGG).isPresent();
    }
}
