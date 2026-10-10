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

// reaches into minecraft internals on purpose, removes entities from the client and server storages by hand
// and walks the entity section maps directly. accesstransformer.cfg widens every touched member to public
// at class load so this can be written as plain field access. the widened modifier survives reobfuscation
// because forge re-applies the AT at runtime,so that access also holds in production
// keeping the reflection path too - the direct form is the compile-time proof the AT hits the right SRG members
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

    // tracking range has to cover the 1024 block follow range, so this asks for the maximum
    public static final RegistryObject<EntityType<TranscenZombie>> TRANSCEN_ZOMBIE = ENTITIES.register(
            "transcenzombie",
            () -> EntityType.Builder.create(TranscenZombie::new, EntityClassification.MONSTER)
                    .size(0.6F, 1.95F)
                    .trackingRange(32)
                    .build("transcenzombie"));

    public static final RegistryObject<EntityType<TranscenLightningBolt>> TRANSCEN_LIGHTNING = ENTITIES.register(
            "transcen_lightning_bolt",
            () -> EntityType.Builder.create(TranscenLightningBolt::new, EntityClassification.MONSTER)
                    // size deliberately not the usual 0x0, a degenerate box is what a bolt cannot afford
                    // while it is being tracked and culled
                    // MONSTER,not MISC. measured on the client: its own MONSTER zombie arrived every time
                    // and never a bolt, and MISC is not serializable in this generation - the one remaining
                    // difference between the two types
                    .size(0.6F, 1.8F)
                    .trackingRange(64)
                    .setUpdateInterval(1)
                    .setShouldReceiveVelocityUpdates(true)
                    // the load-bearing line, vanilla lightning is client-excluded - it lives on the server and
                    // the client is told out of band instead of being handed an entity, so a modded subclass
                    // never arrives through the ordinary spawn path
                    // this factory is what gives forge a client-side constructor to build the instance with.
                    // measured before it existed: bolt added on every strike server-side, client never held
                    // one, renderer never entered
                                        .build("transcen_lightning_bolt"));

    // late-bound accessor - naming TRANSCEN_LIGHTNING in its own initialiser is a self-reference the
    // compiler rejects so the client factory goes through here and the read happens when a bolt is built,
    // long after registration
    private static EntityType<TranscenLightningBolt> lightningType() {
        return TRANSCEN_LIGHTNING.get();
    }

    // right-click spawns the zombie and arms the HUD
    public static final RegistryObject<Item> TRANSCEN_SPAWN_EGG = ITEMS.register(
            "transcen_zombie_spawn_egg",
            () -> new SpawnTranscenZombieItem(new Item.Properties(), 0x8B2BE2, 0xE22B7A));

    // the 1.19.2 era has no tab registry and no ItemGroup.builder so a tab is a subclass appended to the
    // plain mutable GROUPS array; only a holder so the instance stays reachable, see setup
    private static ItemGroup tab;

    // covers the systems around the zombie, the revival that puts a removed zombie back, the periodic
    // sweep, the bar, the overlay and the music - false stops those, which is what lets a wipe stay wiped
    // deliberately does not reach the entity's own defences setRemoved,remove,hurt,setHealth and the
    // position guard stay in force whatever this says. switching it off must not turn the creature into an
    // ordinary mob, it only stops this mod working to keep it present
    public static volatile boolean defenceEnabled = true;

    // annihilation clears what is present, this is what stops the same creature walking back in a tick
    // later and being removed again forever the ban stays for the session and covers the whole class
    public static final Set<Class<?>> BANNED_CLASSES = ConcurrentHashMap.newKeySet();

    // last seen position,so a removed zombie can be put back
    public static final Map<UUID, Vector3d> ZOMBIE_POSITIONS = new ConcurrentHashMap<>();

    // a revived zombie should come back as the entity it was and one facing due south is visibly a
    // different one. kept apart from ZOMBIE_POSITIONS so that map keeps its plain UUID -> Vector3d shape
    // that the rest of the mod reasons about
    public static final Map<UUID, Float> ZOMBIE_YAW = new ConcurrentHashMap<>();
    public static final Map<UUID, Float> ZOMBIE_PITCH = new ConcurrentHashMap<>();
    // when each doomed entity's two-second display window started, keyed by UUID - the value is the level
    // game time at detection, so the countdown is derived rather than ticked
    public static final Map<UUID, Long> ANNIHILATION_DOOMED_ON = new ConcurrentHashMap<>();

    // last known position of every entity being annihilated for the strikes and the death puff
    public static final Map<UUID, Vector3d> ANNIHILATION_POSITIONS = new ConcurrentHashMap<>();

    // ten ticks of strikes,then the client-side removal
    public static final int CLIENT_REMOVE_DELAY_TICKS = 10;

    // a plain SoundEvent rather than a registered one,it only ever plays on the client so it need not be
    // in the registry, SoundHandler resolves it straight from assets/transcenmod/sounds.json
    // the .ogg itself is not shipped, see README "Background music"
    public static final SoundEvent TRANSCEN_MUSIC =
            new SoundEvent(new ResourceLocation(MODID + ":music.transcendence"));

    // armed on the client by right-clicking the spawn egg
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
        // left-click with the egg asks the server to end the encounter
        NETWORK.registerMessage(5, WipeRequestPacket.class, WipeRequestPacket::encode,
                WipeRequestPacket::new, WipeRequestPacket::handle);
        NETWORK.registerMessage(6, WipeEndedPacket.class, WipeEndedPacket::encode,
                WipeEndedPacket::new, WipeEndedPacket::handle);


        // tab appended to vanilla's array rather than registered - the 1.19.2 era has no tab registry to
        // register into. done here because the spawn egg has to exist first and the array grows by one so
        // vanilla's own tabs keep working
        // the constructor argument is the tab's bare name,not its translation key - the game prefixes
        // "itemGroup." itself, and passing the finished key is what produced the doubled
        // itemGroup.itemGroup.transcenmod label. the key that must match the lang file is itemGroup.+this
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
        // registered by name,this file is loaded on dedicated servers too and a direct reference to a
        // client-only class in its constant pool would break that. the client classes register their own
        // packet hooks in static initialisers
        MinecraftForge.EVENT_BUS.register(com.example.transcenmod.client.ClientEvents.class);
        MinecraftForge.EVENT_BUS.register(com.example.transcenmod.client.DoomedRenderer.class);
    }

    // 1.16.5 registers entity renderers during client setup,not through a renderer event
    private void registerRenderers(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        net.minecraftforge.fml.client.registry.RenderingRegistry.registerEntityRenderingHandler(
                TRANSCEN_ZOMBIE.get(), com.example.transcenmod.client.TranscenZombieRenderer::new);
        net.minecraftforge.fml.client.registry.RenderingRegistry.registerEntityRenderingHandler(
                TRANSCEN_LIGHTNING.get(), com.example.transcenmod.client.TranscenLightningRenderer::new);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        // one source of truth for the stat block - it lives in TranscenZombie#createAttributes
        event.put(TRANSCEN_ZOMBIE.get(), TranscenZombie.createAttributes().create());
    }
}
