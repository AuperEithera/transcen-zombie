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
import com.example.transcenmod.network.HudSyncPacket;
import com.example.transcenmod.network.WipeEndedPacket;
import com.example.transcenmod.network.WipeRequestPacket;
import com.example.transcenmod.network.MarkDoomedPacket;
import com.example.transcenmod.util.ReflectionAccess;
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

// this mod removes entities from the client/server storages by hand and walks the entity section maps
// direct - the AT widens every member touched here,reflectively or not,private/package-private/protected to public
// widening survives reobfuscation, so the reflective lookups resolve the same in dev and in production
// and the direct accesses compile,which is the proof the AT lines target real SRG members
@Mod(TranscenMod.MODID)
public class TranscenMod {

    public static final String MODID = "transcenmod";

    public static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    // tracking range must cover the zombie's 1024 block follow range
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

    public static final RegistryObject<Item> TRANSCEN_SPAWN_EGG = ITEMS.register(
            "transcen_zombie_spawn_egg",
            () -> new SpawnTranscenZombieItem(new Item.Properties(), 0x8B2BE2, 0xE22B7A));

    // plain holder so the tab instance stays reachable
    private static CreativeModeTab tab;

    // covers the systems around the zombie,revival sweep bar overlay music - false is what lets a wipe stay wiped
    // never covers the entity's own defences, setRemoved remove hurt setHealth and the position guard stay in force
    // so switching this off cannot turn the creature into an ordinary mob
    public static volatile boolean defenceEnabled = true;

    // ban is deliberately permanent for the session and covers the whole class
    // annihilation only removes what is present - without this the same creature walks back in a tick later,forever
    public static final Set<Class<?>> BANNED_CLASSES = ConcurrentHashMap.newKeySet();

    public static final Map<UUID, Vec3> ZOMBIE_POSITIONS = new ConcurrentHashMap<>();

    // kept beside the position, a revived zombie comes back as the entity it was,not facing due south
    public static final Map<UUID, Float> ZOMBIE_YAW = new ConcurrentHashMap<>();
    public static final Map<UUID, Float> ZOMBIE_PITCH = new ConcurrentHashMap<>();
    // value is the level game time at detection, so the two second countdown is derived rather than ticked
    public static final Map<UUID, Long> ANNIHILATION_DOOMED_ON = new ConcurrentHashMap<>();

    public static final Map<UUID, Vec3> ANNIHILATION_POSITIONS = new ConcurrentHashMap<>();

    // ten ticks of strikes before the client side removal
    public static final int CLIENT_REMOVE_DELAY_TICKS = 10;

    // plain SoundEvent not a registered one,it only ever plays client side so the registry never sees it
    // SoundManager resolves it straight from assets/transcenmod/sounds.json
    // the .ogg is not shipped, see README "Background music"
    public static final SoundEvent TRANSCEN_MUSIC =
            new SoundEvent(new ResourceLocation(MODID + ":music.transcendence"));

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
        // left click with the egg asks the server to end the encounter
        NETWORK.registerMessage(5, WipeRequestPacket.class, WipeRequestPacket::encode,
                WipeRequestPacket::new, WipeRequestPacket::handle);
        NETWORK.registerMessage(6, WipeEndedPacket.class, WipeEndedPacket::encode,
                WipeEndedPacket::new, WipeEndedPacket::handle);


        // 1.19.2 predates the tab registry, so the tab is appended to vanilla's static array instead
        // done here because the spawn egg has to exist first
        // the ctor string is a bare name, the game prefixes itemGroup. itself
        // passing the finished key gave the doubled itemGroup.itemGroup.transcenmod label
        tab = new CreativeModeTab("transcenmod") {
            @Override
            public ItemStack makeIcon() {
                return new ItemStack(TRANSCEN_SPAWN_EGG.get());
            }

            @Override
            public void fillItemList(net.minecraft.core.NonNullList<ItemStack> items) {
                items.add(new ItemStack(TRANSCEN_SPAWN_EGG.get()));
            }
        };
        CreativeModeTab[] vanillaTabs = CreativeModeTab.TABS;
        CreativeModeTab[] withModTab = java.util.Arrays.copyOf(vanillaTabs, vanillaTabs.length + 1);
        withModTab[vanillaTabs.length] = tab;
        CreativeModeTab.TABS = withModTab;
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // referenced by name - this file loads on dedicated servers too and a client only class in its
        // constant pool would break that, the client classes register their own packet hooks statically
        MinecraftForge.EVENT_BUS.register(com.example.transcenmod.client.ClientEvents.class);
        MinecraftForge.EVENT_BUS.register(com.example.transcenmod.client.DoomedRenderer.class);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(TRANSCEN_ZOMBIE.get(), TranscenZombieRenderer::new);
        event.registerEntityRenderer(TRANSCEN_LIGHTNING.get(), TranscenLightningRenderer::new);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        // stat block lives in TranscenZombie#createAttributes,one source of truth
        event.put(TRANSCEN_ZOMBIE.get(), TranscenZombie.createAttributes().build());
    }
}
