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

// bar, screen rendering, background loop
// visibility is server driven through HudSyncPacket - the bar has to be right even when the zombie
// is outside the rendering view,right clicking the egg arms it locally via ActivateHudPacket
// no AccessTransformer widening here, the package sits in public Forge API
// registered from FMLClientSetupEvent so it never loads on a dedicated server
public class ClientEvents {

    private static final TranscenBossBar BAR = new TranscenBossBar();
    private static final Component TITLE =
            Component.literal("TRANSCENDENCE ZOMBIE")
                    .withStyle(style -> style.withBold(true).withItalic(true));

    private static int tickCount = 0;

    static {
        // packet handlers reach the client half through this hook,so common code never names a
        // client-only class
        ActivateHudPacket.setClientHook(ClientEvents::activateHud);
        com.example.transcenmod.network.WipeEndedPacket.setClientHook(ClientEvents::deactivateHud);
        com.example.transcenmod.network.WipeRequestPacket.setClientHook(ClientEvents::deactivateHud);
    }

    public static void activateHud() {
        // right click switched protection back on,so the override is lifted and bar and music may run
        AnnihilationClientState.setProtectionOff(false);
        TranscenMod.HUD_ACTIVE = true;
        // latch as well as switch on: the server's later "no zombie" updates must not turn the
        // encounter off, or the bar strobes every time a removal is followed by the repair
        AnnihilationClientState.latchHud();
    }

    // ends the encounter here - latch released too, it is immune to the server's "off" updates, so
    // a wipe has to clear it explicitly or the bar outlives the zombies it belongs to
    public static void deactivateHud() {
        TranscenMod.HUD_ACTIVE = false;
        AnnihilationClientState.unlatchHud();
        AnnihilationClientState.setHud(false, 0.0F);
        // drop the shells or a red remnant stays over an entity the server already discarded
        AnnihilationClientState.clearAllDoomed();

        // and take this side's copies out of the level by hand
        // clearing the shell set stops the tint, not the zombie - the client entity is still in its
        // own level and still drawn, which is why the render survived with protection off while the
        // server copy was gone. CustomEntityRemover has a client branch for exactly this
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
        // deliberately does NOT advance the zombie generation: that counter is static and shared
        // with the server, and this method runs from three packet handlers, so one wipe bumped it
        // several times - removed, restored, removed - the flicker. the server retires zombies
    }

    // left click with the egg asks the server to wipe
    // LeftClickEmpty exists only on the client so it has to be forwarded, LeftClickBlock and
    // AttackEntityEvent cover swings that landed on something - without the third only air wipes
    // nothing is cancelled: suppressing the swing would also suppress the hit feedback
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

