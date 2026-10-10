package com.example.transcenmod.client.audio;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.util.ResourceLocation;

public final class TranscenMusic
{

    public static final ResourceLocation OGG =
            new ResourceLocation(TranscenMod.MODID + ":sounds/music/hell_river.ogg");

    public static final String OGG_RELATIVE_PATH =
            "src/main/resources/assets/transcenmod/sounds/music/hell_river.ogg";

    private static ISound playing;
    private static boolean warnedAboutMissingFile;

    private TranscenMusic() {}

    public static void start()
    {
        if (playing != null)
        {
            return;
        }
        if (!oggPresent())
        {
            if (!warnedAboutMissingFile)
            {
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

    public static void stop()
    {
        if (playing == null)
        {
            return;
        }
        soundManager().stop(playing);
        playing = null;
    }

    public static void tick(boolean shouldBePlaying)
    {
        if (!shouldBePlaying)
        {
            stop();
            return;
        }
        if (playing != null && soundManager().isPlaying(playing))
        {
            return;
        }
        // stop first,always - dropping the reference alone is the "music stacks up" bug
        // isPlaying false does not prove the sound is gone, the handle is lost and a second instance
        // starts on top of the first, repeated ticks pile them higher
        stop();
        start();
    }

    public static boolean isPlaying()
    {
        return playing != null;
    }

    private static SoundHandler soundManager()
    {
        return Minecraft.getInstance().getSoundHandler();
    }

    private static boolean oggPresent()
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getResourceManager() == null)
        {
            return false;
        }
        try
        {
            return mc.getResourceManager().getResource(OGG) != null;
        }
        catch (java.io.IOException missing)
        {
            return false;
        }
    }
}
