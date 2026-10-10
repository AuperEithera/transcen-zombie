package com.example.transcenmod.client;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.client.audio.TranscenMusic;
import com.example.transcenmod.client.bar.TranscenBossBar;
import com.example.transcenmod.entity.TranscenZombie;
import com.example.transcenmod.event.TranscenEvents;
import com.example.transcenmod.network.NetworkHandler;
import com.example.transcenmod.network.WipeRequestMessage;
import com.example.transcenmod.util.EntityWiper;
import com.example.transcenmod.util.RetaliationState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovementInput;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ClientEvents {

    private static final TranscenBossBar BAR = new TranscenBossBar();

    private static final String TITLE = "TRANSCENDENCE ZOMBIE";

    private static int tickCount;

    private static final Set<RenderGameOverlayEvent.ElementType> VANILLA_AND_FORGE =
            EnumSet.of(RenderGameOverlayEvent.ElementType.ALL,
                    RenderGameOverlayEvent.ElementType.HELMET,
                    RenderGameOverlayEvent.ElementType.PORTAL,
                    RenderGameOverlayEvent.ElementType.CROSSHAIRS,
                    RenderGameOverlayEvent.ElementType.BOSSHEALTH,
                    RenderGameOverlayEvent.ElementType.BOSSINFO,
                    RenderGameOverlayEvent.ElementType.ARMOR,
                    RenderGameOverlayEvent.ElementType.HEALTH,
                    RenderGameOverlayEvent.ElementType.FOOD,
                    RenderGameOverlayEvent.ElementType.AIR,
                    RenderGameOverlayEvent.ElementType.HOTBAR,
                    RenderGameOverlayEvent.ElementType.EXPERIENCE,
                    RenderGameOverlayEvent.ElementType.TEXT,
                    RenderGameOverlayEvent.ElementType.HEALTHMOUNT,
                    RenderGameOverlayEvent.ElementType.JUMPBAR,
                    RenderGameOverlayEvent.ElementType.CHAT,
                    RenderGameOverlayEvent.ElementType.PLAYER_LIST,
                    RenderGameOverlayEvent.ElementType.DEBUG,
                    RenderGameOverlayEvent.ElementType.POTION_ICONS,
                    RenderGameOverlayEvent.ElementType.SUBTITLES,
                    RenderGameOverlayEvent.ElementType.FPS_GRAPH,
                    RenderGameOverlayEvent.ElementType.VIGNETTE);

    private static final float[][] TINT_COLORS = {
            {1.0F, 0.0F, 0.0F},
            {1.0F, 1.0F, 0.0F},
            {0.5F, 0.0F, 0.5F},
            {0.0F, 1.0F, 0.0F},
            {0.0F, 1.0F, 1.0F}
    };

    // -------------------------------------------------------------------------------------------
    // Static entry points, called from network/ClientPacketHooks
    // -------------------------------------------------------------------------------------------

    public static void activateHud() {
        AnnihilationClientState.setProtectionOff(false);
        TranscenMod.HUD_ACTIVE = true;
        AnnihilationClientState.latchHud();
    }

    // the bar and the music are presentation, so they go first and unconditionally. the zombie removal is
    // best effort: the entities are the server's, this only stops them being drawn.
    public static void deactivateHud() {
        TranscenMod.HUD_ACTIVE = false;
        AnnihilationClientState.unlatchHud();
        AnnihilationClientState.setHud(false, 0.0F);

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != null) {
            // a copy, because the wiper takes entities out of the very list this walks
            for (Entity entity : new ArrayList<Entity>(mc.world.loadedEntityList)) {
                if (entity instanceof TranscenZombie) {
                    entity.setDead();
                }
            }
            // and then every loaded chunk, because the list above is only one of the two places the render
            // loop reads from - RenderGlobal:609 walks the chunk sections directly.
            EntityWiper.wipeAll(mc.world);
        }

        AnnihilationClientState.setProtectionOff(true);
        BAR.setVisible(false);
        TranscenMusic.stop();
    }

    // -------------------------------------------------------------------------------------------
    // Wipe requests
    // -------------------------------------------------------------------------------------------

    private static void requestWipe() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) {
            return;
        }
        // Holding, not carrying: the wipe is triggered by swinging the egg, and requiring it to be in hand
        // keeps an egg parked in a backpack slot from firing the wipe on every stray click.
        if (!TranscenEvents.isHoldingEgg(mc.player)) {
            return;
        }
        deactivateHud();
        NetworkHandler.sendToServer(new WipeRequestMessage());
    }

    // Sneaking is the item's other gesture, so the wipe must not also fire on a shift-click. Without this a
    // single shift-click ran both: it toggled the defence and printed both chat lines at once.
    @SubscribeEvent
    public void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (isSneaking(event.getEntityPlayer())) {
            return;
        }
        requestWipe();
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (isSneaking(event.getEntityPlayer())) {
            return;
        }
        requestWipe();
    }

    // Guarded like the other two: attacking is still a left click, and shift is the item's other gesture.
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (isSneaking(event.getEntityPlayer())) {
            return;
        }
        requestWipe();
    }

    private static boolean isSneaking(EntityPlayer player) {
        return player != null && player.isSneaking();
    }

    // -------------------------------------------------------------------------------------------
    // Death screen
    // -------------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onScreenOpening(GuiScreenEvent.InitGuiEvent.Pre event) {
        if (!(event.getGui() instanceof GuiGameOver)) {
            return;
        }
        if (TranscenMod.SHOW_DEATH_SCREEN) {
            TranscenMod.LOGGER.info("death screen let through (forced by the mod)");
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;
        if (player == null || mc.world == null) {
            return;
        }

        if (!TranscenEvents.isCarryingEgg(player)) {
            TranscenMod.LOGGER.info("death screen not suppressed: egg not held (health {}, main={}, "
                            + "off={}, expected={})", player.getHealth(), player.getHeldItemMainhand(),
                    player.getHeldItemOffhand(), TranscenMod.spawnEgg);
            return;
        }

        player.setHealth(TranscenEvents.eggHolderHealth());
        TranscenMod.LOGGER.info("death screen suppressed for egg holder");
        event.setCanceled(true);
    }

    // -------------------------------------------------------------------------------------------
    // Ticks
    // -------------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onClientPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        EntityPlayer player = event.player;
        // The event is fired for every player the client knows about; only the local one is pinned here.
        if (player != Minecraft.getMinecraft().player) {
            return;
        }

        if (TranscenEvents.isCarryingEgg(player)
                && player.getHealth() < TranscenEvents.eggHolderHealth()) {
            TranscenMod.LOGGER.info("client pinned health {} -> {}",
                    player.getHealth(), TranscenEvents.eggHolderHealth());
            player.setHealth(TranscenEvents.eggHolderHealth());
        }

        // Once a second, in case the file was edited by hand. This is the way out of the AllReturn switch
        // that goes through no input at all, which is what is left when a guarded delegate has taken the
        // mouse with it and neither button does anything any more.
        if (player.world != null && player.world.getTotalWorldTime() % 20L == 0L) {
            com.example.transcenmod.core.AllReturn.followFile();
        }

        TranscenMod.instance.setHealthProtected(player.getUniqueID(), !RetaliationState.isActive());
    }


    // the fake death screen, drawn as a full screen quad instead of a GuiScreen. GuiGameOver would come with
    // clickable Respawn and Title Screen buttons, which is exactly what gives the trick away.

    // re-entrancy guard for onScreenOpened. displayGuiScreen(null) does not fire InitGuiEvent again, but the
    // close is called from inside the fabric that posts it, so the flag keeps that assumption explicit.
    private boolean closingScreen;

    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL || !TranscenMod.SHOW_DEATH_SCREEN) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) {
            return;
        }

        holdInput(mc);
        drawPoster(mc);
    }

    // the drawing itself lives in PosterRenderer and is raw lwjgl; see there for the projection and the
    // texture upload. this class only decides when the frame is.
    private void drawPoster(Minecraft mc) {
        PosterRenderer.draw(mc);
    }

    private void holdInput(Minecraft mc) {
        mc.gameSettings.hideGUI = false;
        mc.gameSettings.showDebugInfo = false;
        mc.gameSettings.thirdPersonView = 0;
        mc.inGameHasFocus = false;

        if (!mouseReleased) {
            mouseReleased = true;
            mc.mouseHelper.ungrabMouseCursor();
        }

        // clicks are not gated by inGameHasFocus - only block breaking is (Minecraft.java L2268). Attack, use
        // and pick block fire straight off the key binding state (Minecraft.java L2247-2260), so the binding
        // itself is what has to read as released.
        release(mc.gameSettings.keyBindAttack);
        release(mc.gameSettings.keyBindUseItem);
        release(mc.gameSettings.keyBindPickBlock);
    }

    private boolean mouseReleased;

    private static void release(KeyBinding binding) {
        KeyBinding.setKeyBindState(binding.getKeyCode(), false);
    }

    @SubscribeEvent
    public void onScreenOpened(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!TranscenMod.SHOW_DEATH_SCREEN || closingScreen) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == null) {
            return;
        }
        closingScreen = true;
        try {
            mc.displayGuiScreen(null);
        } finally {
            closingScreen = false;
        }
    }

    @SubscribeEvent
    public void onSound(PlaySoundEvent event) {
        if (!TranscenMod.SHOW_DEATH_SCREEN) {
            return;
        }
        event.setResultSound(null);
    }

    @SubscribeEvent
    public void onInputUpdate(InputUpdateEvent event) {
        if (!TranscenMod.SHOW_DEATH_SCREEN) {
            return;
        }
        MovementInput input = event.getMovementInput();
        input.moveForward = 0.0F;
        input.moveStrafe = 0.0F;
        input.jump = false;
        input.sneak = false;
        input.forwardKeyDown = false;
        input.backKeyDown = false;
        input.leftKeyDown = false;
        input.rightKeyDown = false;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCount++;

        Minecraft mc = Minecraft.getMinecraft();
        boolean remoteHud = AnnihilationClientState.isHudActive();
        boolean zombieVisible = mc.world != null && zombiePresent(mc.world);
        if (zombieVisible) {
            AnnihilationClientState.latchHud();
        }

        boolean active = (TranscenMod.HUD_ACTIVE
                || remoteHud
                || AnnihilationClientState.isHudLatched()
                || zombieVisible) && mc.world != null;
        if (AnnihilationClientState.isProtectionOff()) {
            active = false;
        }

        // The remote progress is only meaningful when the server is the one driving the bar. On a locally
        // latched client (the zombie is here but no sync has arrived) the full bar is the honest display:
        // the client has no idea how far along the wipe is, and an empty bar would read as "finished".
        BAR.setProgress(remoteHud ? clamp(AnnihilationClientState.hudProgress()) : 1.0F);
        BAR.setVisible(active);
        BAR.tick();
        TranscenMusic.tick(active);
    }

    // -------------------------------------------------------------------------------------------
    // Overlay
    // -------------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onRenderGuiPost(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.HOTBAR) {
            return;
        }
        if (!BAR.isVisible()) {
            return;
        }
        BAR.render(TITLE, 12);
    }

    @SubscribeEvent(priority = EventPriority.NORMAL, receiveCanceled = true)
    public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (!isEncounterPresent()) {
            return;
        }
        if (isVanillaOrForge(event.getType())) {
            return;
        }
        // 1.12.2 decides cancelability per class through an ASM-injected isCancelable(), and the answer
        // for this particular subclass is not something the source makes obvious. Asking costs nothing
        // and turns a hard crash into a no-op, which the Post handler below still covers.
        if (!event.isCancelable()) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.NORMAL, receiveCanceled = true)
    public void onPosterFrame(RenderGameOverlayEvent.Post event) {
        if (!isEncounterPresent()) {
            return;
        }
        if (isVanillaOrForge(event.getType())) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onFogColors(EntityViewRenderEvent.FogColors event) {
        if (!BAR.isVisible()) {
            return;
        }
        float[] tint = TINT_COLORS[(tickCount / 40) % TINT_COLORS.length];
        event.setRed(tint[0]);
        event.setGreen(tint[1]);
        event.setBlue(tint[2]);
    }

    @SubscribeEvent
    public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        AnnihilationClientState.reset();
        TranscenMod.HUD_ACTIVE = false;
        BAR.setVisible(false);
        TranscenMusic.stop();
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private static boolean isVanillaOrForge(RenderGameOverlayEvent.ElementType type) {
        return VANILLA_AND_FORGE.contains(type);
    }

    private static boolean isEncounterPresent() {
        return HudSuppression.isEncounterPresent();
    }

    private static float clamp(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        return value > 1.0F ? 1.0F : value;
    }

    static boolean zombiePresent(WorldClient level) {
        for (Entity entity : level.loadedEntityList) {
            if (entity instanceof TranscenZombie) {
                return true;
            }
        }
        return false;
    }
}
