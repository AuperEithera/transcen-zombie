package com.example.transcenmod;

import com.example.transcenmod.entity.TranscenZombie;
import com.example.transcenmod.event.TranscenEvents;
import com.example.transcenmod.item.SpawnTranscenZombieItem;
import com.example.transcenmod.network.NetworkHandler;
import com.example.transcenmod.util.ConfigManager;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.EntityEntryBuilder;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod(
        modid = TranscenMod.MODID,
        name = "Transcendence Zombie",
        version = "0.14",
        acceptedMinecraftVersions = "[1.12.2]",
        acceptableRemoteVersions = "*",
        useMetadata = false
)
public class TranscenMod {

    public static final String MODID = "transcenmod";

    public static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger();

    @Mod.Instance(MODID)
    public static TranscenMod instance;

    @SidedProxy(
            clientSide = "com.example.transcenmod.client.ClientProxy",
            serverSide = "com.example.transcenmod.CommonProxy"
    )
    public static CommonProxy proxy;

    // ---------------------------------------------------------------------------------------------
    // Registered objects
    // ---------------------------------------------------------------------------------------------

    public static final ResourceLocation ZOMBIE_NAME = new ResourceLocation(MODID, "transcenzombie");

    private static EntityEntry zombieEntry;

    public static Item spawnEgg;

    public static final net.minecraft.creativetab.CreativeTabs CREATIVE_TAB =
            new net.minecraft.creativetab.CreativeTabs(MODID) {
                @Override
                public net.minecraft.item.ItemStack createIcon() {
                    return spawnEgg == null
                            ? net.minecraft.item.ItemStack.EMPTY
                            : new net.minecraft.item.ItemStack(spawnEgg);
                }
            };

    public static EntityEntry zombieEntry() {
        return zombieEntry;
    }

    // ---------------------------------------------------------------------------------------------
    // Shared state
    // ---------------------------------------------------------------------------------------------

    public static volatile boolean defenceEnabled = true;

    public static final Set<Class<?>> BANNED_CLASSES = ConcurrentHashMap.newKeySet();

    public static final Map<UUID, Vec3d> ZOMBIE_POSITIONS = new ConcurrentHashMap<UUID, Vec3d>();

    public static final Map<UUID, Float> ZOMBIE_YAW = new ConcurrentHashMap<UUID, Float>();
    public static final Map<UUID, Float> ZOMBIE_PITCH = new ConcurrentHashMap<UUID, Float>();

    public static final Map<UUID, Long> ANNIHILATION_STRIKE_STARTED = new ConcurrentHashMap<UUID, Long>();

    public static final Map<UUID, Vec3d> ANNIHILATION_POSITIONS = new ConcurrentHashMap<UUID, Vec3d>();

    public static final SoundEvent TRANSCEN_MUSIC =
            new SoundEvent(new ResourceLocation(MODID, "music.transcendence"))
                    .setRegistryName(new ResourceLocation(MODID, "music.transcendence"));

    public static boolean HUD_ACTIVE = false;
    public static boolean SHOW_DEATH_SCREEN = false;

    public static final ConfigManager CONFIG = new ConfigManager();

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    public TranscenMod() {
        NetworkHandler.init();
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new RegistryHandler());
        MinecraftForge.EVENT_BUS.register(new TranscenEvents());
        CONFIG.init(event.getModConfigurationDirectory());

        // The ban list is enforced twice from here on: by the EntityJoinWorldEvent handler it always had,
        // and by the guard the coremod injects into World.spawnEntity / onEntityAdded / Chunk.addEntity and
        // the list add call sites. The injected side never touches BANNED_CLASSES itself - it asks through
        // this predicate, which reads the live set, so the two cannot drift apart.
        com.example.transcenmod.core.CoreHooks.setBanPredicate(new java.util.function.Predicate<Class<?>>() {
            @Override
            public boolean test(Class<?> type) {
                return type != null && BANNED_CLASSES.contains(type);
            }
        });
        com.example.transcenmod.core.CoreHooks.setProtectionEnabled(defenceEnabled);

        // Second TranscendenceZombieFullPower report. The coremod's own pass runs from IFMLCallHook.call(),
        // which is early enough that a coremod sorted after this one may not have registered yet; this one
        // runs after the whole tweak loop, so the list it reports is the final one. Nothing is removed from
        // that list either way: FullPowerFilter discards a foreign transformer's changes per class.
        int foreign = com.example.transcenmod.core.TranscenCorePlugin.purgeIfEnabled();
        if (foreign >= 0) {
            LOGGER.info("[transcenmod] TranscendenceZombieFullPower: {} foreign transformer(s) "
                    + "will not be replayed", foreign);
        }

        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    public static class RegistryHandler {

        @SubscribeEvent
        public void registerEntities(RegistryEvent.Register<EntityEntry> event) {
            zombieEntry = EntityEntryBuilder.<TranscenZombie>create()
                    .id(ZOMBIE_NAME, 0)
                    .name("transcenzombie")
                    .entity(TranscenZombie.class)
                    // Tracking range has to cover the 1024 block follow range, so this asks for the
                    // maximum the client will accept for a monster, and one update per tick.
                    .tracker(80, 1, true)
                    .build();
            event.getRegistry().register(zombieEntry);

            // Deliberately no spawn list entry. 1.12.2 only spawns what has been added to a creature
            // type's weighted list, so leaving this out is what keeps the zombie a summoned creature
            // rather than one that turns up in every dark chunk.
        }

        @SubscribeEvent
        public void registerItems(RegistryEvent.Register<Item> event) {
            spawnEgg = new SpawnTranscenZombieItem();
            event.getRegistry().register(spawnEgg);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Health bridge
    // ---------------------------------------------------------------------------------------------

    public void setHealthProtected(UUID player, boolean protectedNow) {
        com.example.transcenmod.core.CoreHooks.setProtected(player, protectedNow);
    }

    public void setHealthRetaliating(UUID player, boolean retaliating) {
        com.example.transcenmod.core.CoreHooks.setRetaliating(player, retaliating);
    }

    public static void clearHealthState(UUID player) {
        com.example.transcenmod.core.CoreHooks.clear(player);
    }

    public static void setEncounterPresent(boolean present) {
        com.example.transcenmod.core.CoreHooks.setEncounterPresent(present);
    }

    public static boolean isZombie(Entity entity) {
        return entity instanceof TranscenZombie;
    }
}
