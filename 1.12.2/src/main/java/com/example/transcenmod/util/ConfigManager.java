package com.example.transcenmod.util;

import com.example.transcenmod.TranscenMod;
import net.minecraftforge.common.DimensionManager;

import java.io.File;
import java.util.UUID;

public class ConfigManager {

    private static final UUID NIL = new UUID(0L, 0L);

    private volatile boolean armed = false;

    private volatile UUID owner = NIL;

    private int loadedDimension = Integer.MIN_VALUE;

    private File location;

    public void init(File configDirectory) {
        // The per-world file is the authority. The path Forge hands here is rooted at
        // config/transcenmod, which is shared across every world a client opens, so it can carry the
        // player's last choice but never the world's state. Logged once so the location is discoverable.
        TranscenMod.LOGGER.info("[transcenmod] configuration directory: {}",
                configDirectory.getAbsolutePath());
        // the AllReturn switch mirrors itself into this directory, so that it can always be turned off
        // from outside the game even when every click in the game is being eaten by a guarded delegate
        com.example.transcenmod.core.AllReturn.watch(configDirectory);
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

    public void reset() {
        this.armed = false;
        this.owner = NIL;
        this.loadedDimension = Integer.MIN_VALUE;
        this.location = null;
    }
}
