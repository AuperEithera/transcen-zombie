package com.example.transcenmod.client.audio;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;

// looping MUSIC-category track for the Transcendence encounter
// one tracked instance only, start() while playing does nothing
// ogg is not shipped with the mod,so a missing file is refused up front
// otherwise SoundManager logs an unknown-sound error every tick
public final class TranscenMusic {

    // sounds.json declares transcenmod:music/hell_river but SoundManager loads it as
    // namespace:sounds/entry - the existence check needs the sounds/ path,not the declared one
    public static final ResourceLocation OGG =
            new ResourceLocation(TranscenMod.MODID + ":sounds/music/hell_river.ogg");

    public static final String OGG_RELATIVE_PATH =
            "src/main/resources/assets/transcenmod/sounds/music/hell_river.ogg";

    private static SoundInstance playing;
    private static boolean warnedAboutMissingFile;

    private TranscenMusic() {}

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

    public static void stop() {
        if (playing == null) {
            return;
        }
        soundManager().stop(playing);
        playing = null;
    }

    // isActive, not merely a null check - another mod can stop this sound through the
    // shared SoundManager and never tell us,leaving a stale reference that keeps
    // start() believing the track is already running
    public static void tick(boolean shouldBePlaying) {
        if (!shouldBePlaying) {
            stop();
            return;
        }
        if (playing != null && soundManager().isActive(playing)) {
            return;
        }
        // stop the tracked instance first,then start - dropping only the reference stacks tracks
        // isActive can report false for a sound that is still running,so the old handle is lost
        // and a second instance plays over it - worst case here is a brief restart
        stop();
        start();
    }

    public static boolean isPlaying() {
        return playing != null;
    }

    private static SoundManager soundManager() {
        return Minecraft.getInstance().getSoundManager();
    }

    // through the client resource manager,so a resource pack can supply the file instead of the jar
    private static boolean oggPresent() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getResourceManager() != null
                && mc.getResourceManager().getResource(OGG).isPresent();
    }
}
