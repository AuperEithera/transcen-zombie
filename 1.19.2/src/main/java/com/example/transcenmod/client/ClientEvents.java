package com.example.transcenmod.client;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.client.audio.TranscenMusic;
import com.example.transcenmod.client.bar.TranscenBossBar;
import com.example.transcenmod.entity.TranscenZombie;
import com.example.transcenmod.event.TranscenEvents;
import com.example.transcenmod.network.ActivateHudPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
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
        // latch as well as switch on: the server's later "no zombie" updates must not turn the
        // encounter off, or the bar strobes every time a removal is followed by the repair
        AnnihilationClientState.latchHud();
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
                if (entity instanceof com.example.transcenmod.entity.TranscenZombie)
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
        // deliberately does NOT advance the zombie generation: that counter is static and shared
        // with the server, and this method runs from three packet handlers, so one wipe bumped it
        // several times - removed, restored, removed - the flicker. the server retires zombies
    }

    private static void requestWipe()
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null)
        {
            return;
        }
        if (!TranscenEvents.isHoldingEgg(mc.player))
        {
            return;
        }
        TranscenMod.NETWORK.sendToServer(new com.example.transcenmod.network.WipeRequestPacket());
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickEmpty event)
    {
        requestWipe();
    }

    @SubscribeEvent
    public static void onLeftClickBlock(net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event)
    {
        requestWipe();
    }

    @SubscribeEvent
    public static void onAttackEntity(net.minecraftforge.event.entity.player.AttackEntityEvent event)
    {
        requestWipe();
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event)
    {
        if (!(event.getNewScreen() instanceof DeathScreen))
        {
            return;
        }
        if (TranscenMod.SHOW_DEATH_SCREEN)
        {
            TranscenMod.LOGGER.info("[transcenmod] death screen let through (forced by the mod)");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null)
        {
            return;
        }
        if (!TranscenEvents.isCarryingEgg(player))
        {
            TranscenMod.LOGGER.info(
                    "[transcenmod] death screen not suppressed: egg not held "
                            + "(health {}, main={}, off={}, expected={})",
                    player.getHealth(),
                    player.getMainHandItem(),
                    player.getOffhandItem(),
                    TranscenMod.TRANSCEN_SPAWN_EGG.get());
            return;
        }
        player.setHealth(TranscenEvents.eggHolderHealth());
        TranscenMod.LOGGER.info("[transcenmod] death screen suppressed for egg holder");
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientPlayerTick(TickEvent.PlayerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        Player player = event.player;
        if (!TranscenEvents.isCarryingEgg(player))
        {
            return;
        }
        if (player.getHealth() < TranscenEvents.eggHolderHealth())
        {
            TranscenMod.LOGGER.info(
                    "[transcenmod] client pinned health {} -> {}",
                    player.getHealth(), TranscenEvents.eggHolderHealth());
            player.setHealth(TranscenEvents.eggHolderHealth());
        }
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
        DeathScreen screen = new DeathScreen(Component.translatable("deathScreen.title"), false);
        screen.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        mc.screen = screen;
        TranscenMod.LOGGER.info(
                "[transcenmod] forced death screen installed (now {})",
                mc.screen == null ? "null" : mc.screen.getClass().getSimpleName());
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


    private static boolean isEncounterPresent()
    {
        return HudSuppression.isEncounterPresent();
    }

    public static void renderOwnBarDirectly(PoseStack poseStack)
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
        BAR.render(poseStack, TITLE, mc.getWindow().getGuiScaledWidth(), 12);
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event)
    {
        if (!BAR.isVisible())
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PoseStack poseStack = event.getPoseStack();
        int width = mc.getWindow().getGuiScaledWidth();
        BAR.render(poseStack, TITLE, width, 12);
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event)
    {
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
}
