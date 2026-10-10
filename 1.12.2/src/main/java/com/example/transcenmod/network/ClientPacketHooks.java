package com.example.transcenmod.network;

/**
 * The one place where a packet reaches client-only code.
 *
 * <p>Every message class in this package is loaded on a dedicated server as well, so none of them may name
 * a client type in a method body that can run there. All the client-bound instructions therefore funnel
 * through these methods, and each body loads only when a client-bound packet is actually handled -- which
 * by construction never happens on a server, because those messages are registered {@code Side.CLIENT}.
 *
 * <p>This is the 1.12.2 equivalent of the {@code static Runnable clientHook} fields the 1.16.5 build
 * carried on each packet class. Two differences: the hook is a plain static call rather than a field
 * because there is exactly one implementation, and the client body is reached directly rather than through
 * Forge's {@code enqueueWork}, which this generation does not have on the message handler.
 */
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
