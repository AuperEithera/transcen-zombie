package com.example.transcenmod;

import com.example.transcenmod.client.TranscenLightningRenderer;
import com.example.transcenmod.client.TranscenZombieRenderer;
import com.example.transcenmod.entity.TranscenLightningBolt;
import com.example.transcenmod.entity.TranscenZombie;
import com.example.transcenmod.event.TranscenEvents;
import com.example.transcenmod.item.SpawnTranscenZombieItem;
import com.example.transcenmod.network.ActivateHudPacket;
import com.example.transcenmod.network.ClientRemovePacket;
import com.example.transcenmod.network.DeathScreenPacket;
import com.example.transcenmod.network.WipeRequestPacket;
import com.example.transcenmod.network.WipeEndedPacket;
import com.example.transcenmod.network.HudSyncPacket;
import com.example.transcenmod.network.MarkDoomedPacket;
import com.example.transcenmod.util.ReflectionAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.IEventListener;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transcendence Zombie.
 *
 * <p>This mod deliberately reaches into Minecraft's internals: it removes entities from the
 * client/server entity storages by hand and walks the entity section maps directly.
 *
 * <p>What AccessTransformer does here is widen every member that this mod touches -- by reflection
 * <em>or</em> by direct access -- from {@code private}/{@code package-private}/{@code protected} to
 * {@code public} at class-load time. See {@code src/main/resources/META-INF/accesstransformer.cfg}.
 * Making them public has two effects:
 * <ol>
 *   <li>the reflective lookups keep working in both dev and production, because the widened
 *       modifier survives reobfuscation (the AT is re-applied at runtime by Forge);</li>
 *       members through plain Java field/method access, which is the compile-time proof that the
 *       AT file actually targets the right SRG members.</li>
 * </ol>
 */
@Mod(TranscenMod.MODID)
public class TranscenMod {

    public static final String MODID = "transcenmod";

    /** Shared logger, so every subsystem reports through one place. */
    public static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    /** Tracking range has to cover the 1024-block follow range, so the type asks for the maximum. */
    public static final RegistryObject<EntityType<TranscenZombie>> TRANSCEN_ZOMBIE = ENTITIES.register(
            "transcenzombie",
            () -> EntityType.Builder.of(TranscenZombie::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(32)
                    .build("transcenzombie"));

    public static final RegistryObject<EntityType<TranscenLightningBolt>> TRANSCEN_LIGHTNING = ENTITIES.register(
            "transcen_lightning_bolt",
            () -> EntityType.Builder.of(TranscenLightningBolt::new, MobCategory.MISC)
                    .sized(0.0F, 0.0F)
                    .clientTrackingRange(32)
                    .build("transcen_lightning_bolt"));

    /** The spawn egg. Right-clicking it spawns the zombie and arms the HUD. */
    public static final RegistryObject<Item> TRANSCEN_SPAWN_EGG = ITEMS.register(
            "transcen_zombie_spawn_egg",
            () -> new SpawnTranscenZombieItem(new Item.Properties(), 0x8B2BE2, 0xE22B7A));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.transcenmod"))
                    .icon(() -> new ItemStack(TRANSCEN_SPAWN_EGG.get()))
                    .displayItems((params, output) -> output.accept(TRANSCEN_SPAWN_EGG.get()))
                    .build());

    /**
     * Whether this mod's protection systems are running. Left-clicking with the egg toggles it.
     *
     * <p><b>What it covers, and what it must never cover.</b> It covers the systems around the zombie:
     * the revival that puts a removed zombie back, the periodic sweep, and the bar, the overlay and the
     * music. While it is false those stop, which is what lets a wipe stay wiped.
     *
     * <p>It deliberately does <b>not</b> reach the entity's own defences. setRemoved, remove, hurt,
     * setHealth and the position guard are the zombie's core and stay in force whatever this says.
     * Switching the protection off must not turn the creature into an ordinary mob; it only stops this
     * mod from working to keep it present.
     */
    public static volatile boolean defenceEnabled = true;

    /**
     * Entity classes the encounter has annihilated, which may not re-enter the world.
     *
     * <p>Annihilation removes what is present; this is what stops the same creature walking straight back
     * in a tick later and being removed again, forever. The ban is deliberately permanent for the session
     * and deliberately covers the whole class -- the encounter is not meant to be survivable by ordinary
     * means.
     */
    public static final Set<Class<?>> BANNED_CLASSES = ConcurrentHashMap.newKeySet();

    /** Where each zombie was last seen, so a removed zombie can be put back. */
    public static final Map<UUID, Vec3> ZOMBIE_POSITIONS = new ConcurrentHashMap<>();

