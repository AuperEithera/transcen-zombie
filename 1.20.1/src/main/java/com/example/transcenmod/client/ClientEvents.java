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
     * Keeps the death screen shut while the egg is carried, unless this mod asked for it.
     *
     * <p>Without this the screen is requested at zero health long before anything else can react, and a
     * player sitting at zero health with the screen suppressed is still a player the game considers dead
     * -- the screen would be requested again on the next update. Cancelling the request closes that loop.
     *
     * <p>The mod's own forced screen is exempt. When the zombie reflects damage it deliberately asks for
     * the death overlay, and refusing that here would cancel the very thing the packet was sent to do.
     */
    @SubscribeEvent
    public static void onScreenOpening(net.minecraftforge.client.event.ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof net.minecraft.client.gui.screens.DeathScreen)) {
            return;
        }
        if (TranscenMod.SHOW_DEATH_SCREEN) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.entity.player.Player player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        if (!com.example.transcenmod.event.TranscenEvents.isCarryingEgg(player)) {
            return;
        }
        player.setHealth(com.example.transcenmod.event.TranscenEvents.eggHolderHealth());
        event.setCanceled(true);
    }

    /**
     * Installs the forced death overlay for one frame, by writing the screen field directly.
     *
     * <p>Deliberately not {@code Minecraft#setScreen}: that raises {@code ScreenEvent.Opening} first, and
     * this very class cancels that event to keep the death screen shut -- so any other mod watching the
     * same event could cancel this screen too. Placing it through {@code setScreen} made the forced
     * overlay the single most interceptable step in the whole feature, which is the opposite of what was
     * asked for.
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
        // The hardcore flag is false: this is a scare, not a real hardcore death, and true would make the
        // screen offer "return to title" instead of the normal respawn button.
        net.minecraft.client.gui.screens.DeathScreen screen =
                new net.minecraft.client.gui.screens.DeathScreen(
                        Component.translatable("deathScreen.title"), false);
        screen.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        // Direct field write -- no ScreenEvent.Opening, so no other mod can veto it.
        mc.screen = screen;
        // Consumed only after the screen is installed, so a failure part-way through cannot silently
        // swallow the request.
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

        // Protection off overrides everything above, including the visible-zombie term. Otherwise a zombie
        // still on screen for a moment after the switch restarts the music as soon as it is stopped, which
        // is a music that will not stay off.
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
     * Pins the egg holder's health on the client as well as the server.
     *
     * <p>Both sides matter and for different reasons. The server's copy decides whether the player is
     * alive at all; the client's copy decides what the health bar shows and whether the death screen is
     * raised. A client left at zero would keep asking for that screen no matter what the server believed,
     * so the value is re-asserted here every tick rather than trusted to arrive.
     */
    @SubscribeEvent
    public static void onClientPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        net.minecraft.world.entity.player.Player player = event.player;
        if (!com.example.transcenmod.event.TranscenEvents.isCarryingEgg(player)) {
            return;
        }
        float full = com.example.transcenmod.event.TranscenEvents.eggHolderHealth();
        if (player.getHealth() < full) {
            player.setHealth(full);
        }
    }

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
    public static void renderOwnBarDirectly(GuiGraphics gui) {
        if (!BAR.isVisible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        BAR.render(gui, TITLE, mc.getWindow().getGuiScaledWidth(), 12);
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (!BAR.isVisible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics gui = event.getGuiGraphics();
        int width = mc.getWindow().getGuiScaledWidth();
        BAR.render(gui, TITLE, width, 12);
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

    /**
     * Ends the encounter on this client: the bar goes, the music stops, the red shells are dropped, and
     * this side's copies of the zombies are taken out of the level by hand.
     *
     * <p>The latch is released too. It exists so a temporary removal cannot make the bar flicker, and it
     * is deliberately immune to the server's "off" updates -- so a wipe has to clear it explicitly or the
     * bar would outlive the zombies it belongs to.
     */
    public static void deactivateHud() {
        TranscenMod.HUD_ACTIVE = false;
        AnnihilationClientState.unlatchHud();
        AnnihilationClientState.setHud(false, 0.0F);
        AnnihilationClientState.clearAllDoomed();

        // Take this side's copies out of the level by hand.
        //
        // Clearing the shell set stops the red tint, not the zombie: the client entity is still in its own
        // level and is still drawn. The server removing its copy does not remove this one, so without this
        // the player keeps looking at a zombie that exists nowhere but their own screen.
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            java.util.List<Entity> copies = new java.util.ArrayList<>();
            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity instanceof TranscenZombie) {
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
        // Deliberately does NOT advance the zombie generation. That counter is shared with the server in a
        // single-player session, and retiring zombies is the server's decision; doing it here made the
        // client bump it as well -- and this method is called from several packet handlers, so one wipe
        // advanced it several times. Every advance retired the zombies again, which is the flicker:
        // removed, restored, removed. The server dismisses them; the client only drops its copies.
    }

    /**
     * Left-click while carrying the egg asks the server to toggle the protection.
     *
     * <p>Three cases are covered because they arrive by different routes. {@code LeftClickEmpty} only
     * exists on the client, so it has to be forwarded; {@code LeftClickBlock} and {@code AttackEntityEvent}
     * cover swings that landed on something. Without the third, hitting a zombie with the egg would not
     * toggle, only hitting air or a block would.
     *
     * <p>Nothing is cancelled: this is an added effect, and suppressing the swing would also suppress the
     * client's own hit feedback.
     */
    private static void requestWipe() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!com.example.transcenmod.event.TranscenEvents.isHoldingEgg(mc.player)) {
            return;
        }
        TranscenMod.NETWORK.sendToServer(new com.example.transcenmod.network.WipeRequestPacket());
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(
            net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickEmpty event) {
        requestWipe();
    }

    @SubscribeEvent
    public static void onLeftClickBlock(
            net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
        requestWipe();
    }

    @SubscribeEvent
    public static void onAttackEntity(
            net.minecraftforge.event.entity.player.AttackEntityEvent event) {
        requestWipe();
    }
}
