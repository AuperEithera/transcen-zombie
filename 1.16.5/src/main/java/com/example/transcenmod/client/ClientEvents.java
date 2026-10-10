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

// client-only presentation -bar,screen,ambience loop
// visibility is server driven via HudSyncPacket since the bar must be right even when the rendering
// view lacks the zombie; the egg also arms it locally,one source is enough
// loads only from FMLClientSetupEvent so a dedicated server never sees it
public class ClientEvents {

    private static final TranscenBossBar BAR = new TranscenBossBar();
    private static final ITextComponent TITLE =
            new StringTextComponent("TRANSCENDENCE ZOMBIE")
                    .mergeStyle(net.minecraft.util.text.TextFormatting.BOLD,
                            net.minecraft.util.text.TextFormatting.ITALIC);

    private static int tickCount = 0;

    static {
        // the packet handler reaches the client half through this hook,so common-side code never has to
        // name a client-only class
        ActivateHudPacket.setClientHook(ClientEvents::activateHud);
        com.example.transcenmod.network.WipeEndedPacket.setClientHook(ClientEvents::deactivateHud);
        com.example.transcenmod.network.WipeRequestPacket.setClientHook(ClientEvents::deactivateHud);
    }

    public static void activateHud() {
        // egg used again,so lift the protection override and let the bar and music run
        AnnihilationClientState.setProtectionOff(false);
        TranscenMod.HUD_ACTIVE = true;
        // latch too,not just switch on - a later "no zombie" update must not switch it off or the bar
        // and overlay strobe whenever something removes the zombie and the repair puts it back
        AnnihilationClientState.latchHud();
    }

