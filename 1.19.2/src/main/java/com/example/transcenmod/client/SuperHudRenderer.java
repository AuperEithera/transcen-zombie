package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Gui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * Invokes the vanilla {@code Gui.render} pass directly, bypassing {@code ForgeGui}'s override.
 *
 * <p>Needed because the override is where third-party HUD output is suppressed, and the vanilla interface
 * still has to be drawn. Calling the override again would re-enter the suppression -- infinite recursion
 * -- so the parent implementation is reached explicitly.
 *
 * <p>This cannot be expressed with a mixin invoker: {@code @Invoker} resolves against the target class,
 * which is the very method being injected into. Reaching the parent needs the declared owner to be
 * {@code Gui}, which is what {@link MethodHandles.Lookup#findSpecial} with an explicit lookup class and
 * an {@code invokespecial} handle provides.
 *
 * <p>The method is named by its SRG id, taken from the mixin refmap rather than guessed. It is resolved
 * once and cached; a resolution failure is reported and remembered, so a mismatch shows up as one
 * warning instead of a stack trace per frame.
 */
public final class SuperHudRenderer {

    private SuperHudRenderer() {}

    /** The vanilla pass: {@code Gui.m_93030_(PoseStack, float)}. */
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
            // The event this mod's bar is normally drawn from is raised inside the pass just skipped, so
            // it has to be drawn here instead. Reached reflectively to keep common-side code from naming a
            // client-only class, and guarded because a failure here must not take the frame down.
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

    /** True when the vanilla pass could not be reached, so callers can choose to leave the HUD alone. */
    public static boolean isUnavailable() {
        handle();
        return unavailable;
    }
}
