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

/**
 * Client-side presentation: the Transcendence bar, the screen rendering and the background loop.
 *
 * <p>Visibility is server-driven through {@code HudSyncPacket}, because the bar has to be correct
 * even when the client's rendering view does not contain the zombie. Right-clicking the spawn egg
 * additionally arms it locally via {@link ActivateHudPacket}. Either source is enough.
 *
 * <p>No member touched here needs AccessTransformer widening; this package lives entirely inside
 * public Forge API. Registered from {@code FMLClientSetupEvent}, so it never loads on a dedicated
 * server.
 */
public class ClientEvents {

    private static final TranscenBossBar BAR = new TranscenBossBar();
    private static final Component TITLE =
            Component.literal("TRANSCENDENCE ZOMBIE")
                    .withStyle(style -> style.withBold(true).withItalic(true));

    private static int tickCount = 0;

    static {
        // The packet handler reaches the client half through this hook, so the common-side code
        // never has to name a client-only class.
        ActivateHudPacket.setClientHook(ClientEvents::activateHud);
        com.example.transcenmod.network.WipeEndedPacket.setClientHook(ClientEvents::deactivateHud);
        com.example.transcenmod.network.WipeRequestPacket.setClientHook(ClientEvents::deactivateHud);
    }

    /** Called from the packet handler when the spawn egg is used. */
    public static void activateHud() {
        // Right-click switched the protection back on, so the override is lifted and the bar and the
        // music are allowed to run again.
        AnnihilationClientState.setProtectionOff(false);
        TranscenMod.HUD_ACTIVE = true;
        // Latch as well as switch on. The server's later "no zombie" updates must not be able to turn
        // the encounter back off, or the bar and the overlay strobe whenever something removes the
        // zombie and the repair puts it back.
        AnnihilationClientState.latchHud();
    }

