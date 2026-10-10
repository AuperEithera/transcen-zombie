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

/**
 * Transcendence Zombie -- Minecraft 1.12.2.
 *
 * <p>Ported from the 1.16.5 build in {@code 1.16.5/}. The behaviour is unchanged; what changed is the
 * shape of the entry point, because this generation has no mod event bus, no deferred registers and no
 * {@code @Mod} constructor injection. Registration is done from {@code @SubscribeEvent} handlers on
 * {@code RegistryEvent}, which is what Forge 1.12.2 offers instead.
 *
 * <p>Two 1.12.2-specific traps are handled explicitly here and are worth knowing about, because both fail
 * in ways a compile cannot see:
 *
 * <ul>
 *   <li>The network channel is created from the <em>constructor</em>, not from {@code preInit}. Forge's
 *       {@code NetworkRegistry.newChannel} resolves the owning mod through the "active mod container" on
 *       the loader, which is only set while the {@code @Mod} instance is being constructed.</li>
 *   <li>Registration is attached from an explicit {@code MinecraftForge.EVENT_BUS.register} call rather
 *       than from {@code @Mod.EventBusSubscriber}, so the ordering relative to the coremod that must be
 *       installed before any entity class loads is visible in one place.</li>
 * </ul>
 */
@Mod(
        modid = TranscenMod.MODID,
        name = "Transcendence Zombie",
        version = "0.11",
        acceptedMinecraftVersions = "[1.12.2]",
        acceptableRemoteVersions = "*",
        useMetadata = false
)
public class TranscenMod {

    public static final String MODID = "transcenmod";

    /** Shared logger, so every subsystem reports through one place. */
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

    /** Registry names, in one place because several call sites need them. */
    public static final ResourceLocation ZOMBIE_NAME = new ResourceLocation(MODID, "transcenzombie");

    /**
     * The entity registry entries.
     *
     * <p>1.16.5 held {@code RegistryObject}s that nobody could read before the mod event bus had run.
     * Here the entry is filled in during {@code RegistryEvent.Register<EntityEntry>} and read back
     * through the accessors below.
     */
    private static EntityEntry zombieEntry;

    public static Item spawnEgg;

    /**
     * The mod's creative tab.
     *
     * <p>1.12.2 registers tabs by constructing a {@code CreativeTabs} subclass, which appends itself to
     * {@code CreativeTabs.CREATIVE_TAB_ARRAY} in the constructor. That is the opposite of the 1.16.5
     * build, which had to grow {@code ItemGroup.GROUPS} by hand. The constructor argument is the bare
     * name, not the finished translation key: the game prefixes {@code itemGroup.} itself, which is what
     * makes {@code itemGroup.transcenmod} the key the lang file has to carry.
     */
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

    /**
     * Covers the systems around the zombie: the revival that puts a removed zombie back, the periodic
     * sweep, the bar, the overlay and the music. False stops those, which is what lets a wipe stay wiped.
     *
     * <p>Deliberately does not reach the entity's own defences. {@code setDead}, {@code setHealth} and the
     * position guard stay in force whatever this says -- switching it off must not turn the creature into
     * an ordinary mob, it only stops this mod working to keep it present.
     */
    public static volatile boolean defenceEnabled = true;

    /**
     * Annihilation clears what is present; this is what stops the same creature walking back in a tick
     * later and being removed again forever. The ban stays for the session and covers the whole class.
     */
    public static final Set<Class<?>> BANNED_CLASSES = ConcurrentHashMap.newKeySet();

    /** Last seen position, so a removed zombie can be put back. */
    public static final Map<UUID, Vec3d> ZOMBIE_POSITIONS = new ConcurrentHashMap<UUID, Vec3d>();

    /**
     * A revived zombie should come back as the entity it was, and one facing due south is visibly a
     * different one. Kept apart from {@link #ZOMBIE_POSITIONS} so that map keeps its plain
     * {@code UUID -> Vec3d} shape the rest of the mod reasons about.
     */
    public static final Map<UUID, Float> ZOMBIE_YAW = new ConcurrentHashMap<UUID, Float>();
    public static final Map<UUID, Float> ZOMBIE_PITCH = new ConcurrentHashMap<UUID, Float>();

