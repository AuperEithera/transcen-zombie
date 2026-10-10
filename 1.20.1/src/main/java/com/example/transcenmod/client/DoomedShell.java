package com.example.transcenmod.client;

import java.util.UUID;

public final class DoomedShell
{

    private DoomedShell() {}

    public static boolean isDoomed(UUID id)
    {
        return AnnihilationClientState.isDoomed(id);
    }
}