    /**
     * Ends the encounter on this client: the bar goes, the music stops, and the sticky latch is released.
     *
     * <p>The latch is the important part. It exists so a temporary removal cannot make the bar flicker,
     * and it is deliberately immune to the server's "off" updates -- so a wipe has to clear it explicitly
     * or the bar would outlive the zombies it belongs to.
     */
    public static void deactivateHud() {
        TranscenMod.HUD_ACTIVE = false;
        AnnihilationClientState.unlatchHud();
        AnnihilationClientState.setHud(false, 0.0F);
        // Drop the shells, so no red remnant is left over an entity the server has discarded.
        AnnihilationClientState.clearAllDoomed();

        // And take this side's copies out of the level by hand.
        //
        // Clearing the shell set stops the red tint, not the zombie: the client entity is still in its own
        // level and is still drawn. That is why the rendering survived with the protection switched off --
        // the server's copy was gone and this one was not. CustomEntityRemover has a client branch for
        // exactly this situation, so the same surgery is applied here.
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            java.util.List<Entity> copies = new java.util.ArrayList<>();
            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity instanceof com.example.transcenmod.entity.TranscenZombie) {
                    copies.add(entity);
                }
            }
            for (Entity entity : copies) {
                try {
                    com.example.transcenmod.util.CustomEntityRemover.removeEntity(entity);
                } catch (Throwable failure) {
                    TranscenMod.LOGGER.warn("[transcenmod] client-side removal failed", failure);
                }
            }
        }

        AnnihilationClientState.setProtectionOff(true);
        BAR.setVisible(false);
        TranscenMusic.stop();
        // Deliberately does NOT advance the zombie generation. That counter is static and shared with the
        // server, and retiring zombies is the server's decision; doing it here made the client bump it as
        // well -- and this method is called from three packet handlers, so one wipe advanced it several
        // times. Every advance retired the zombies again, which is the flicker: removed, restored,
        // removed. The server dismisses them; the client only drops its copies and stops drawing.
    }

    /**
     * Left-click while carrying the egg asks the server to wipe the encounter.
     *
     * <p>Three cases are covered because they arrive by different routes. {@code LeftClickEmpty} only
     * exists on the client, so it has to be forwarded; {@code LeftClickBlock} and {@code AttackEntityEvent}
     * cover swings that landed on something. Without the third, hitting a zombie with the egg would not
     * wipe, only hitting air or a block would.
     *
     * <p>Nothing is cancelled: this is an added effect, and suppressing the swing would also suppress the
     * client's own hit feedback.
     */
    private static void requestWipe() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!TranscenEvents.isHoldingEgg(mc.player)) {
            return;
        }
        TranscenMod.NETWORK.sendToServer(new com.example.transcenmod.network.WipeRequestPacket());
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickEmpty event) {
        requestWipe();
    }

    @SubscribeEvent
    public static void onLeftClickBlock(net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
        requestWipe();
    }

    @SubscribeEvent
    public static void onAttackEntity(net.minecraftforge.event.entity.player.AttackEntityEvent event) {
        requestWipe();
    }

    /**
     * Refuses to open the death screen while the player holds this mod's spawn egg.
     *
     * <p>This is the entry point the user asked for, and it is the right one: the death screen is a
     * <b>screen</b>, so the only place that can decide not to show it is the screen-opening path.
     * Server-side guards cannot cover this, because health can be moved without going through damage at
     * all -- a direct {@code setHealth(0)} or a command never raises a damage or death event, so
     * anything hooked to those is simply not consulted.
     *
     * <p>Cancelling is used rather than swapping in a different screen. Replacing it would still be "a
     * screen the player did not ask for", and cancelling leaves whatever screen is already open
     * untouched, which is what "the GUI never opened" should look like.
     *
     * <p>Health is restored in the same breath. The client can be told it has no health by the server
     * long before the death screen is requested, and a player sitting at zero health with the screen
     * suppressed is still a player the game considers dead -- the screen would be requested again on
     * the next update. Pinning it here closes that loop.
     *
     * <p>The mod's own forced screen is exempt. When the zombie reflects damage it deliberately asks for
     * the death overlay, and refusing that here would cancel the very thing the packet was sent to do.
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof DeathScreen)) {
            return;
        }
        if (TranscenMod.SHOW_DEATH_SCREEN) {
            // Reported so the two "the screen did not appear" cases can be told apart: the forced screen
            // being let through, versus the forced screen never having been requested at all.
            TranscenMod.LOGGER.info("[transcenmod] death screen let through (forced by the mod)");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        if (!TranscenEvents.isCarryingEgg(player)) {
            // Reports what is actually in each hand rather than only that the check failed. "Not
            // holding" has two very different causes -- the item genuinely is not there, or the lookup is
            // consulting the wrong player object -- and printing the stacks separates them in one run
            // instead of another round of guessing.
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

    /**
     * Pins the local player's health on the client, every tick.
     *
     * <p>Half of the "hold the egg and cannot die" guarantee. The server half pins the authoritative
     * value; this half matters because the death screen is a client-side reaction. A player whose
     * client has been told it is at zero health will keep asking for that screen on every sync, and
     * suppressing the request each time leaves the client permanently believing it is dead. Keeping the
     * local value off zero removes the disagreement at its source instead of arguing with it once per
     * update.
     */
    @SubscribeEvent
    public static void onClientPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (!TranscenEvents.isCarryingEgg(player)) {
            return;
        }
        if (player.getHealth() < TranscenEvents.eggHolderHealth()) {
            TranscenMod.LOGGER.info(
                    "[transcenmod] client pinned health {} -> {}",
                    player.getHealth(), TranscenEvents.eggHolderHealth());
            player.setHealth(TranscenEvents.eggHolderHealth());
        }
    }

    /**
     * Draws the forced death overlay for one frame.
     *
     * <p>This is the port of the 1.12.2 render-tick hook that did
     * {@code new GuiGameOver(null).drawScreen(0, 0, 0)}, but it deliberately does <b>not</b> use the
     * obvious modern equivalent, {@code Minecraft#setScreen}.
     *
     * <p>The reason is that {@code setScreen} raises {@code ScreenEvent.Opening} first. That event is
     * cancelable, and this very class cancels it to keep the death screen shut -- so any other mod
     * watching the same event can cancel this screen too. Placing the screen through {@code setScreen}
     * therefore made the forced overlay the single most interceptable step in the whole feature, which
     * is the opposite of what was asked for.
     *
     * <p>{@code Minecraft.screen} is a plain public non-final field in this version (SRG
     * {@code f_91080_}), so it can be assigned directly. That skips {@code setScreen} entirely, and
     * with it the event: nothing listening for a screen opening is ever consulted, because nothing ever
     * announces one. {@code forceSetScreen} is not an alternative -- its bytecode calls
     * {@code setScreen} internally, so it raises the same event.
     *
     * <p>What is given up by skipping the event is also what made the event useful: other mods get no
     * chance to react. Nothing else in vanilla depends on that event for a screen to work, so the screen
     * still initialises and renders normally; {@code resize} is called explicitly to set the dimensions,
     * since {@code setScreen} would otherwise have been the thing to do it.
     *
     * <p>The flag is consumed only after the screen has been installed, so a failure part-way through
     * cannot silently swallow the request.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !TranscenMod.SHOW_DEATH_SCREEN) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // The hardcore flag is false: this is a scare, not a real hardcore death, and true would make
        // the screen offer a "return to title" instead of the normal respawn button.
        DeathScreen screen = new DeathScreen(Component.translatable("deathScreen.title"), false);
        screen.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        // Direct field write -- no ScreenEvent.Opening, so no other mod can veto it.
        mc.screen = screen;
        TranscenMod.LOGGER.info(
                "[transcenmod] forced death screen installed (now {})",
                mc.screen == null ? "null" : mc.screen.getClass().getSimpleName());
        TranscenMod.SHOW_DEATH_SCREEN = false;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCount++;

        Minecraft mc = Minecraft.getInstance();
        boolean remoteHud = AnnihilationClientState.isHudActive();
        boolean zombieVisible = mc.level != null && zombiePresent(mc.level);
        // A sighting starts the encounter and is then remembered. This is the "once it appears, it
        // stays" rule: the sighting is what turns it on, and nothing turns it off except leaving the
        // world.
        if (zombieVisible) {
            AnnihilationClientState.latchHud();
        }
        boolean active = (TranscenMod.HUD_ACTIVE
                || remoteHud
                || AnnihilationClientState.isHudLatched()
                || zombieVisible) && mc.level != null;

        // Protection off overrides everything above, including the visible-zombie term. Otherwise a
        // zombie still on screen for a moment after the switch restarts the music as soon as it is
        // stopped, which is a music that will not stay off.
        if (AnnihilationClientState.isProtectionOff()) {
            active = false;
        }

        keepDoomedShellsRed(mc);

        // The zombie's health is a fixed constant and setHealth is a no-op, so the displayed
        // fraction is always full; the bar's identity comes from its colour cycle, not from damage.
        BAR.setProgress(remoteHud ? clamp(AnnihilationClientState.hudProgress()) : 1.0F);
        BAR.setVisible(active);
        BAR.tick();
        TranscenMusic.tick(active);
    }

    /**
     * Forces doomed shells to render red using vanilla's own hurt-flash mechanism.
     *
     * <p>This is deliberately <b>not</b> {@code RenderSystem.setShaderColor}: vanilla does not tint
     * a hurt entity through the shader colour at all. {@code LivingEntityRenderer#getOverlayCoords}
     * packs the overlay texture coordinate from
     * {@code OverlayTexture.v(entity.hurtTime > 0 || entity.deathTime > 0)}, and a positive
     * {@code hurtTime} makes the model sample the red overlay texel. Re-setting {@code hurtTime}
     * every tick keeps that overlay latched for as long as the shell exists, which is exactly the
     * standing red the shell is supposed to show.
     *
     * <p>{@code hurtTime} is a public field, so no access widening is needed.
     */
    private static void keepDoomedShellsRed(Minecraft mc) {
        AnnihilationClientState.noteTick();
        // The actual latching happens in DoomedRenderer#onRenderLivingPre, because LivingEntity
        // decrements hurtTime every tick and the value has to be positive at render time.
    }

    private static float clamp(float v) {
        if (v < 0.0F) {
            return 0.0F;
        }
        return v > 1.0F ? 1.0F : v;
    }

    /**
     * Suppresses HUD drawing by other mods while the encounter is present, and only by other mods.
     *
     * <p>Vanilla, Forge and this mod are all passed through, decided by the overlay id's namespace
     * rather than by listing overlay names. A name list would have to be maintained against every
     * version, and anything missed would start silently eating the player's own interface; the namespace
     * test cannot go stale, because everything the game and Forge define lives under {@code minecraft}
     * and {@code forge}.
     *
     * <p><b>What this can and cannot reach.</b> Forge gives mods two routes onto the HUD. Overlays
     * registered through the overlay registry surface here, one event each, and are covered. Mods that
     * ignore the registry and hook the raw whole-HUD event are <i>not</i> covered, and this was worth
     * verifying rather than assuming: {@code Gui.render} contains no branch on the cancellation flag, so
     * cancelling that raw event skips nothing and suppresses nobody -- it only sets a flag the vanilla
     * code never consults. A cancelled event does not stop its listeners from having run.
     *
     * <p>An earlier revision did cancel the raw event, on the theory that it covered everything. It
     * covered nothing, and it is removed rather than left in place looking protective.
     *
     * <p>The per-overlay cancellation that used to live here is gone for a different reason: it was
     * unconditional, so it removed every overlay including the game's own HUD, which is precisely the
     * failure it was meant to prevent. Nothing replaces it. Suppression now happens one step earlier, by
     * removing foreign entries from the list Forge iterates (see the HUD overlay mixin), so the loop
     * never reaches them and never raises an event for them -- and vanilla's entries, being in the list,
     * draw exactly as they always did.
     */

    /**
     * True while the encounter is running on this client.
     *
     * <p>Uses the same three sources as the bar's own visibility: the local "egg was used" flag, the
     * server's HUD sync, and whether a zombie is currently visible. Checking only the entity would turn
     * the overlays back on during the two-second window after a removal, when the server copy is gone
     * but the encounter is plainly still happening.
     */
    private static boolean isEncounterPresent() {
        return HudSuppression.isEncounterPresent();
    }

    /**
     * Draws this mod's own bar.
     *
     * <p>Anchored to the whole-HUD event rather than to a per-overlay one. The per-overlay phase only
     * runs for overlays Forge knows about, so a bar drawn from there would be missing whenever the
     * overlay registry had a bad frame or a resource reload was in progress. The whole-HUD phase always
     * runs while a level is loaded, and this handler is independent of whether any overlay was
     * suppressed.
     */
    /**
     * Draws this mod's bar directly, for use when the whole HUD pass has been suppressed.
     *
     * <p>Needed because suppressing that pass also suppresses the event the bar normally draws from --
     * the event is raised inside the very method being cancelled. Without this the encounter would remove
     * every other mod's HUD output and its own along with them, which is the same "it kills the wrong
     * things" failure this feature has already been through once, only with this mod as the victim.
     *
     * <p>Public so the mixin's helper can reach it without a hard reference to a client-only class from
     * common code.
     */
    public static void renderOwnBarDirectly(PoseStack poseStack) {
        if (!BAR.isVisible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        BAR.render(poseStack, TITLE, mc.getWindow().getGuiScaledWidth(), 12);
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        if (!BAR.isVisible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PoseStack poseStack = event.getPoseStack();
        int width = mc.getWindow().getGuiScaledWidth();
        BAR.render(poseStack, TITLE, width, 12);
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        // Deliberately empty; the bar is drawn from onRenderGuiPost.
        //
        // Keeping both hooks would draw it twice, because nothing suppresses the overlay phase -- the
        // suppression added for other mods leaves vanilla and this mod's overlays alone, and there is no
        // whole-HUD cancellation any more.
    }

    /** Cycles the sky tint while the encounter is active. */
    @SubscribeEvent
    public static void onRenderSky(RenderLevelStageEvent event) {
        if (!BAR.isVisible() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        int colorIndex = (tickCount / 40) % 5;
        float[][] colors = {
                {1f, 0f, 0f}, {1f, 1f, 0f}, {0.5f, 0f, 0.5f}, {0f, 1f, 0f}, {0f, 1f, 1f}
        };
        float[] c = colors[colorIndex];
        RenderSystem.clearColor(c[0], c[1], c[2], 1.0f);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
    }

    /** Drops every scrap of per-world client state when the connection goes away. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AnnihilationClientState.reset();
        TranscenMod.HUD_ACTIVE = false;
        BAR.setVisible(false);
        TranscenMusic.stop();
    }

    static boolean zombiePresent(ClientLevel level) {
        for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
            if (entity instanceof TranscenZombie) {
                return true;
            }
        }
        return false;
    }
}
