package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.client.audio.TranscenMusic;
import com.example.transcenmod.client.bar.TranscenBossBar;
import com.example.transcenmod.entity.TranscenZombie;
import com.example.transcenmod.network.ActivateHudPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.opengl.GL11;

public class ClientEvents
{

    private static final TranscenBossBar BAR = new TranscenBossBar();
    private static final Component TITLE =
            Component.literal("TRANSCENDENCE ZOMBIE")
                    .withStyle(style -> style.withBold(true).withItalic(true));

    private static int tickCount = 0;

    static
    {
        ActivateHudPacket.setClientHook(ClientEvents::activateHud);
        com.example.transcenmod.network.WipeEndedPacket.setClientHook(ClientEvents::deactivateHud);
        com.example.transcenmod.network.WipeRequestPacket.setClientHook(ClientEvents::deactivateHud);
    }

    public static void activateHud()
    {
        AnnihilationClientState.setProtectionOff(false);
        TranscenMod.HUD_ACTIVE = true;
        AnnihilationClientState.latchHud();
    }

    @SubscribeEvent
    public static void onScreenOpening(net.minecraftforge.client.event.ScreenEvent.Opening event)
    {
        if (!(event.getNewScreen() instanceof net.minecraft.client.gui.screens.DeathScreen))
        {
            return;
        }
        if (TranscenMod.SHOW_DEATH_SCREEN)
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.entity.player.Player player = mc.player;
        if (player == null || mc.level == null)
        {
            return;
        }
        if (!com.example.transcenmod.event.TranscenEvents.isCarryingEgg(player))
        {
            return;
        }
        player.setHealth(com.example.transcenmod.event.TranscenEvents.eggHolderHealth());
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || !TranscenMod.SHOW_DEATH_SCREEN)
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
        {
            return;
        }
        net.minecraft.client.gui.screens.DeathScreen screen =
                new net.minecraft.client.gui.screens.DeathScreen(
                        Component.translatable("deathScreen.title"), false);
        screen.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        mc.screen = screen;
        TranscenMod.SHOW_DEATH_SCREEN = false;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {

        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        tickCount++;

        Minecraft mc = Minecraft.getInstance();
        boolean remoteHud = AnnihilationClientState.isHudActive();
        boolean zombieVisible = mc.level != null && zombiePresent(mc.level);
        if (zombieVisible)
        {
            AnnihilationClientState.latchHud();
        }
        boolean active = (TranscenMod.HUD_ACTIVE
                || remoteHud
                || AnnihilationClientState.isHudLatched()
                || zombieVisible) && mc.level != null;

        if (AnnihilationClientState.isProtectionOff())
        {
            active = false;
        }

        keepDoomedShellsRed(mc);

        BAR.setProgress(remoteHud ? clamp(AnnihilationClientState.hudProgress()) : 1.0F);
        BAR.setVisible(active);
        BAR.tick();
        TranscenMusic.tick(active);
    }

    private static void keepDoomedShellsRed(Minecraft mc)
    {
        AnnihilationClientState.noteTick();
    }

    private static float clamp(float v)
    {
        if (v < 0.0F)
        {
            return 0.0F;
        }
        return v > 1.0F ? 1.0F : v;
    }

    @SubscribeEvent
    public static void onClientPlayerTick(TickEvent.PlayerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        net.minecraft.world.entity.player.Player player = event.player;
        if (!com.example.transcenmod.event.TranscenEvents.isCarryingEgg(player))
        {
            return;
        }
        float full = com.example.transcenmod.event.TranscenEvents.eggHolderHealth();
        if (player.getHealth() < full)
        {
            player.setHealth(full);
        }
    }

    public static void renderOwnBarDirectly(GuiGraphics gui)
    {
        if (!BAR.isVisible())
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null)
        {
            return;
        }
        BAR.render(gui, TITLE, mc.getWindow().getGuiScaledWidth(), 12);
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event)
    {
        if (!BAR.isVisible())
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics gui = event.getGuiGraphics();
        int width = mc.getWindow().getGuiScaledWidth();
        BAR.render(gui, TITLE, width, 12);
    }

    @SubscribeEvent
    public static void onRenderSky(RenderLevelStageEvent event)
    {
        if (!BAR.isVisible() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY)
        {
            return;
        }
        int colorIndex = (tickCount / 40) % 5;
        float colors[][] = {
                {1f, 0f, 0f}, {1f, 1f, 0f}, {0.5f, 0f, 0.5f}, {0f, 1f, 0f}, {0f, 1f, 1f}
        };
        float c[] = colors[colorIndex];
        RenderSystem.clearColor(c[0], c[1], c[2], 1.0f);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        AnnihilationClientState.reset();
        TranscenMod.HUD_ACTIVE = false;
        BAR.setVisible(false);
        TranscenMusic.stop();
    }

    static boolean zombiePresent(ClientLevel level)
    {
        for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering())
        {
            if (entity instanceof TranscenZombie)
            {
                return true;
            }
        }
        return false;
    }

    public static void deactivateHud()
    {
        TranscenMod.HUD_ACTIVE = false;
        AnnihilationClientState.unlatchHud();
        AnnihilationClientState.setHud(false, 0.0F);
        AnnihilationClientState.clearAllDoomed();

        Minecraft client = Minecraft.getInstance();
        if (client.level != null)
        {
            java.util.List<Entity> copies = new java.util.ArrayList<>();
            for (Entity entity : client.level.entitiesForRendering())
            {
                if (entity instanceof TranscenZombie)
                {
                    copies.add(entity);
                }
            }
            for (Entity entity : copies)
            {
                try
                {
                    com.example.transcenmod.util.CustomEntityRemover.removeEntity(entity);
                }
                catch (Throwable failure)
                {
                    TranscenMod.LOGGER.warn("[transcenmod] client-side removal failed", failure);
                }
            }
        }

        AnnihilationClientState.setProtectionOff(true);
        BAR.setVisible(false);
        TranscenMusic.stop();
        // deliberately does NOT advance the zombie generation. that counter is shared with the server in a
        // single-player session and retiring zombies is the server's decision; bumping it here made the
        // client bump it as well, and several packet handlers call this so one wipe advanced it several
        // times - every advance retired the zombies again, which is the flicker: removed,restored,removed
    }

    private static void requestWipe()
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null)
        {
            return;
        }
        if (!com.example.transcenmod.event.TranscenEvents.isHoldingEgg(mc.player))
        {
            return;
        }
        TranscenMod.NETWORK.sendToServer(new com.example.transcenmod.network.WipeRequestPacket());
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(
            net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickEmpty event)
            {
        requestWipe();
    }

    @SubscribeEvent
    public static void onLeftClickBlock(
            net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event)
            {
        requestWipe();
    }

    @SubscribeEvent
    public static void onAttackEntity(
            net.minecraftforge.event.entity.player.AttackEntityEvent event)
            {
        requestWipe();
    }
}
