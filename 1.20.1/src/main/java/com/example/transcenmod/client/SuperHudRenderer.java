package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

public final class SuperHudRenderer
{

    private SuperHudRenderer() {}

    private static final String VANILLA_RENDER = "m_280421_";

    private static MethodHandle superRender;
    private static boolean resolved;
    private static boolean unavailable;

    public static void render(Gui gui, GuiGraphics graphics, float partialTick)
    {
        MethodHandle handle = handle();
        if (handle == null)
        {
            return;
        }
        try
        {
            handle.invoke(gui, graphics, partialTick);
            drawOwnBar(graphics);
        }
        catch (Throwable failure)
        {
            TranscenMod.LOGGER.warn("[transcenmod] vanilla HUD pass failed", failure);
        }
    }

    private static MethodHandle ownBar;
    private static boolean ownBarResolved;

    private static void drawOwnBar(GuiGraphics graphics)
    {
        try
        {
            if (!ownBarResolved)
            {
                ownBarResolved = true;
                Class<?> events = Class.forName("com.example.transcenmod.client.ClientEvents");
                Method method = events.getMethod("renderOwnBarDirectly", GuiGraphics.class);
                ownBar = MethodHandles.publicLookup().unreflect(method);
            }
            if (ownBar != null)
            {
                ownBar.invoke(graphics);
            }
        }
        catch (Throwable failure)
        {
            TranscenMod.LOGGER.warn("[transcenmod] could not draw this mod's own bar", failure);
        }
    }

    private static synchronized MethodHandle handle()
    {
        if (resolved)
        {
            return superRender;
        }
        resolved = true;
        try
        {
            Method declared = Gui.class.getDeclaredMethod(VANILLA_RENDER, GuiGraphics.class, float.class);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Gui.class, MethodHandles.lookup());
            superRender = lookup.findSpecial(
                    Gui.class, VANILLA_RENDER,
                    MethodType.methodType(void.class, GuiGraphics.class, float.class),
                    Gui.class);
            TranscenMod.LOGGER.info("[transcenmod] vanilla HUD pass resolved ({})", declared);
        }
        catch (Throwable failure)
        {
            unavailable = true;
            TranscenMod.LOGGER.warn(
                    "[transcenmod] could not resolve the vanilla HUD pass; the encounter will suppress "
                            + "the HUD without redrawing it", failure);
        }
        return superRender;
    }

    public static boolean isUnavailable()
    {
        handle();
        return unavailable;
    }
}