    // ends the encounter on this client -bar,music,latch
    // the latch is the point: it exists so a temporary removal cannot flicker the bar and is immune to
    // the server's "off" updates,so a wipe has to clear it by hand or the bar outlives its zombies
    public static void deactivateHud() {
        TranscenMod.HUD_ACTIVE = false;
        AnnihilationClientState.unlatchHud();
        AnnihilationClientState.setHud(false, 0.0F);
        // drop the shells so no red remnant is left over an entity the server already discarded
        AnnihilationClientState.clearAllDoomed();

        // and remove this side's copies by hand -
        // clearing the shell set stops the red tint but not the zombie: the client entity still sits in
        // its own level and is still drawn,which is exactly how the rendering outlived the protection
        Minecraft client = Minecraft.getInstance();
        if (client.world != null) {
            java.util.List<Entity> copies = new java.util.ArrayList<>();
            for (Entity entity : client.world.getAllEntities()) {
                if (entity instanceof com.example.transcenmod.entity.TranscenZombie) {
                    copies.add(entity);
                }
            }
            for (Entity entity : copies) {
                try {
                    entity.remove(false);
                } catch (Throwable failure) {
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

    // left click while carrying the egg asks the server to wipe -
    // three routes because they are three different events: LeftClickEmpty exists only on the client so
    // it must be forwarded, LeftClickBlock and AttackEntityEvent cover swings that landed on something
    // without the third,hitting a zombie with the egg would not wipe while hitting air or a block did
    // nothing is cancelled: this is an added effect and suppressing the swing would suppress hit feedback
    private static void requestWipe() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null) {
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

    // refuses to open the death screen while the player holds this mod's egg -
    // the right entry point because the death screen is a screen: only the opening path can decline it
    // server-side guards cannot cover this,health can move without damage at all -a direct setHealth(0)
    // or a command raises no damage or death event,so anything hooked there is never consulted
    // cancel rather than swap in another screen -the swap is still "a screen nobody asked for", and
    // cancelling leaves whatever is already open untouched,which is what "the gui never opened" means
    // health is restored in the same breath: the server can zero the client long before the screen is
    // requested, and a player pinned at zero is still dead to the game so it asks again every update
    // the mod's own forced screen is exempt,or refusing it would cancel what the packet was sent to do
    @SubscribeEvent
    public static void onScreenOpening(GuiScreenEvent.InitGuiEvent event) {
        if (!(event.getGui() instanceof DeathScreen)) {
            return;
        }
        if (TranscenMod.SHOW_DEATH_SCREEN) {
            // tells the two "screen did not appear" cases apart -forced screen let through, or the forced
            // screen never requested at all
            TranscenMod.LOGGER.info("[transcenmod] death screen let through (forced by the mod)");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            return;
        }
        if (!TranscenEvents.isCarryingEgg(player)) {
            // prints both hands rather than just "check failed" - "not holding" has two very different
            // causes,the item truly is not there or the lookup is on the wrong player object,and the
            // stacks separate them in one run instead of another round of guessing
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

    // half of the "hold the egg and cannot die" guarantee -
    // the server pins the authoritative value,this half matters because the death screen is a client
    // reaction: a client told it sits at zero health asks for that screen on every sync, and suppressing
    // each request leaves it permanently believing it is dead
    // holding the local value off zero removes the disagreement at the source instead of arguing per tick
    @SubscribeEvent
    public static void onClientPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        PlayerEntity player = event.player;
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

    // draws the forced death overlay for one frame -
    // the port of the 1.12.2 render-tick hook that did new GuiGameOver(null).drawScreen(0,0,0)
    // it deliberately does NOT use the obvious modern equivalent Minecraft#setScreen: that raises
    // GuiScreenEvent.InitGuiEvent first, the very event this class cancels to keep the screen shut, so
    // any other mod watching it could veto the screen too -setScreen would make the forced overlay the
    // most interceptable step in the feature
    // Minecraft.screen is a plain public non-final field in 1.16.5 (SRG f_91080_) so it can simply be
    // assigned; forceSetScreen is no alternative,its bytecode calls setScreen internally
    // giving up the event costs nothing else -no vanilla screen depends on it to work; resize is called
    // by hand because setScreen would have been the thing to do it
    // the flag is consumed only after the screen is installed so a mid-way failure cannot swallow it
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !TranscenMod.SHOW_DEATH_SCREEN) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // hardcore flag false -this is a scare,not a real hardcore death; true would swap the respawn
        // button for "return to title"
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
        if (mc.currentScreen != screen) {
            mc.displayGuiScreen(screen);
        }
        TranscenMod.LOGGER.info(
                "[transcenmod] forced death screen installed (now {})",
                mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName());
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
        boolean zombieVisible = mc.world != null && zombiePresent(mc.world);
        // a sighting starts the encounter and is then remembered -the "once it appears,it stays" rule;
        // only leaving the world turns it off
        if (zombieVisible) {
            AnnihilationClientState.latchHud();
        }
        boolean active = (TranscenMod.HUD_ACTIVE
                || remoteHud
                || AnnihilationClientState.isHudLatched()
                || zombieVisible) && mc.world != null;

        // protection off overrides everything above,visible-zombie term included,or a zombie still on
        // screen for a moment after the switch restarts the music as soon as it is stopped
        if (AnnihilationClientState.isProtectionOff()) {
            active = false;
        }

        keepDoomedShellsRed(mc);

        // the zombie's health is a fixed constant and setHealth is a no-op,so the displayed fraction is
        // always full -the bar's identity comes from its colour cycle,not from damage
        BAR.setProgress(remoteHud ? clamp(AnnihilationClientState.hudProgress()) : 1.0F);
        BAR.setVisible(active);
        BAR.tick();
        TranscenMusic.tick(active);
    }

    // forces doomed shells to render red through vanilla's own hurt-flash -
    // deliberately NOT RenderSystem.setShaderColor: vanilla never tints a hurt entity through the shader
    // colour; LivingEntityRenderer#getOverlayCoords packs the coord from
    // OverlayTexture.v(entity.hurtTime > 0 || entity.deathTime > 0) and a positive hurtTime makes the
    // model sample the red overlay texel,so re-setting hurtTime every tick latches that overlay for as
    // long as the shell exists
    // hurtTime is a public field,no access widening needed
    private static void keepDoomedShellsRed(Minecraft mc) {
        AnnihilationClientState.noteTick();
        // the latching itself lives in DoomedRenderer#onRenderLivingPre: LivingEntity decrements hurtTime
        // every tick and the value has to be positive at render time
    }

    private static float clamp(float v) {
        if (v < 0.0F) {
            return 0.0F;
        }
        return v > 1.0F ? 1.0F : v;
    }

    // vanilla,forge and this mod pass through,decided by the overlay id's namespace rather than by a name
    // list -a list would rot against every version and anything missed would silently eat the player's own
    // interface, while everything the game and forge define lives under minecraft and forge
    // this covers overlays registered through the overlay registry only; mods that hook the raw whole-HUD
    // event are not covered - Gui.render has no branch on the cancellation flag, so cancelling that raw
    // event skips nothing and a cancelled event does not stop its listeners from having run
    // an earlier revision cancelled the raw event on the theory that it covered everything,it covered
    // nothing and is removed rather than left looking protective
    // the per-overlay cancellation that used to sit here went the other way,it was unconditional and so
    // removed the game's own HUD; suppression now happens a step earlier by removing foreign entries from
    // the list forge iterates,so the loop never raises an event for them and vanilla's entries still draw

    // true while the encounter is running on this client -
    // same three sources as the bar: local "egg was used" flag, server HUD sync, visible zombie
    // checking only the entity would switch the overlays back on during the two second window after a
    // removal,when the server copy is gone but the encounter plainly is not
    private static boolean isEncounterPresent() {
        return HudSuppression.isEncounterPresent();
    }

    // draws the bar by hand for when the whole HUD pass has been suppressed -
    // the bar normally draws from an event raised inside the very method being cancelled, so without this
    // the encounter would remove every other mod's HUD output and its own along with them
    // public so the mixin's helper can reach it without a hard reference to a client-only class from
    // common code
    public static void renderOwnBarDirectly(MatrixStack poseStack) {
        if (!BAR.isVisible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        BAR.render(poseStack, TITLE, mc.getMainWindow().getScaledWidth(), 12);
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGameOverlayEvent.Post event) {
        if (!BAR.isVisible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MatrixStack poseStack = event.getMatrixStack();
        int width = mc.getMainWindow().getScaledWidth();
        BAR.render(poseStack, TITLE, width, 12);
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        // deliberately empty,the bar is drawn from onRenderGuiPost
        // keeping both hooks would draw it twice: nothing suppresses the overlay phase -the suppression
        // added for other mods leaves vanilla and this mod's overlays alone -and there is no whole-HUD
        // cancellation any more
    }

    // cycling atmosphere tint,one entry per colour step
    private static final float[][] TINT_COLORS = {
            {1f, 0f, 0f}, {1f, 1f, 0f}, {0.5f, 0f, 0.5f}, {0f, 1f, 0f}, {0f, 1f, 1f}
    };

    // cycles the atmosphere tint while the encounter is active -
    // older generations cleared the colour buffer at the "after sky" render stage,which made the sky a
    // solid colour while terrain survived on the depth buffer
    // 1.16.5 has no such stage: RenderWorldLastEvent fires once the whole world is drawn,so the same clear
    // would wipe the framebuffer and paint the entire screen; the fog-colour hook is the non destructive
    // equivalent, it recolours the sky and horizon blend without touching what is already rendered
    @SubscribeEvent
    public static void onFogColors(net.minecraftforge.client.event.EntityViewRenderEvent.FogColors event) {
        if (!BAR.isVisible()) {
            return;
        }
        float[] c = TINT_COLORS[(tickCount / 40) % TINT_COLORS.length];
        event.setRed(c[0]);
        event.setGreen(c[1]);
        event.setBlue(c[2]);
    }

    // drops every scrap of per-world client state when the connection goes away
    @SubscribeEvent
    public static void onLoggedOutEvent(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        AnnihilationClientState.reset();
        TranscenMod.HUD_ACTIVE = false;
        BAR.setVisible(false);
        TranscenMusic.stop();
    }

    static boolean zombiePresent(ClientWorld level) {
        for (net.minecraft.entity.Entity entity : level.getAllEntities()) {
            if (entity instanceof TranscenZombie) {
                return true;
            }
        }
        return false;
    }
}
