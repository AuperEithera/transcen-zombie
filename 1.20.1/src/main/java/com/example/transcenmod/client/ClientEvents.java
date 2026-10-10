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

// bar,screen and music for the encounter - client side only
// visibility is server driven through HudSyncPacket: the bar must stay right even when the zombie is
// outside the client render view. the spawn egg also arms it locally via ActivateHudPacket,either source is enough
// no access transformer needed, this package sits in public forge api and loads from FMLClientSetupEvent
public class ClientEvents {

    private static final TranscenBossBar BAR = new TranscenBossBar();
    private static final Component TITLE =
            Component.literal("TRANSCENDENCE ZOMBIE")
                    .withStyle(style -> style.withBold(true).withItalic(true));

    private static int tickCount = 0;

    static {
        // hook for the packet handlers so the common-side code never names a client-only class
        ActivateHudPacket.setClientHook(ClientEvents::activateHud);
        com.example.transcenmod.network.WipeEndedPacket.setClientHook(ClientEvents::deactivateHud);
        com.example.transcenmod.network.WipeRequestPacket.setClientHook(ClientEvents::deactivateHud);
    }

    public static void activateHud() {
        // right click put protection back on: lift the override so bar and music may run again
        AnnihilationClientState.setProtectionOff(false);
        TranscenMod.HUD_ACTIVE = true;
        // latch as well as switch on. the server's later "no zombie" updates must not turn the encounter
        // off, or the bar and overlay strobe whenever something removes the zombie and the repair restores it
        AnnihilationClientState.latchHud();
    }

    // keeps the death screen shut while the egg is carried, unless this mod asked for it
    // without it the screen is requested at zero health before anything else can react, and a player
    // sitting at zero with the screen suppressed still counts as dead - cancelling the request breaks that loop
    // the mod's own forced screen is exempt, refusing it would cancel the very thing the packet was sent to do
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

    // installs the forced death overlay for one frame by writing the screen field directly
    // not Minecraft#setScreen: that fires ScreenEvent.Opening first, and this very class cancels that
    // event to keep the death screen shut, so any other mod watching it could cancel this screen too -
    // setScreen made the forced overlay the most interceptable step in the feature,a direct field write is not
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !TranscenMod.SHOW_DEATH_SCREEN) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // hardcore false: this is a scare,true would offer "return to title" instead of the respawn button
        net.minecraft.client.gui.screens.DeathScreen screen =
                new net.minecraft.client.gui.screens.DeathScreen(
                        Component.translatable("deathScreen.title"), false);
        screen.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        // direct field write so no ScreenEvent.Opening, nothing gets to veto it
        mc.screen = screen;
        // consumed only after the screen is installed, a failure part-way cannot silently swallow the request
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
        // a sighting starts the encounter and is then remembered - "once it appears it stays": the sighting
        // turns it on and nothing turns it off except leaving the world
        if (zombieVisible) {
            AnnihilationClientState.latchHud();
        }
        boolean active = (TranscenMod.HUD_ACTIVE
                || remoteHud
                || AnnihilationClientState.isHudLatched()
                || zombieVisible) && mc.level != null;

        // protection off beats everything above, the visible-zombie term included, else a zombie still on
        // screen a moment after the switch restarts the music the instant it is stopped
        if (AnnihilationClientState.isProtectionOff()) {
            active = false;
        }

        keepDoomedShellsRed(mc);

        // zombie health is a fixed constant and setHealth is a no-op so the fraction always reads full,
        // the bar's identity comes from its colour cycle not from damage
        BAR.setProgress(remoteHud ? clamp(AnnihilationClientState.hudProgress()) : 1.0F);
        BAR.setVisible(active);
        BAR.tick();
        TranscenMusic.tick(active);
    }

    // forces doomed shells red through vanilla's own hurt-flash, deliberately not RenderSystem.setShaderColor --
    // vanilla never tints a hurt entity via shader colour. LivingEntityRenderer#getOverlayCoords packs the
    // overlay uv from OverlayTexture.v(hurtTime > 0 || deathTime > 0) and a positive hurtTime makes the model
    // sample the red texel. re-setting hurtTime every tick holds that overlay for as long as the shell exists,
    // which is the standing red it is supposed to show. hurtTime is public,no access widening needed
    private static void keepDoomedShellsRed(Minecraft mc) {
        AnnihilationClientState.noteTick();
        // the real latching is in DoomedRenderer#onRenderLivingPre, because LivingEntity decrements hurtTime
        // every tick and the value has to be positive at render time
    }

    private static float clamp(float v) {
        if (v < 0.0F) {
            return 0.0F;
        }
        return v > 1.0F ? 1.0F : v;
    }

    // pins the egg holder's health on the client as well as the server, both sides matter for different reasons
    // the server's copy decides whether the player is alive, the client's decides what the health bar shows
    // and whether the death screen is raised - a client left at zero would keep asking for that screen no
    // matter what the server believed, so the value is re-asserted every tick rather than trusted to arrive
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

    // draws this mod's bar directly, for when the whole HUD pass has been suppressed
    // suppressing that pass also suppresses the event the bar normally draws from, it is raised inside the
    // very method being cancelled - without this the encounter would remove every other mod's HUD output
    // and its own with it. public so the mixin's helper reaches it without naming a client-only class
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

    // ends the encounter on this client: bar, music,red shells, and this side's copies of the zombies
    // are taken out of the level by hand
    // the latch is released too. it keeps a temporary removal from flickering the bar and is deliberately
    // immune to the server's "off" updates, so a wipe must clear it or the bar outlives the zombies it belongs to
    public static void deactivateHud() {
        TranscenMod.HUD_ACTIVE = false;
        AnnihilationClientState.unlatchHud();
        AnnihilationClientState.setHud(false, 0.0F);
        AnnihilationClientState.clearAllDoomed();

        // take this side's copies out of the level by hand
        // clearing the shell set stops the red tint, not the zombie: the client entity is still in its own
        // level and still drawn, and the server removing its copy does not remove this one - without this
        // the player keeps looking at a zombie that exists nowhere but their own screen
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
        // deliberately does NOT advance the zombie generation. that counter is shared with the server in a
        // single-player session and retiring zombies is the server's decision; bumping it here made the
        // client bump it as well, and several packet handlers call this so one wipe advanced it several
        // times - every advance retired the zombies again, which is the flicker: removed,restored,removed
    }

    // left click while carrying the egg asks the server to toggle the protection
    // three routes: LeftClickEmpty exists only on the client so it has to be forwarded, LeftClickBlock and
    // AttackEntityEvent cover swings that landed on something - without the third,hitting a zombie with the
    // egg would not toggle, only air or a block would
    // nothing is cancelled: this is an added effect, suppressing the swing would also suppress hit feedback
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