    /**
     * Facing of each zombie when it was last seen.
     *
     * <p>Kept next to the position because a revived zombie should come back as the entity it was, and
     * an entity that reappears facing due south is visibly a different one. Stored separately rather
     * than folded into {@code ZOMBIE_POSITIONS} so the position map keeps its plain
     * {@code UUID -> Vec3} shape that the rest of the mod reasons about.
     */
    public static final Map<UUID, Float> ZOMBIE_YAW = new ConcurrentHashMap<>();
    public static final Map<UUID, Float> ZOMBIE_PITCH = new ConcurrentHashMap<>();
    /**
     * When each doomed entity's two-second display window started, keyed by UUID. The value is the
     * level game time at which it was detected, so the countdown is derived rather than ticked.
     */
    public static final Map<UUID, Long> ANNIHILATION_DOOMED_ON = new ConcurrentHashMap<>();

    /** Last known position of every entity being annihilated, for the strikes and the death puff. */
    public static final Map<UUID, Vec3> ANNIHILATION_POSITIONS = new ConcurrentHashMap<>();

    /** Ten ticks of strikes, then the client-side removal. */
    public static final int CLIENT_REMOVE_DELAY_TICKS = 10;

    /**
     * Looping background track played while the Transcendence HUD is up.
     *
     * <p>A plain {@link SoundEvent} rather than a registered one: it only ever plays on the client,
     * so it does not need to exist in the registry, and {@code SoundManager} resolves it straight
     * from {@code assets/transcenmod/sounds.json}.
     *
     * <p>The audio file itself is not shipped -- see README "Background music" for where to drop the
     * {@code .ogg}.
     */
    public static final SoundEvent TRANSCEN_MUSIC =
            SoundEvent.createVariableRangeEvent(new ResourceLocation(MODID + ":music.transcendence"));

    /** Set on the client when the HUD has been armed by right-clicking the spawn egg. */
    public static boolean HUD_ACTIVE = false;
    public static boolean SHOW_DEATH_SCREEN = false;

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel NETWORK = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID + ":main"),
            () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);

    public TranscenMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus);
        ITEMS.register(bus);
        TABS.register(bus);
        bus.addListener(this::setup);
        bus.addListener(this::clientSetup);
        bus.addListener(this::registerRenderers);
        bus.addListener(this::registerAttributes);
        MinecraftForge.EVENT_BUS.register(TranscenEvents.class);
    }

    private void setup(final FMLCommonSetupEvent event) {
        NETWORK.registerMessage(0, DeathScreenPacket.class, DeathScreenPacket::encode,
                DeathScreenPacket::new, DeathScreenPacket::handle);
        NETWORK.registerMessage(1, ClientRemovePacket.class, ClientRemovePacket::encode,
                ClientRemovePacket::new, ClientRemovePacket::handle);
        NETWORK.registerMessage(2, ActivateHudPacket.class, ActivateHudPacket::encode,
                ActivateHudPacket::new, ActivateHudPacket::handle);
        NETWORK.registerMessage(3, MarkDoomedPacket.class, MarkDoomedPacket::encode,
                MarkDoomedPacket::new, MarkDoomedPacket::handle);
        NETWORK.registerMessage(4, HudSyncPacket.class, HudSyncPacket::encode,
                HudSyncPacket::new, HudSyncPacket::handle);
        // Left-click with the egg: toggles the protection.
        NETWORK.registerMessage(5, WipeRequestPacket.class, WipeRequestPacket::encode,
                WipeRequestPacket::new, WipeRequestPacket::handle);
        // Broadcast: the encounter is over, drop the latch and the local copies.
        NETWORK.registerMessage(6, WipeEndedPacket.class, WipeEndedPacket::encode,
                WipeEndedPacket::new, WipeEndedPacket::handle);

    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // Registered reflectively by class name: this file is loaded on dedicated servers too, and a
        // direct reference to a client-only class in its constant pool would break that. The client
        // classes register their own packet hooks in static initialisers.
        MinecraftForge.EVENT_BUS.register(com.example.transcenmod.client.ClientEvents.class);
        MinecraftForge.EVENT_BUS.register(com.example.transcenmod.client.DoomedRenderer.class);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(TRANSCEN_ZOMBIE.get(), TranscenZombieRenderer::new);
        event.registerEntityRenderer(TRANSCEN_LIGHTNING.get(), TranscenLightningRenderer::new);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        // The stat block lives in TranscenZombie#createAttributes so there is exactly one source of
        // truth for it.
        event.put(TRANSCEN_ZOMBIE.get(), TranscenZombie.createAttributes().build());
    }


}
