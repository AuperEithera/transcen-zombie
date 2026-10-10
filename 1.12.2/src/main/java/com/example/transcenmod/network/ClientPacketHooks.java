package com.example.transcenmod.network;

public final class ClientPacketHooks {

    private ClientPacketHooks() {}

    public static void showDeathScreen() {
        com.example.transcenmod.TranscenMod.SHOW_DEATH_SCREEN = true;
    }

    public static void activateHud() {
        com.example.transcenmod.client.ClientEvents.activateHud();
    }

    public static void deactivateHud() {
        com.example.transcenmod.client.ClientEvents.deactivateHud();
    }

    public static void setHud(boolean active, float progress) {
        com.example.transcenmod.client.AnnihilationClientState.setHud(active, progress);
    }
}