    /**
     * When the strike sequence for an annihilated entity began, keyed by UUID. The value is the level's game
     * time at detection, so the countdown is derived rather than ticked.
     */
    public static final Map<UUID, Long> ANNIHILATION_STRIKE_STARTED = new ConcurrentHashMap<UUID, Long>();

    /** Last known position of every entity being annihilated, for the strikes and the death puff. */
    public static final Map<UUID, Vec3d> ANNIHILATION_POSITIONS = new ConcurrentHashMap<UUID, Vec3d>();

    /**
     * The background track.
     *
     * <p>A plain {@code SoundEvent} rather than a registered one: it only ever plays on the client, so it
     * need not be in the registry. {@code SoundHandler} resolves it straight from
     * {@code assets/transcenmod/sounds.json}.
     */
    public static final SoundEvent TRANSCEN_MUSIC =
            new SoundEvent(new ResourceLocation(MODID, "music.transcendence"))
                    .setRegistryName(new ResourceLocation(MODID, "music.transcendence"));

    /** Armed on the client by right-clicking the spawn egg. */
    public static boolean HUD_ACTIVE = false;
    public static boolean SHOW_DEATH_SCREEN = false;

    /**
     * Per-world settings.
     *
     * <p>1.16.5 kept "has the encounter been ended" in a static and relied on the process holding one
     * world at a time. That is true of a dedicated server but not of a single-player client that opens two
     * worlds in one session, and it is why a wipe could come back armed after leaving and re-entering.
     * Here the flag is persisted next to the world instead.
     */
    public static final ConfigManager CONFIG = new ConfigManager();

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    public TranscenMod() {
        // The channel is created here rather than in preInit, and the placement is load-bearing. Forge's
        // NetworkRegistry.newChannel resolves the owning mod through the "active mod container" on the
        // loader, and that is only set while the @Mod instance is being constructed -- from an
        // FMLPreInitializationEvent handler it is already null, and the call dies with a
        // NullPointerException that takes the whole game down before any world loads.
        NetworkHandler.init();
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new RegistryHandler());
        MinecraftForge.EVENT_BUS.register(new TranscenEvents());
        CONFIG.init(event.getModConfigurationDirectory());
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    /**
     * Entity and item registration.
     *
     * <p>Kept as its own class rather than folded into {@link TranscenMod} because {@code @Mod.EventHandler}
     * methods and {@code @SubscribeEvent} methods are dispatched by two different buses, and a single class
     * holding both is the easiest way to register one of them on the wrong one.
     */
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
                    .egg(0x8B2BE2, 0xE22B7A)
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

    /**
     * Pushes the egg-holder's protection into the coremod's state.
     *
     * <p>This is the 1.12.2 replacement for {@code PlayerHealthMixin}: the injected
     * {@code EntityLivingBase#getHealth} asks {@code CoreHooks} for an override on every call, and
     * {@code CoreHooks} has to be told who is protected and who is retaliating. Both are UUID keyed sets
     * in the coremod package, because the transformer runs on the launch classloader and can never name a
     * class from this one.
     */
    public void setHealthProtected(UUID player, boolean protectedNow) {
        com.example.transcenmod.core.CoreHooks.setProtected(player, protectedNow);
    }

    public void setHealthRetaliating(UUID player, boolean retaliating) {
        com.example.transcenmod.core.CoreHooks.setRetaliating(player, retaliating);
    }

    /** Drops a player's health state entirely, on logout or respawn. */
    public static void clearHealthState(UUID player) {
        com.example.transcenmod.core.CoreHooks.clear(player);
    }

    /** Tells the coremod whether boss bars should be hidden this frame. */
    public static void setEncounterPresent(boolean present) {
        com.example.transcenmod.core.CoreHooks.setEncounterPresent(present);
    }

    /** Convenience for the registries: this mod's own view of "is this our zombie". */
    public static boolean isZombie(Entity entity) {
        return entity instanceof TranscenZombie;
    }
}
