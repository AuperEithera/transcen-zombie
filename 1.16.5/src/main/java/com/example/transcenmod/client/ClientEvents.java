package com.example.transcenmod.client;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.client.audio.TranscenMusic;
import com.example.transcenmod.client.bar.TranscenBossBar;
import com.example.transcenmod.entity.TranscenZombie;
import com.example.transcenmod.event.TranscenEvents;
import com.example.transcenmod.network.ActivateHudPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class ClientEvents
{

    private static final TranscenBossBar BAR = new TranscenBossBar();
    private static final ITextComponent TITLE =
            new StringTextComponent("TRANSCENDENCE ZOMBIE")
                    .mergeStyle(net.minecraft.util.text.TextFormatting.BOLD,
                            net.minecraft.util.text.TextFormatting.ITALIC);

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

    public static void deactivateHud()
    {
        TranscenMod.HUD_ACTIVE = false;
        AnnihilationClientState.unlatchHud();
        AnnihilationClientState.setHud(false, 0.0F);
        AnnihilationClientState.clearAllDoomed();

        Minecraft client = Minecraft.getInstance();
        if (client.world != null)
        {
            java.util.List<Entity> copies = new java.util.ArrayList<>();
            for (Entity entity : client.world.getAllEntities())
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
                    entity.remove(false);
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
        // deliberately does NOT advance the zombie generation - that static counter is shared with the
        // server and retiring zombies is the server's call; bumping it here made the client advance it
        // too, and this method runs from three packet handlers so one wipe advanced it several times
        // every advance retired the zombies again,which is the flicker
        // the server dismisses them,this side only drops its copies and stops drawing
    }

    private static void requestWipe()
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null)
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
    public static void onScreenOpening(GuiScreenEvent.InitGuiEvent event)
    {
        if (!(event.getGui() instanceof DeathScreen))
        {
            return;
        }
        if (TranscenMod.SHOW_DEATH_SCREEN)
        {
            TranscenMod.LOGGER.info("[transcenmod] death screen let through (forced by the mod)");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PlayerEntity player = mc.player;
        if (player == null || mc.world == null)
        {
            return;
        }
        if (!TranscenEvents.isCarryingEgg(player))
        {
            TranscenMod.LOGGER.info(
                    "[transcenmod] death screen not suppressed: egg not held "
                            + "(health {}, main={}, off={}, expected={})",
                    player.getHealth(),
                    player.getHeldItemMainhand(),
                    player.getHeldItemOffhand(),
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
        PlayerEntity player = event.player;
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
        DeathScreen screen = new DeathScreen(new TranslationTextComponent("deathScreen.title"), false);
        screen.resize(mc, mc.getMainWindow().getScaledWidth(), mc.getMainWindow().getScaledHeight());
        // displayGuiScreen,not a bare field write -
        // the bare write was meant to deny other mods a veto over GuiScreenEvent.InitGuiEvent,but it also
        // skipped everything displayGuiScreen does around that event: the outgoing screen's removal, the
        // incoming screen's init() and -the one that mattered- the mouse ungrab
        // the result was a client that thought no screen was open while the pointer stayed captured and
        // the widgets were never built,so the player could neither move nor use the hotbar
        // a veto is still defended against by re-asserting once after the event has already fired
        mc.displayGuiScreen(screen);
        if (mc.currentScreen != screen)
        {
            mc.displayGuiScreen(screen);
        }
        TranscenMod.LOGGER.info(
                "[transcenmod] forced death screen installed (now {})",
                mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName());
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
        boolean zombieVisible = mc.world != null && zombiePresent(mc.world);
        if (zombieVisible)
        {
            AnnihilationClientState.latchHud();
        }
        boolean active = (TranscenMod.HUD_ACTIVE
                || remoteHud
                || AnnihilationClientState.isHudLatched()
                || zombieVisible) && mc.world != null;

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

    public static void renderOwnBarDirectly(MatrixStack poseStack)
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
        BAR.render(poseStack, TITLE, mc.getMainWindow().getScaledWidth(), 12);
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGameOverlayEvent.Post event)
    {
        if (!BAR.isVisible())
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MatrixStack poseStack = event.getMatrixStack();
        int width = mc.getMainWindow().getScaledWidth();
        BAR.render(poseStack, TITLE, width, 12);
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGameOverlayEvent.Post event)
    {
    }

    private static final float TINT_COLORS[][] = {
            {1f, 0f, 0f}, {1f, 1f, 0f}, {0.5f, 0f, 0.5f}, {0f, 1f, 0f}, {0f, 1f, 1f}
    };

    @SubscribeEvent
    public static void onFogColors(net.minecraftforge.client.event.EntityViewRenderEvent.FogColors event)
    {
        if (!BAR.isVisible())
        {
            return;
        }
        float c[] = TINT_COLORS[(tickCount / 40) % TINT_COLORS.length];
        event.setRed(c[0]);
        event.setGreen(c[1]);
        event.setBlue(c[2]);
    }

    @SubscribeEvent
    public static void onLoggedOutEvent(ClientPlayerNetworkEvent.LoggedOutEvent event)
    {
        AnnihilationClientState.reset();
        TranscenMod.HUD_ACTIVE = false;
        BAR.setVisible(false);
        TranscenMusic.stop();
    }

    static boolean zombiePresent(ClientWorld level)
    {
        for (net.minecraft.entity.Entity entity : level.getAllEntities())
        {
            if (entity instanceof TranscenZombie)
            {
                return true;
            }
        }
        return false;
    }
}
