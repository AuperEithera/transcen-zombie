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

/**
 * The encounter's background music, started and stopped with the boss bar.
 *
 * <p>The track is looked up through the resource manager before it is played. {@code SoundHandler} itself
 * would only log a missing-file warning once at play time, and it would do so on the sound thread, which
 * makes the failure hard to connect to the mod; checking here turns "why is it silent" into one clear line
 * at the moment the music tries to start, and only once per session.
 *
 * <p>All state is static because there is only ever one background track for the whole process, and it is
 * started from static packet hooks and tick handlers, none of which own a suitable object.
 */
@SideOnly(Side.CLIENT)
public final class TranscenMusic {

    /** The sound event as the resource system sees it: everything under {@code assets/transcenmod/}. */
    public static final ResourceLocation OGG =
            new ResourceLocation("transcenmod:sounds/music/hell_river.ogg");

    /** Where the developer has to put the file in the source tree for the lookup above to succeed. */
    public static final String OGG_RELATIVE_PATH =
            "src/main/resources/assets/transcenmod/sounds/music/hell_river.ogg";

    /** The sound currently handed to the sound handler, or null when nothing is playing. */
    private static ISound playing;

    /** Guards the missing-file message so it appears once per session instead of once per attempt. */
    private static boolean warnedAboutMissingFile;

    private TranscenMusic() {}

    /**
     * Starts the track if it is not already playing.
     *
     * <p>The early return on a non-null {@code playing} is what makes this safe to call every tick: the
     * field is the mod's own record of the sound, not a query of the sound handler, so it also covers the
     * window in which the sound has been submitted but has not started.
     */
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

    /** Stops the track and forgets it. Safe when nothing is playing. */
    public static void stop() {
        if (playing == null) {
            return;
        }
        soundHandler().stopSound(playing);
        playing = null;
    }

    /**
     * Tick hook: keeps the music in step with the bar.
     *
     * <p>Two jobs, and the second is the reason this is a tick method rather than a pair of start/stop
     * calls. {@code isSoundPlaying} catches every way the sound can end without this class asking: the
     * handler dropping it, the resource pack reloading, or the player muting the music category and the
     * handler discarding it. Without the re-check the mod would report the music as running forever after
     * the first interruption and never restart it.
     */
    public static void tick(boolean shouldBePlaying) {
        if (!shouldBePlaying) {
            stop();
            return;
        }
        if (playing != null && soundHandler().isSoundPlaying(playing)) {
            return;
        }
        // stop() first: the field can be non-null while the handler no longer knows the sound, and starting
        // a second one without dropping the stale reference would leak the first.
        stop();
        start();
    }

    public static boolean isPlaying() {
        return playing != null;
    }

    /**
     * The sound handler is reached through {@code Minecraft} every time rather than cached in a field.
     * A cached handler survives {@code SoundHandler#reloadSoundSystem}, which replaces the internal sound
     * manager, and then silently belongs to a dead instance.
     */
    private static SoundHandler soundHandler() {
        return Minecraft.getMinecraft().getSoundHandler();
    }

    /**
     * Whether the music file is actually present in the loaded resource packs.
     *
     * <p>{@code IOException} means "not there" for this purpose, so it is swallowed and reported as false:
     * the only caller wants a yes/no, and the warning it prints names the file to drop in.
     */
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

    /**
     * The playing sound.
     *
     * <p>Extending {@link PositionedSound} rather than {@code PositionedSoundRecord} is required, not
     * preferred: {@code repeat}, {@code repeatDelay}, {@code volume}, {@code pitch} and
     * {@code attenuationType} are {@code protected} fields of {@code PositionedSound}, and
     * {@code PositionedSoundRecord} is an immutable concrete type that exposes no setter for them. Only a
     * subclass can configure a looping sound, and a subclass of {@code PositionedSound} is exactly what
     * that class is for.
     *
     * <p>The 1.16.5 build used {@code SimpleSound} here; that type does not exist in 1.12.2, where
     * {@code PositionedSound} plays the same role. {@code NONE} attenuation is what makes it background
     * music rather than a sound at a point in the world: it plays at full volume regardless of where the
     * listener is, and in particular it does not fade with distance from whatever spawned the encounter.
     */
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
