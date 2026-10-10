package com.example.transcenmod.client.audio;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.util.ResourceLocation;

// looping track, MUSIC category, one instance tracked, start() is idempotent
// the ogg is not in the jar,sounds.json names it anyway
// pre-checking the file and logging one line beats SoundHandler spamming unknown-sound every tick
public final class TranscenMusic {

    // exists-check path, sounds/ prefix included - SoundHandler resolves namespace:sounds/<entry>
    // sounds.json "name" omits that prefix,so the full resource path is the only thing worth testing
    public static final ResourceLocation OGG =
            new ResourceLocation(TranscenMod.MODID + ":sounds/music/hell_river.ogg");

    // where to drop the file, for the log line and the README
    public static final String OGG_RELATIVE_PATH =
            "src/main/resources/assets/transcenmod/sounds/music/hell_river.ogg";

    private static ISound playing;
    private static boolean warnedAboutMissingFile;

    private TranscenMusic() {}

    // starts only when nothing is tracked and the ogg resolves, so call it every tick
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
        SimpleSound instance = SimpleSound.master(TranscenMod.TRANSCEN_MUSIC, 1.0F);
        playing = instance;
        soundManager().play(instance);
    }

    // stops only what this class started, a null handle means nothing to do
    public static void stop() {
        if (playing == null) {
            return;
        }
        soundManager().stop(playing);
        playing = null;
    }

    // called from the client tick
    // restarts when the sound has actually stopped not when the reference is merely null,those differ
    // another mod can stop it through the shared SoundHandler with no callback here,so the stale handle
    // keeps start() believing the track still runs - asking the manager is the only way to recover
    public static void tick(boolean shouldBePlaying) {
        if (!shouldBePlaying) {
            stop();
            return;
        }
        if (playing != null && soundManager().isPlaying(playing)) {
            return;
        }
        // stop first,always - dropping the reference alone is the "music stacks up" bug
        // isPlaying false does not prove the sound is gone, the handle is lost and a second instance
        // starts on top of the first, repeated ticks pile them higher
        // worst case here is one brief restart
        stop();
        start();
    }

    public static boolean isPlaying() {
        return playing != null;
    }

    private static SoundHandler soundManager() {
        return Minecraft.getInstance().getSoundHandler();
    }

    // through the client resource manager, so a resource pack can supply the ogg instead of the jar
    private static boolean oggPresent() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getResourceManager() == null) {
            return false;
        }
        try {
            return mc.getResourceManager().getResource(OGG) != null;
        } catch (java.io.IOException missing) {
            return false;
        }
    }
}
