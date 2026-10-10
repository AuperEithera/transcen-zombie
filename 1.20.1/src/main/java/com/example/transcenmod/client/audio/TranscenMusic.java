package com.example.transcenmod.client.audio;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;

// music-category loop,repeats until the HUD switches off or the level is left
// start() is idempotent - a call while already playing does nothing
// ogg is not shipped with the mod, a missing file refuses to start and logs one line
// instead of letting SoundManager flood unknown-sound errors every tick
public final class TranscenMusic {

    // SoundManager resolves resources as <namespace>:sounds/<entry> taken from sounds.json
    // so the check needs the sounds/ prefix,the json name is only transcenmod:music/hell_river
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

    public static void tick(boolean shouldBePlaying) {
        if (shouldBePlaying) {
            start();
        } else {
            stop();
        }
    }

    public static boolean isPlaying() {
        return playing != null;
    }

    private static SoundManager soundManager() {
        return Minecraft.getInstance().getSoundManager();
    }

    // resolved through the client resource manager so a resource pack can supply the file,not the jar
    private static boolean oggPresent() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getResourceManager() != null
                && mc.getResourceManager().getResource(OGG).isPresent();
    }
}
