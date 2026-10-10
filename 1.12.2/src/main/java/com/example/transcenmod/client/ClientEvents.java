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

/**
 * Everything the client reacts to during an encounter: the boss bar and music, the HUD blackout, the
 * suppression of the death screen and the fog tint.
 *
 * <p>Registered as an <em>instance</em> on {@code MinecraftForge.EVENT_BUS} from
 * {@code ClientProxy#preInit}. That is why none of the handlers below are static even though several of
 * them need no instance state: 1.12.2's event bus accepts both, but a static handler class cannot also be
 * reached by the packet hooks (which are static and would need a class literal) without the bus
 * registering the same class twice, and mixing the two styles in one class is a reliable way to get a
 * handler that is never called. The three static methods at the top are the deliberate exceptions, because
 * they are the entry points the network layer calls by name.
 */
@SideOnly(Side.CLIENT)
public class ClientEvents {

    /** The one bar instance; see {@link TranscenBossBar} for why it is shared. */
    private static final TranscenBossBar BAR = new TranscenBossBar();

    private static final String TITLE = "TRANSCENDENCE ZOMBIE";

    /** Client ticks since load. Drives the fill colour and the fog tint; both wrap on their own. */
    private static int tickCount;

    // overlay elements this mod must leave alone. the suppression below cancels anything not in here, which
    // is only sound because this generation's ElementType is a plain enum: there is no ResourceLocation on
    // it and no registry behind it, so the test has to be "is this one of the elements that existed then",
    // enumerated explicitly. a mod that adds its own constant to this enum cannot be told from vanilla by
    // that test, which is why the list is kept complete rather than trimmed to what the encounter draws over.
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

    /** Fog tints cycled through {@code onFogColors}, as {red, green, blue} in 0..1. */
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