    // refuses to open the death screen while the player holds the egg
    // the screen-opening path is the only place that can decide not to show it. server side guards
    // cannot cover this - a direct setHealth(0) or a command raises no damage or death event at all
    // cancelled rather than swapping in another screen: cancel leaves whatever is open untouched
    // health restored in the same breath, a client left at zero keeps asking for the screen on every
    // update and suppressing each request just leaves it believing it is dead
    // SHOW_DEATH_SCREEN is exempt, refusing it would cancel what the packet was sent to do
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof DeathScreen)) {
            return;
        }
        if (TranscenMod.SHOW_DEATH_SCREEN) {
            // tells the two no-screen cases apart: forced screen let through, or never requested
            TranscenMod.LOGGER.info("[transcenmod] death screen let through (forced by the mod)");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        if (!TranscenEvents.isCarryingEgg(player)) {
            // prints both hand stacks: "not holding" is either the item genuinely absent or the
            // lookup consulting the wrong player object, and the stacks separate them in one run
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

    // client half of "hold the egg and cannot die". the death screen is a client side reaction, so
    // a client told it is at zero health asks for that screen on every sync - keeping the local
    // value off zero removes the disagreement instead of arguing with it once per update
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

    // port of the 1.12.2 render hook that did new GuiGameOver(null).drawScreen(0,0,0)
    // NOT setScreen: it raises ScreenEvent.Opening first, and this class cancels that event, so any
    // other mod listening could veto the forced overlay - the one step that must not be vetoable
    // Minecraft.screen is a plain public non final field in this version (SRG f_91080_), assigning
    // it skips setScreen and the event with it. forceSetScreen is no alternative, its bytecode
    // calls setScreen internally and raises the same event. resize is explicit because setScreen
    // would otherwise have sized the screen
    // SHOW_DEATH_SCREEN cleared only after the screen is installed, a failure cannot swallow it
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !TranscenMod.SHOW_DEATH_SCREEN) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // hardcore false - this is a scare, true would offer "return to title" instead of respawn
        DeathScreen screen = new DeathScreen(Component.translatable("deathScreen.title"), false);
        screen.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        // direct field write, no ScreenEvent.Opening so no mod can veto it
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
        // a sighting latches the encounter until the world is left: once it appears it stays
        if (zombieVisible) {
            AnnihilationClientState.latchHud();
        }
        boolean active = (TranscenMod.HUD_ACTIVE
                || remoteHud
                || AnnihilationClientState.isHudLatched()
                || zombieVisible) && mc.level != null;

        // protection off overrides everything above, the visible zombie term included, or a zombie
        // on screen for a moment after the switch restarts the music the instant it stops
        if (AnnihilationClientState.isProtectionOff()) {
            active = false;
        }

        keepDoomedShellsRed(mc);

        // health is a fixed constant and setHealth is a no-op so the fraction is always full -
        // the bar's identity is the colour cycle, not damage
        BAR.setProgress(remoteHud ? clamp(AnnihilationClientState.hudProgress()) : 1.0F);
        BAR.setVisible(active);
        BAR.tick();
        TranscenMusic.tick(active);
    }

    // the red is vanilla's own hurt flash, not RenderSystem.setShaderColor - vanilla never tints a
    // hurt entity through the shader colour. getOverlayCoords packs a coordinate from
    // OverlayTexture.v(hurtTime > 0), and a positive hurtTime makes the model sample the red texel
    // hurtTime is public, no access widening needed
    private static void keepDoomedShellsRed(Minecraft mc) {
        AnnihilationClientState.noteTick();
        // the latching itself lives in DoomedRenderer#onRenderLivingPre - LivingEntity decrements
        // hurtTime each tick, so the value has to be positive at render time
    }

    private static float clamp(float v) {
        if (v < 0.0F) {
            return 0.0F;
        }
        return v > 1.0F ? 1.0F : v;
    }

    // hides other mods' overlays while the encounter is present, and only other mods'
    // the namespace test replaces a name list: minecraft and forge pass, everything else does not,
    // and a list would go stale every version and silently eat the player's own interface
    // registry overlays surface here one event each and are covered. mods that hook the raw
    // whole-HUD event are not: Gui.render has no branch on the cancellation flag, so cancelling it
    // skips nothing and suppresses nobody - a cancelled event still ran its listeners
    // the old per-overlay cancellation here was unconditional and removed the game's own HUD, so it
    // is gone. suppression now drops foreign entries from the list Forge iterates, one step earlier

    // true while the encounter runs here - same three sources as the bar: local flag, server sync,
    // visible zombie. the entity alone would turn overlays back on in the two seconds after a
    // removal, when the server copy is gone but the encounter is plainly still happening
    private static boolean isEncounterPresent() {
        return HudSuppression.isEncounterPresent();
    }

    // bar drawn from the whole-HUD event, not a per-overlay one: that phase only runs for overlays
    // Forge knows about, so it would vanish on a bad frame or during a resource reload
    // direct draw path for when the whole HUD pass is suppressed - suppressing it also suppresses
    // the event the bar normally uses, that event is raised inside the cancelled method. without
    // this the encounter would kill every mod's HUD output and its own with them. public so the
    // mixin helper can reach it without a hard reference to a client-only class from common code
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
        // deliberately empty,the bar draws from onRenderGuiPost and keeping both hooks draws it
        // twice - nothing suppresses the overlay phase any more
    }

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
