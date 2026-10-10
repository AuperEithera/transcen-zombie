package com.example.transcenmod.util;

public final class RetaliationState
{

    private RetaliationState() {}

    private static volatile boolean retaliation;

    public static void mark()
    {
        retaliation = true;
    }

    public static void clear()
    {
        retaliation = false;
    }

    public static boolean isActive()
    {
        return retaliation;
    }
}
