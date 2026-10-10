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

// reaches into minecraft internals - entities are pulled from the client and server entity
// storages by hand and the entity section maps are walked directly
// the accesstransformer widens every member this mod touches to public at class-load time
// reflective lookups keep working in dev and production, the widened modifier survives
// reobfuscation because forge re-applies the AT at runtime
// plain field and method access is the compile-time proof that the AT targets the right SRG members
@Mod(TranscenMod.MODID)
public class TranscenMod {

    public static final String MODID = "transcenmod";

    public static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // tracking range must cover the 1024-block follow range, so this asks for the maximum
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

    // the spawn egg - right-click spawns the zombie and arms the HUD
    public static final RegistryObject<Item> TRANSCEN_SPAWN_EGG = ITEMS.register(
            "transcen_zombie_spawn_egg",
            () -> new SpawnTranscenZombieItem(new Item.Properties(), 0x8B2BE2, 0xE22B7A));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.transcenmod"))
                    .icon(() -> new ItemStack(TRANSCEN_SPAWN_EGG.get()))
                    .displayItems((params, output) -> output.accept(TRANSCEN_SPAWN_EGG.get()))
                    .build());

    // whether this mod's protection systems are running,left-clicking with the egg toggles it
    // covers the revival the periodic sweep the bar the overlay and the music,so a wipe stays wiped
    // it must never reach the zombie's own defences - setRemoved remove hurt setHealth and the
    // position guard stay in force whatever this says
    // off must not turn the creature into an ordinary mob,it only stops this mod keeping it present
    public static volatile boolean defenceEnabled = true;

    // classes the encounter has annihilated, they may not re-enter the world
    // annihilation removes what is present,this stops the same creature walking back in a tick
    // later and being removed forever - the ban is permanent for the session and covers the class
    public static final Set<Class<?>> BANNED_CLASSES = ConcurrentHashMap.newKeySet();

    // last seen position, so a removed zombie can be put back
    public static final Map<UUID, Vec3> ZOMBIE_POSITIONS = new ConcurrentHashMap<>();

    // kept beside the position, a revived zombie should come back as the entity it was
    // stored separately from ZOMBIE_POSITIONS, so that map keeps its plain UUID -> Vec3 shape
    public static final Map<UUID, Float> ZOMBIE_YAW = new ConcurrentHashMap<>();
    public static final Map<UUID, Float> ZOMBIE_PITCH = new ConcurrentHashMap<>();
    // level game time at which each doomed entity's two-second display window started, keyed by UUID
    // the countdown is derived from it,not ticked
    public static final Map<UUID, Long> ANNIHILATION_DOOMED_ON = new ConcurrentHashMap<>();

    // last known position of every entity being annihilated,feeds the strikes and the death puff
    public static final Map<UUID, Vec3> ANNIHILATION_POSITIONS = new ConcurrentHashMap<>();

    // ten ticks of strikes, then the client-side removal
    public static final int CLIENT_REMOVE_DELAY_TICKS = 10;

    // plain SoundEvent,not a registered one - it only plays client-side so it needs no registry entry
    // SoundManager resolves it straight from assets/transcenmod/sounds.json
    // the .ogg itself is not shipped, see README "Background music"
    public static final SoundEvent TRANSCEN_MUSIC =
            SoundEvent.createVariableRangeEvent(new ResourceLocation(MODID + ":music.transcendence"));

    // set on the client once the spawn egg arms the HUD
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
        // left-click with the egg,toggles the protection
        NETWORK.registerMessage(5, WipeRequestPacket.class, WipeRequestPacket::encode,
                WipeRequestPacket::new, WipeRequestPacket::handle);
        // broadcast - the encounter is over,drop the latch and the local copies
        NETWORK.registerMessage(6, WipeEndedPacket.class, WipeEndedPacket::encode,
                WipeEndedPacket::new, WipeEndedPacket::handle);

    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // this file also loads on dedicated servers,so the client-only classes are only ever touched
        // as class literals here
        // the client classes register their own packet hooks in static initialisers
        MinecraftForge.EVENT_BUS.register(com.example.transcenmod.client.ClientEvents.class);
        MinecraftForge.EVENT_BUS.register(com.example.transcenmod.client.DoomedRenderer.class);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(TRANSCEN_ZOMBIE.get(), TranscenZombieRenderer::new);
        event.registerEntityRenderer(TRANSCEN_LIGHTNING.get(), TranscenLightningRenderer::new);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        // the stat block lives in TranscenZombie#createAttributes, one source of truth
        event.put(TRANSCEN_ZOMBIE.get(), TranscenZombie.createAttributes().build());
    }


}