    /**
     * Starts the encounter presentation: the bar, the music, the blackout.
     *
     * <p>Clearing {@code protectionOff} is what makes this a real restart rather than just "show the bar".
     * Without it, a client that had already survived one wipe would keep the flag from
     * {@link #deactivateHud} and the next encounter's bar would be forced invisible on the very next tick.
     */
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
                    EntityWiper.wipe(entity);
                }
            }
        }

        AnnihilationClientState.setProtectionOff(true);
        BAR.setVisible(false);
        TranscenMusic.stop();
        // Deliberately not touching the zombie generation counter here. That static is shared with the
        // server, and a client bumping it would make the server's next spawn think a generation had already
        // been consumed -- the wipe would then restart one generation ahead of where the server believes it
        // is. Advancing it is the server's job, in TranscenEvents.
    }

    // -------------------------------------------------------------------------------------------
    // Wipe requests
    // -------------------------------------------------------------------------------------------

    /**
     * Sends the "I used the egg" request.
     *
     * <p>Three different events route here because the same physical action produces a different event
     * depending on what is under the crosshair, and picking just one of them would make the egg dead in the
     * other two cases:
     *
     * <ul>
     *   <li>{@code LeftClickEmpty} when nothing is in reach -- and note that this event is fired on the
     *       client only, so it cannot be handled server-side at all; forwarding it is mandatory, not an
     *       optimisation.</li>
     *   <li>{@code LeftClickBlock} when a block is in reach.</li>
     *   <li>{@code AttackEntityEvent} when an entity is in reach.</li>
     * </ul>
     *
     * <p>None of the three is cancelled. Swinging at the air, mining a block and hitting a zombie all keep
     * their vanilla meaning; this only adds a request on the way past. The local teardown before sending is
     * what makes the response feel immediate -- the bar goes at the click rather than a round trip later --
     * and the server's own {@code WipeEndedMessage} repeats it as the authoritative version.
     */
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

    @SubscribeEvent
    public void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        requestWipe();
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        requestWipe();
    }

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        requestWipe();
    }

    // -------------------------------------------------------------------------------------------
    // Death screen
    // -------------------------------------------------------------------------------------------

    /**
     * Swallows the death screen for a player who is still carrying the egg.
     *
     * <p>Vanilla opens {@code GuiGameOver} from {@code EntityPlayerSP#onDeath}; the screen itself is opened
     * elsewhere, so cancelling this event is enough to stop it appearing while leaving the death handling
     * (drops, statistics, the respawn button's prerequisites) to run normally.
     *
     * <p>Two ways out before the cancel. If {@code SHOW_DEATH_SCREEN} is set, the screen was asked for
     * deliberately -- by the packet that ends the encounter, so the player finally sees the death they
     * earned -- and suppressing it here would make that packet a no-op.
     *
     * <p>The health is re-pinned before cancelling. A screen that is never shown is not the same as a
     * player who is alive: whatever damage killed them already ran, and without the write the player would
     * be at zero health behind a screen that is not there, which the next tick's pin would then undo. The
     * diagnostics log in the not-carrying branch is the one place that reports the four values needed to
     * tell "no egg" apart from "wrong item registered" when the suppression does not happen.
     */
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

    /**
     * Keeps the egg holder alive on the client's own copy of the player.
     *
     * <p>The client's health is the server's to set, and the mod does not pretend otherwise -- but between
     * the server's heal packet and the client's own prediction of a lethal hit there is a window in which
     * the local player is at zero health. That window is what opens the death screen and what the
     * {@code DeathScreenMessage} is for; this handler closes it on the ordinary ticks.
     *
     * <p>{@code setHealthProtected} is called with the negation of the retaliation state rather than a
     * constant: while the player is retaliating the injected health read has to report the real value so
     * the damage that ends the encounter can actually be seen, and the flag has to drop at exactly the same
     * moment.
     */
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

        TranscenMod.instance.setHealthProtected(player.getUniqueID(), !RetaliationState.isActive());
    }

    // -------------------------------------------------------------------------------------------
    // The poster. SHOW_DEATH_SCREEN means "the fake death screen is up", and it stays up: there is no
    // clear condition, which is the point - the player is meant to believe the game died there.
    // -------------------------------------------------------------------------------------------

    // the fake death screen, drawn as a full screen quad instead of a GuiScreen. GuiGameOver would come with
    // clickable Respawn and Title Screen buttons, which is exactly what gives the trick away.

    // re-entrancy guard for onScreenOpened. displayGuiScreen(null) does not fire InitGuiEvent again, but the
    // close is called from inside the fabric that posts it, so the flag keeps that assumption explicit.
    private boolean closingScreen;

    /**
     * Last thing in the frame. RenderGameOverlayEvent.Post with ALL is posted at the very end of
     * GuiIngameForge#renderGameOverlay (GuiIngameForge.java L207), after chat and the player list, so an
     * opaque quad here covers the whole vanilla HUD without having to cancel any of it. Anything drawn after
     * this point is a GuiScreen, and onScreenOpened closes those instead.
     */
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

    /**
     * Everything that would let the player notice the screen is fake, re-asserted every frame because vanilla
     * writes these from its own key loop regardless of what a handler does once.
     *
     * F1 toggles hideGUI (Minecraft.java L1967-1970) and with hideGUI set the hud is skipped entirely
     * (EntityRenderer.java L1093), which would uncover the world. F3 and F5 are cheap to pin for the same
     * reason. The mouse is ungrabbed so Mouse#getDX stops moving the camera - neither InputEvent is
     * cancelable (InputEvent.java declares both without @Cancelable), so the event bus cannot do this.
     */
    private void holdInput(Minecraft mc) {
        mc.gameSettings.hideGUI = false;
        mc.gameSettings.showDebugInfo = false;
        mc.gameSettings.thirdPersonView = 0;
        mc.inGameHasFocus = false;

        // once, not every frame. a vanilla death screen releases the cursor exactly once, when the screen
        // opens, and then shows it. ungrabMouseCursor also calls Mouse.setCursorPosition(centre), so calling
        // it per frame re-centres the OS cursor every frame, which is what made the pointer look pinned
        // rather than released. deltas read zero while ungrabbed, so the view stays locked on its own.
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

    /**
     * A screen is the one thing that can appear over the poster: EntityRenderer draws the current screen after
     * renderGameOverlay (EntityRenderer.java L1118), so a GuiScreen would sit on top of the image. Every
     * screen is closed instead, which is also what makes ESC do nothing - it reaches displayInGameMenu
     * (Minecraft.java L1959-1961) and opens GuiIngameMenu, and that screen is closed here in the same frame.
     * The screen is never drawn, so the player sees no flicker.
     */
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

    /**
     * Silence for as long as the poster is up. PlaySoundEvent is not cancelable (it declares no @Cancelable
     * and derives from SoundEvent, which does not either), so the documented way is to replace the sound with
     * null. That is every sound the client is asked to play, not just the world's.
     */
    @SubscribeEvent
    public void onSound(PlaySoundEvent event) {
        if (!TranscenMod.SHOW_DEATH_SCREEN) {
            return;
        }
        event.setResultSound(null);
    }

    /**
     * Movement, zeroed after vanilla has read the keys and before anything uses the result:
     * EntityPlayerSP#onLivingUpdate calls updatePlayerMoveState() and then ForgeHooksClient.onInputUpdate
     * (EntityPlayerSP.java L863-864), and the values are consumed further down. InputUpdateEvent hands the
     * MovementInput over, so writing the fields here is enough and no tick ordering guesswork is needed.
     */
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

    /**
     * The one tick that keeps every other piece of client presentation in step.
     *
     * <p>The bar is shown if any of four signals says so, and forced off if the encounter has been declared
     * over -- {@code protectionOff} wins over everything, including a zombie that is still loaded, because
     * it is only ever set by the teardown path and cleared by a new encounter.
     *
     * <p>{@code latchHud} on a loaded zombie is the recovery path for a client that joined mid-encounter
     * and never received an activate packet: seeing the entity is enough to commit to the presentation.
     */
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

    /**
     * Draws the bar with the hotbar, which is the last element drawn before the chat and debug layers.
     *
     * <p>Hooking the same element the vanilla boss bar uses keeps the bar above the HUD it replaces and
     * below the chat, without needing a layer of its own.
     */
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

    /**
     * Blacks out the vanilla HUD while the encounter runs.
     *
     * <p>{@code receiveCanceled = true} and {@code EventPriority.NORMAL} are both load-bearing.
     * {@code RenderGameOverlayEvent} is a cancellable event that the vanilla renderer fires per element and
     * then checks, so a handler that runs after another mod has already cancelled the element still needs
     * to see it (that is {@code receiveCanceled}). The priority is left at normal so that a mod wanting the
     * last word can take it by registering later rather than by fighting over the number.
     *
     * <p>The Post variant is needed as well as Pre: several of these elements are drawn by a single call, so
     * cancelling the pre-event of the element that starts the group does not stop the drawing that has
     * already been queued by the caller's own loop. Cancelling both ends is the only complete cut.
     */
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

    /** Mirror of {@link #onOverlayPre}; see there for why both ends are cancelled. */
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

    /**
     * Tints the fog while the encounter runs.
     *
     * <p>This is the non-destructive replacement for the older build's trick of clearing the colour buffer
     * right after the sky was drawn. That approach recoloured everything drawn afterwards -- including the
     * HUD, since the buffer clear happened before it -- and it could not be limited to the world. The fog
     * colour hook changes only the value vanilla uses to blend distance fog, which is exactly the effect
     * wanted: the world goes red and nothing else does.
     */
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

    /**
     * Forgets everything on disconnect.
     *
     * <p>Nothing here is persisted, so the only requirement is that no flag survives into the next
     * connection -- a stale latched bar would follow the player onto another server.
     */
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

    /** Local clamp, so the tick handler does not need {@code MathHelper} for one expression. */
    private static float clamp(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        return value > 1.0F ? 1.0F : value;
    }

    /**
     * Whether a loaded {@code TranscenZombie} is in the given client world.
     *
     * <p>Package-private rather than private because {@link HudSuppression} needs the same answer for its
     * own check, and a second copy of this loop is exactly the kind of thing that drifts. It reads the
     * world's own live list, so a zombie that has been marked dead but not yet removed still counts -- which
     * is deliberate: the presentation should survive until the entity is actually gone, not flicker off in
     * the tick between the two.
     */
    static boolean zombiePresent(WorldClient level) {
        for (Entity entity : level.loadedEntityList) {
            if (entity instanceof TranscenZombie) {
                return true;
            }
        }
        return false;
    }
}
