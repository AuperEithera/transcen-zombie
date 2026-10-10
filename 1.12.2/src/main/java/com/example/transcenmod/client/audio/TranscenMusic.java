package com.example.transcenmod.client.audio;

import java.io.IOException;

import com.example.transcenmod.TranscenMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class TranscenMusic {

    public static final ResourceLocation OGG =
            new ResourceLocation("transcenmod:sounds/music/hell_river.ogg");

    public static final String OGG_RELATIVE_PATH =
            "src/main/resources/assets/transcenmod/sounds/music/hell_river.ogg";

    private static ISound playing;

    private static boolean warnedAboutMissingFile;

    private TranscenMusic() {}

    public static void start() {
        if (playing != null) {
            return;
        }
        if (!oggPresent()) {
            if (!warnedAboutMissingFile) {
                warnedAboutMissingFile = true;
                TranscenMod.LOGGER.warn("background music is configured but the .ogg is missing, so nothing"
                        + " will play. Drop the track at {} and rebuild.", OGG_RELATIVE_PATH);
            }
            return;
        }
        playing = new MusicSound();
        soundHandler().playSound(playing);
    }

    public static void stop() {
        if (playing == null) {
            return;
        }
        soundHandler().stopSound(playing);
        playing = null;
    }

    public static void tick(boolean shouldBePlaying) {
        if (!shouldBePlaying) {
            stop();
            return;
        }
        if (playing != null && soundHandler().isSoundPlaying(playing)) {
            return;
        }
        // stop() first: the field can be non-null while the handler no longer knows the sound, and starting
        // a second one without dropping the stale handle would leak the first.
        stop();
        start();
    }

    public static boolean isPlaying() {
        return playing != null;
    }

    private static SoundHandler soundHandler() {
        return Minecraft.getMinecraft().getSoundHandler();
    }

    private static boolean oggPresent() {
        Minecraft mc = Minecraft.getMinecraft();
        IResourceManager resources = mc.getResourceManager();
        if (resources == null) {
            return false;
        }
        try {
            return resources.getResource(OGG) != null;
        } catch (IOException e) {
            return false;
        }
    }

    @SideOnly(Side.CLIENT)
    private static final class MusicSound extends PositionedSound {

        private MusicSound() {
            super(TranscenMod.TRANSCEN_MUSIC, SoundCategory.MUSIC);
            this.volume = 1.0F;
            this.pitch = 1.0F;
            this.repeat = true;
            // Zero delay between repeats, so the track loops seamlessly instead of pausing between passes.
            this.repeatDelay = 0;
            this.attenuationType = ISound.AttenuationType.NONE;
        }
    }
}
