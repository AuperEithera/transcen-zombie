package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Gui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

// calls Gui.render directly,skipping ForgeGui's override
// that override is where third party HUD output is suppressed, yet the vanilla interface still has to draw
// calling the override again re-enters the suppression - infinite recursion - so reach the parent explicitly
// @Invoker cannot express this, it resolves against the target class which is the method being injected into
// findSpecial with an explicit lookup class gives the invokespecial handle the parent needs
// SRG id read from the mixin refmap,not guessed - resolved once so a failure warns once instead of every frame
public final class SuperHudRenderer {

    private SuperHudRenderer() {}

    private static MethodHandle superRender;
    private static boolean resolved;
    private static boolean unavailable;

    public static void render(Gui gui, PoseStack poseStack, float partialTick) {
        MethodHandle handle = handle();
        if (handle == null) {
            return;
        }
        try {
            handle.invoke(gui, poseStack, partialTick);
            // this mod's bar normally draws from an event raised inside the pass just skipped,so draw it here
            // reflective to keep common side code off a client only class, guarded so a failure cannot kill the frame
            drawOwnBar(poseStack);
        } catch (Throwable failure) {
            TranscenMod.LOGGER.warn("[transcenmod] vanilla HUD pass failed", failure);
        }
    }

    private static MethodHandle ownBar;
    private static boolean ownBarResolved;

    private static void drawOwnBar(PoseStack poseStack) {
        try {
            if (!ownBarResolved) {
                ownBarResolved = true;
                Class<?> events = Class.forName("com.example.transcenmod.client.ClientEvents");
                Method method = events.getMethod("renderOwnBarDirectly", PoseStack.class);
                ownBar = MethodHandles.publicLookup().unreflect(method);
            }
            if (ownBar != null) {
                ownBar.invoke(poseStack);
            }
        } catch (Throwable failure) {
            TranscenMod.LOGGER.warn("[transcenmod] could not draw this mod's own bar", failure);
        }
    }

    private static synchronized MethodHandle handle() {
        if (resolved) {
            return superRender;
        }
        resolved = true;
        try {
            Method declared = Gui.class.getDeclaredMethod("m_93030_", PoseStack.class, float.class);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Gui.class, MethodHandles.lookup());
            superRender = lookup.findSpecial(
                    Gui.class, "m_93030_",
                    MethodType.methodType(void.class, PoseStack.class, float.class),
                    Gui.class);
            TranscenMod.LOGGER.info("[transcenmod] vanilla HUD pass resolved ({})", declared);
        } catch (Throwable failure) {
            unavailable = true;
            TranscenMod.LOGGER.warn(
                    "[transcenmod] could not resolve the vanilla HUD pass; the encounter will suppress "
                            + "the HUD without redrawing it", failure);
        }
        return superRender;
    }

    public static boolean isUnavailable() {
        handle();
        return unavailable;
    }
}
