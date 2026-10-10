package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * Invokes the vanilla {@code Gui.render} pass directly, bypassing {@code ForgeGui}'s override.
 *
 * <p>Needed because the override is where third-party HUD output is suppressed, and the vanilla interface
 * still has to be drawn. Calling the override again would re-enter the suppression -- infinite recursion --
 * so the parent implementation is reached explicitly.
 *
 * <p>This cannot be expressed with a mixin invoker: {@code @Invoker} resolves against the target class,
 * which is the very method being injected into. Reaching the parent needs the declared owner to be
 * {@code Gui}, which is what {@link MethodHandles.Lookup#findSpecial} with an explicit lookup class and an
 * {@code invokespecial} handle provides.
 *
 * <p>The method is named by its SRG id, read off this version's bytecode rather than carried over from the
 * other one: 1.19.2 has it as {@code m_93030_} and 1.20.1 as {@code m_280421_}, and the two also disagree
 * on the parameter type ({@code PoseStack} versus {@code GuiGraphics}). It is resolved once and cached; a
 * resolution failure is reported and remembered, so a mismatch shows up as one warning rather than a stack
 * trace per frame.
 */
public final class SuperHudRenderer {

    private SuperHudRenderer() {}

    /** The vanilla pass: {@code Gui.m_280421_(GuiGraphics, float)} on 1.20.1. */
    private static final String VANILLA_RENDER = "m_280421_";

    private static MethodHandle superRender;
    private static boolean resolved;
    private static boolean unavailable;

    public static void render(Gui gui, GuiGraphics graphics, float partialTick) {
        MethodHandle handle = handle();
        if (handle == null) {
            return;
        }
        try {
            handle.invoke(gui, graphics, partialTick);
            drawOwnBar(graphics);
        } catch (Throwable failure) {
            TranscenMod.LOGGER.warn("[transcenmod] vanilla HUD pass failed", failure);
        }
    }

    private static MethodHandle ownBar;
    private static boolean ownBarResolved;

    /**
     * Draws this mod's bar directly.
     *
     * <p>The event the bar normally draws from is raised inside the pass just skipped, so it has to be
     * drawn here instead. Reached reflectively to keep common-side code from naming a client-only class,
     * and guarded because a failure here must not take the frame down.
     */
    private static void drawOwnBar(GuiGraphics graphics) {
        try {
            if (!ownBarResolved) {
                ownBarResolved = true;
                Class<?> events = Class.forName("com.example.transcenmod.client.ClientEvents");
                Method method = events.getMethod("renderOwnBarDirectly", GuiGraphics.class);
                ownBar = MethodHandles.publicLookup().unreflect(method);
            }
            if (ownBar != null) {
                ownBar.invoke(graphics);
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
            Method declared = Gui.class.getDeclaredMethod(VANILLA_RENDER, GuiGraphics.class, float.class);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Gui.class, MethodHandles.lookup());
            superRender = lookup.findSpecial(
                    Gui.class, VANILLA_RENDER,
                    MethodType.methodType(void.class, GuiGraphics.class, float.class),
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
