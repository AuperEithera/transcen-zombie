package com.example.transcenmod;
import net.minecraftforge.fml.client.registry.RenderingRegistry;

import com.example.transcenmod.client.TranscenLightningRenderer;
import com.example.transcenmod.client.TranscenZombieRenderer;
import com.example.transcenmod.entity.TranscenLightningBolt;
import com.example.transcenmod.entity.TranscenZombie;
import com.example.transcenmod.event.TranscenEvents;
import com.example.transcenmod.item.SpawnTranscenZombieItem;
import com.example.transcenmod.network.ActivateHudPacket;
import com.example.transcenmod.network.ClientRemovePacket;
import com.example.transcenmod.network.DeathScreenPacket;
import com.example.transcenmod.network.HudSyncPacket;
import com.example.transcenmod.network.WipeEndedPacket;
import com.example.transcenmod.network.WipeRequestPacket;
import com.example.transcenmod.network.MarkDoomedPacket;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EntityClassification;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.IEventListener;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.RegistryObject;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transcendence ZombieEntity.
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
    public static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITIES, MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    /** Tracking range has to cover the 1024-block follow range, so the type asks for the maximum. */
    public static final RegistryObject<EntityType<TranscenZombie>> TRANSCEN_ZOMBIE = ENTITIES.register(
            "transcenzombie",
            () -> EntityType.Builder.create(TranscenZombie::new, EntityClassification.MONSTER)
                    .size(0.6F, 1.95F)
                    .trackingRange(32)
                    .build("transcenzombie"));

    public static final RegistryObject<EntityType<TranscenLightningBolt>> TRANSCEN_LIGHTNING = ENTITIES.register(
            "transcen_lightning_bolt",
            () -> EntityType.Builder.create(TranscenLightningBolt::new, EntityClassification.MONSTER)
                    // Deliberately not the usual 0x0. A degenerate box is the one structural difference
                    // from the zombie, which does reach the client, and it is what a bolt cannot afford
                    // while it is being tracked and culled.
                    // MONSTER, not MISC. Measured: a census on the client showed its own zombie
                    // (MONSTER) arriving every time and never a single bolt (MISC), through the same
                    // registration path. MISC is not serializable in this generation, which is the one
                    // remaining difference between the two types.
                    .size(0.6F, 1.8F)
                    .trackingRange(64)
                    .setUpdateInterval(1)
                    .setShouldReceiveVelocityUpdates(true)
                    // The load-bearing line. Vanilla lightning is a client-excluded entity: it exists on
                    // the server and the client is told about it out of band rather than being handed an
                    // entity. A modded subclass of it therefore never reaches the client through the
                    // ordinary spawn path, and it is this factory that gives Forge the world-side
                    // constructor to build the client instance with. Measured before it was added: the
                    // server reported the bolt added on every strike while the client never held one at
                    // all, and the renderer was never entered.
                                        .build("transcen_lightning_bolt"));

    /**
     * Late-bound accessor for the bolt type.
     *
     * <p>The client factory below has to name a type, but naming {@code TRANSCEN_LIGHTNING} inside that
     * field's own initialiser is a self-reference the compiler rejects outright. Routing it through a
     * static method defers the read to the moment a bolt is actually built, long after registration.
     */
    private static EntityType<TranscenLightningBolt> lightningType() {
        return TRANSCEN_LIGHTNING.get();
    }

    /** The spawn egg. Right-clicking it spawns the zombie and arms the HUD. */
    public static final RegistryObject<Item> TRANSCEN_SPAWN_EGG = ITEMS.register(
            "transcen_zombie_spawn_egg",
            () -> new SpawnTranscenZombieItem(new Item.Properties(), 0x8B2BE2, 0xE22B7A));

    /**
     * The mod's creative tab.
     *
     * <p>1.19.2 predates both {@code ItemGroup.builder()} and the {@code CREATIVE_MODE_TAB}
     * registry, which arrived with the 1.19.3/1.20 registry rework. Here the tabs are a plain mutable
     * static array on {@code ItemGroup} and are filled by the game itself, so a tab is created
     * as a subclass and appended to that array; see {@link #setup}. The field is only a holder so the
     * instance is reachable from the rest of the mod.
     */
    private static ItemGroup tab;

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
    public static final Map<UUID, Vector3d> ZOMBIE_POSITIONS = new ConcurrentHashMap<>();

    /**
     * Facing of each zombie when it was last seen.
     *
     * <p>Kept next to the position because a revived zombie should come back as the entity it was, and
     * an entity that reappears facing due south is visibly a different one. Stored separately rather
     * than folded into {@code ZOMBIE_POSITIONS} so the position map keeps its plain
     * {@code UUID -> Vector3d} shape that the rest of the mod reasons about.
     */
    public static final Map<UUID, Float> ZOMBIE_YAW = new ConcurrentHashMap<>();
    public static final Map<UUID, Float> ZOMBIE_PITCH = new ConcurrentHashMap<>();
    /**
     * When each doomed entity's two-second display window started, keyed by UUID. The value is the
     * level game time at which it was detected, so the countdown is derived rather than ticked.
     */
    public static final Map<UUID, Long> ANNIHILATION_DOOMED_ON = new ConcurrentHashMap<>();

    /** Last known position of every entity being annihilated, for the strikes and the death puff. */
    public static final Map<UUID, Vector3d> ANNIHILATION_POSITIONS = new ConcurrentHashMap<>();

    /** Ten ticks of strikes, then the client-side removal. */
    public static final int CLIENT_REMOVE_DELAY_TICKS = 10;

    /**
     * Looping background track played while the Transcendence HUD is up.
     *
     * <p>A plain {@link SoundEvent} rather than a registered one: it only ever plays on the client,
     * so it does not need to exist in the registry, and {@code SoundHandler} resolves it straight
     * from {@code assets/transcenmod/sounds.json}.
     *
     * <p>The audio file itself is not shipped -- see README "Background music" for where to drop the
     * {@code .ogg}.
     */
    public static final SoundEvent TRANSCEN_MUSIC =
            new SoundEvent(new ResourceLocation(MODID + ":music.transcendence"));

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
        // Left-click-with-the-egg asks the server to end the encounter.
        NETWORK.registerMessage(5, WipeRequestPacket.class, WipeRequestPacket::encode,
                WipeRequestPacket::new, WipeRequestPacket::handle);
        NETWORK.registerMessage(6, WipeEndedPacket.class, WipeEndedPacket::encode,
                WipeEndedPacket::new, WipeEndedPacket::handle);


        // The creative tab is appended to vanilla's array rather than registered, because in 1.19.2
        // there is no tab registry to register into. Done here because the spawn egg has to exist
        // first, and the array grows by one so vanilla's own tabs keep working.
        // The constructor argument is the tab's bare name, not its translation key: the game prefixes it
        // with "itemGroup." itself. Passing the finished key here is what produced the doubled
        // "itemGroup.itemGroup.transcenmod" label -- the key that must match the lang file is
        // "itemGroup." + this string.
        tab = new ItemGroup("transcenmod") {
            @Override
            public ItemStack createIcon() {
                return new ItemStack(TRANSCEN_SPAWN_EGG.get());
            }

            @Override
            public void fill(net.minecraft.util.NonNullList<ItemStack> items) {
                items.add(new ItemStack(TRANSCEN_SPAWN_EGG.get()));
            }
        };
        ItemGroup[] vanillaTabs = ItemGroup.GROUPS;
        ItemGroup[] withModTab = java.util.Arrays.copyOf(vanillaTabs, vanillaTabs.length + 1);
        withModTab[vanillaTabs.length] = tab;
        ItemGroup.GROUPS = withModTab;
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // Registered reflectively by class name: this file is loaded on dedicated servers too, and a
        // direct reference to a client-only class in its constant pool would break that. The client
        // classes register their own packet hooks in static initialisers.
        MinecraftForge.EVENT_BUS.register(com.example.transcenmod.client.ClientEvents.class);
        MinecraftForge.EVENT_BUS.register(com.example.transcenmod.client.DoomedRenderer.class);
    }

    /** 1.16.5 registers entity renderers during client setup, not through a renderer event. */
    private void registerRenderers(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        net.minecraftforge.fml.client.registry.RenderingRegistry.registerEntityRenderingHandler(
                TRANSCEN_ZOMBIE.get(), com.example.transcenmod.client.TranscenZombieRenderer::new);
        net.minecraftforge.fml.client.registry.RenderingRegistry.registerEntityRenderingHandler(
                TRANSCEN_LIGHTNING.get(), com.example.transcenmod.client.TranscenLightningRenderer::new);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        // The stat block lives in TranscenZombie#createAttributes so there is exactly one source of
        // truth for it.
        event.put(TRANSCEN_ZOMBIE.get(), TranscenZombie.createAttributes().create());
    }
}
