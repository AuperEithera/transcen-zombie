package com.example.transcenmod.util;

import com.example.transcenmod.TranscenMod;
import net.minecraftforge.common.DimensionManager;

import java.io.File;
import java.util.UUID;

/**
 * Per-world encounter state, persisted next to the world.
 *
 * <p>The arming flag is deliberately <em>not</em> what {@link TranscenMod#defenceEnabled} mirrors at
 * runtime. That runtime flag is session state: the swap egg sets it, a wipe clears it, and it decides
 * whether the repair runs at all. This class only remembers what the player last chose, so that a world
 * reopened after a wipe does not silently start the encounter again.
 *
 * <p>JSON rather than Forge's {@code Configuration}: the whole content is two scalars, and Forge's config
 * system in this generation is global rather than per-world, which is the exact problem this class exists
 * to solve.
 */
public class ConfigManager {

    /** Sentinel for "no player recorded yet". */
    private static final UUID NIL = new UUID(0L, 0L);

    /** What the player last chose: true means the encounter is armed. */
    private volatile boolean armed = false;

    /** The player the choice belongs to, when there is one. */
    private volatile UUID owner = NIL;

    /** Dimension the state was loaded for, so a dimension change does not re-read every tick. */
    private int loadedDimension = Integer.MIN_VALUE;

    private File location;

    public void init(File configDirectory) {
        // The per-world file is the authority. The path Forge hands here is rooted at
        // config/transcenmod, which is shared across every world a client opens, so it can carry the
        // player's last choice but never the world's state. Logged once so the location is discoverable.
        TranscenMod.LOGGER.info("[transcenmod] configuration directory: {}",
                configDirectory.getAbsolutePath());
    }

    public boolean isArmed() {
        return armed;
    }

    public void setArmed(boolean armed) {
        this.armed = armed;
        this.owner = NIL;
        save();
    }

    public UUID getOwner() {
        return owner;
    }

    public void setOwner(UUID owner) {
        this.owner = owner == null ? NIL : owner;
    }

    /**
     * Points the store at the currently loaded world and reads it back.
     *
     * <p>Called with the dimension a world tick came from. It is a no-op except on a change, which is what
     * keeps a per-tick call from becoming a per-tick disk read.
     */
    public void loadFor(int dimension) {
        if (dimension == loadedDimension) {
            return;
        }
        this.loadedDimension = dimension;
        File worldDirectory = DimensionManager.getCurrentSaveRootDirectory();
        if (worldDirectory == null) {
            return;
        }
        // One file per world rather than one per dimension: the encounter is a world-level idea, and a
        // player who wipes it in the overworld has wiped it.
        this.location = new File(worldDirectory, "transcenmod_state.json");
        read();
    }

    /** Persists the current choice, if a world is loaded. */
    public void save() {
        if (this.location == null) {
            return;
        }
        try {
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            root.addProperty("armed", this.armed);
            root.addProperty("owner", this.owner.toString());
            java.io.Writer writer = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(this.location), java.nio.charset.StandardCharsets.UTF_8);
            try {
                writer.write(root.toString());
            } finally {
                writer.close();
            }
        } catch (Throwable failure) {
            // A failed write must not take the tick down with it; the in-memory value is still correct for
            // this session.
            TranscenMod.LOGGER.warn("[transcenmod] could not persist encounter state", failure);
        }
    }

    private void read() {
        if (this.location == null || !this.location.isFile()) {
            // No file means a world that has never seen this mod, which is an unarmed encounter.
            this.armed = false;
            this.owner = NIL;
            return;
        }
        try {
            java.io.Reader reader = new java.io.InputStreamReader(
                    new java.io.FileInputStream(this.location), java.nio.charset.StandardCharsets.UTF_8);
            try {
                com.google.gson.JsonObject root =
                        new com.google.gson.JsonParser().parse(reader).getAsJsonObject();
                this.armed = root.has("armed") && root.get("armed").getAsBoolean();
                if (root.has("owner")) {
                    try {
                        this.owner = UUID.fromString(root.get("owner").getAsString());
                    } catch (IllegalArgumentException malformed) {
                        this.owner = NIL;
                    }
                } else {
                    this.owner = NIL;
                }
            } finally {
                reader.close();
            }
        } catch (Throwable failure) {
            TranscenMod.LOGGER.warn("[transcenmod] could not read encounter state", failure);
            this.armed = false;
            this.owner = NIL;
        }
    }

    /** Drops everything when the client leaves a world. */
    public void reset() {
        this.armed = false;
        this.owner = NIL;
        this.loadedDimension = Integer.MIN_VALUE;
        this.location = null;
    }
}
