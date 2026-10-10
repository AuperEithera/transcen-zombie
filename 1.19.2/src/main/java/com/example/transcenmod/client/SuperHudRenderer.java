package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Gui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

public final class SuperHudRenderer
{

    private SuperHudRenderer() {}

    private static MethodHandle superRender;
    private static boolean resolved;
    private static boolean unavailable;

    public static void render(Gui gui, PoseStack poseStack, float partialTick)
    {
        MethodHandle handle = handle();
        if (handle == null)
        {
            return;
        }
        try
        {
            handle.invoke(gui, poseStack, partialTick);
            drawOwnBar(poseStack);
        }
        catch (Throwable failure)
        {
            TranscenMod.LOGGER.warn("[transcenmod] vanilla HUD pass failed", failure);
        }
    }

    private static MethodHandle ownBar;
    private static boolean ownBarResolved;

    private static void drawOwnBar(PoseStack poseStack)
    {
        try
        {
            if (!ownBarResolved)
            {
                ownBarResolved = true;
                Class<?> events = Class.forName("com.example.transcenmod.client.ClientEvents");
                Method method = events.getMethod("renderOwnBarDirectly", PoseStack.class);
                ownBar = MethodHandles.publicLookup().unreflect(method);
            }
            if (ownBar != null)
            {
                ownBar.invoke(poseStack);
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
            Method declared = Gui.class.getDeclaredMethod("m_93030_", PoseStack.class, float.class);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Gui.class, MethodHandles.lookup());
            superRender = lookup.findSpecial(
                    Gui.class, "m_93030_",
                    MethodType.methodType(void.class, PoseStack.class, float.class),
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
