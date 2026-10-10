package com.example.transcenmod.client;

import java.util.UUID;

/**
 * Bridge that lets the client-side entity tick ask whether an entity is a doomed shell.
 *
 * <p>Kept in the client package on purpose: {@code TranscenMod} and the entity classes are loaded on
 * dedicated servers too and must not name a client-only type. The tick hook that calls this lives in
 * {@code TranscenEvents} and is guarded by {@code level.isRemote}, which is a common-side,
 * always-loadable check, so on a server this class is never touched.
 */
public final class DoomedShell {

    private DoomedShell() {}

    /** True while {@code id} is a shell the server has already discarded. */
    public static boolean isDoomed(UUID id) {
        return AnnihilationClientState.isDoomed(id);
    }
}
