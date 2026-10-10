# Transcendence Zombie (transcenmod)

Minecraft **1.20.1** / Forge **47.4.26** mod, grown out of the single-file draft
`transcenzombie`.

The mod's core is unchanged: it manually unlinks entities from the level's entity storages by hand
rather than going through vanilla's removal path, and it drives that from cancelled living ticks.
**All reflection and all low-level calls are kept verbatim** -- they were not rewritten, not "cleaned
up", and not replaced by vanilla APIs.

What the port adds is an **AccessTransformer** that widens every member that code reaches into.

---

## Behaviour

| # | Requirement | Where |
|---|---|---|
| 1 | Spawn-egg item that spawns the zombie; the bar and the screen rendering arm on right-click | `item/SpawnTranscenZombieItem`, `network/ActivateHudPacket` |
| 2 | Annihilation never applies to an exempt player; everyone else is cleared at full effect and banned | `event/TranscenEvents#isAnnihilationExempt` |
| 3 | Red lightning, vanilla radius, 1919810 damage plus `setHealth(0)`; 2 bolts per tick for 10 ticks | `entity/TranscenLightningBolt`, `TranscenEvents#strikeAt` |
| 4 | Vanilla zombie model, colour-inverted | `assets/transcenmod/textures/entity/transcenzombie.png` |
| 5 | Vanilla zombie AI; 114514 health, 2048 attack, 1024 follow range, 30 armour, 20 toughness | `entity/TranscenZombie#createAttributes` |
| 6 | One explosion-4 fireball per second at the nearest living thing | `entity/TranscenZombie#throwFireballAtNearest` |
| 7 | Server-side removal first, client-side removal plus the vanilla white death puff ten ticks later | `TranscenEvents#advanceRemovalTimers`, `network/ClientRemovePacket` |
| 8 | Vanilla-styled boss bar that cycles its colour on a timer | `client/bar/TranscenBossBar` |
| 9 | Looping background track while the HUD is up | `client/audio/TranscenMusic` |

### Player exemption

A player is skipped entirely -- not removed, not banned, not struck -- while holding the spawn egg,
in either hand. Every other player is cleared at full effect **and their class is banned**, so they
cannot re-enter the world. That is what was asked for; be aware it is genuinely destructive.

### The boss bar

Drawn with the real vanilla bar sprites rather than a flat rectangle. The layout was measured from
vanilla's own texture: `assets/minecraft/textures/gui/bars.png` holds the seven boss-bar styles in
rows 0..69, ten rows per style, five for the dark background and five for the solid bar, 182px wide
at u=0. The bar is blitted in two halves so the rounded caps survive partial widths.

The sprite is white, so it is tinted with `setShaderColor`. The tint is the 1.0.0 behaviour: it
cycles red -> yellow -> blue -> white -> purple -> green -> cyan, changing every 15 ticks. The
translucent "damage ghost" behind the live bar is kept as well.

## Background music

The track ships with the mod and loops for as long as the HUD is up.

```
src/main/resources/assets/transcenmod/sounds/music/hell_river.ogg
```

Stream properties as shipped: **Vorbis, stereo, 44100 Hz, ~112 kbit/s, 2:02**. That is well inside
what Minecraft's Vorbis decoder accepts, so no conversion step is needed.

The file name is fixed by `assets/transcenmod/sounds.json`; renaming the OGG means updating the
`"name"` entry there as well. Note the `"name"` value is `transcenmod:music/hell_river` -- there is
no `sounds/` component in it, because that prefix is implied by `sounds.json` itself.

If the OGG is ever missing or unreadable, the mod logs **one** warning and plays nothing. It
deliberately does not try anyway, because `SoundManager` would otherwise spam an unknown-sound error
every tick.

To swap in a different track, replace the OGG and rebuild. To convert another file to Ogg Vorbis
with ffmpeg:

```
ffmpeg -i input.m4s -c:a libvorbis -q:a 5 hell_river.ogg
```

---

## AccessTransformer

File: `src/main/resources/META-INF/accesstransformer.cfg`
(also declared in `build.gradle` via `minecraft.accessTransformer` and in `mods.toml` via
`accessTransformers`, so it is applied by ForgeGradle **and** loaded from the built jar at runtime).

Every widened member below is verified twice:

* **compile time** -- `com.example.transcenmod.util.AccessTransformerVerifier` reads and writes each
  one through plain Java member access, with zero reflection. If the AT stops targeting the right
  member, that class stops compiling with `"<member> has private access in <class>"`.
* **runtime** -- the same member is loaded and its modifier is asserted to be `public`.

| Class | Member | Vanilla | Why |
|---|---|---|---|
| `Entity` | `position` | private | respawn writes the position directly |
| `Entity` | `levelCallback` | private | removal sets it to `EntityInLevelCallback.NULL` |
| `Entity` | `valid` | private (in `CapabilityProvider`) | removal marks the entity invalid |
| `Entity` | `isAddedToWorld` | private | removal clears the flag |
| `CapabilityProvider` | `capabilities`, `valid` | private | Forge injects these on `Entity`'s superclass |
| `ClientLevel` | `entityStorage`, `tickingEntities`, `players` | private / package-private | client-side storage surgery |
| `ServerLevel` | `entityManager`, `players`, `entityTickList` | private / package-private | server-side storage surgery |
| `PersistentEntitySectionManager` | `sectionStorage`, `visibleEntityStorage` | package-private / private | the storage chain |
| `EntitySection` | `storage` | private | holds the per-class entity map |
| `ClassInstanceMultiMap` | `byClass` | private | **the 1.20.1 replacement for the old `ChunkEntitySections`** |
| `EntityTickList` | `active`, `passive` | package-private | removes the entity from the tick list |
| `EntityLookup` | `byId`, `byUuid` | private | removes the entity from the id/uuid lookups |
| `LightningBolt` | `life` | private | keeps a custom bolt alive instead of vanilla's two-tick age-out |
| `LivingEntity` | `getMaxHealth()` | `public final` | `-f` drops the `final` so the zombie can override it |

### Widening vs. removing `final`

Widening access to `public` is the safe case: it only relaxes a check, and reflection plus direct
access both benefit.

Removing `final` is a different operation and is used here for **exactly one** member. The AT syntax
is `public-f` (equivalently `public` + `-f`), and it is the *only* way to make a `final` method
overridable short of not overriding it. Its cost is that it edits the real class contract:

* The transformed method body is unchanged, so the change is behaviour-neutral by itself -- but it
  is no longer a compile-time-constant carrier for callers that inlined `getMaxHealth()`.
  `getMaxHealth()` never returns a constant here (it reads the `MAX_HEALTH` attribute), so there is
  no such carrier to invalidate.
* The mod now *depends* on the SRG id being right. This file targets `m_21233_`, which was verified
  against the 1.20.1 SRG artifact. On a different Minecraft version the same id may name a different
  method, and the override would silently become hollow (it would compile, but never be called).
  This is the real reason to keep `-f` rare: a wrong `public` line fails loudly, a wrong `public-f`
  line can fail quietly.

If you would rather not touch `final` at all, delete the `public-f` line and the
`getMaxHealth()` override in `TranscenZombie`; `getHealth()` still returns the sentinel value and
the `MAX_HEALTH` attribute from `TranscenMod#registerAttributes` stays in effect.

### Names: SRG vs. mapped

Forge resolves AT entries in both namespaces. This file uses **SRG ids** (`f_19825_`,
`m_21233_()F`) for members whose id is published in the 1.20.1 mapping files, and **mapped names**
(`isAddedToWorld`) where it is not. Both paths are exercised by the table above, so the file doubles
as a working example of each.

### 1.20.1 specifics worth knowing

* `Entity extends CapabilityProvider` in Forge, so `valid` / `capabilities` are declared on
  `CapabilityProvider`, not on `Entity`.
* `EntitySection.ChunkEntitySections` **does not exist in 1.20.1**; the per-class lists live in
  `net.minecraft.util.ClassInstanceMultiMap#byClass`.
* `EntityTickList#active` is an `Int2ObjectMap`, not the `Set<Integer>` of older versions. The
  removal path handles both, because the reflection lookup is name-driven.
* `LivingEntity#getMaxHealth()` is `public final`; `Entity#setPos(double,double,double)` is *not*
  final, which is why only the former needs `public-f`.

### Which events can actually be cancelled

`Event#setCanceled` throws `UnsupportedOperationException` unless the event class carries
`@Cancelable`. In the `TickEvent` family **none of them do** -- verified against both 47.4.23 and
47.4.26: every class in `TickEvent.java` has zero `@Cancelable` annotations. So the cancel/un-cancel
pairs for `LevelTickEvent` and `PlayerTickEvent` were removed; they crashed the server thread on the
first tick with

```
UnsupportedOperationException: Attempted to call Event#setCanceled() on a
non-cancelable event of type: net.minecraftforge.event.TickEvent.LevelTickEvent
```

Worth knowing: **forcing the cancel flag on `LevelTickEvent` would be pointless even if it were
possible.** Forge only *reads* the flag when the posting helper returns a `boolean` that Minecraft
checks:

| Event | Posting helper | Return value | Minecraft checks it? |
|---|---|---|---|
| `LivingEvent.LivingTickEvent` | `ForgeHooks.onLivingTick` -> `return EVENT_BUS.post(...)` | `boolean` | yes -- `LivingEntity.tick()` returns early |
| `EntityJoinLevelEvent` | `EventBus.post` in `Level#addFreshEntity` path | `boolean` | yes |
| `TickEvent.LevelTickEvent` | `ForgeEventFactory.onPreLevelTick` -> `MinecraftForge.EVENT_BUS.post(...)` | **`void`** | **no -- discarded** |
| `TickEvent.PlayerTickEvent` | `ForgeEventFactory.onPlayerTick` | **`void`** | **no -- discarded** |

A Mixin would not change that: the flag is already discarded at the throw-away call site, so there
is nothing to cancel. What the mod actually wants -- "stop every living entity from ticking" -- is
exactly what the `LivingTickEvent` cancellation already does, and that one is legitimate.

## Build

Requires **JDK 17** (the local one is `D:\java17`). The Gradle wrapper pins Gradle 8.8.

```powershell
$env:JAVA_HOME = "D:\java17"
.\gradlew.bat build
```

Output: `build/libs/transcenmod-1.0.0.jar`.

> On this machine the build must run **outside the DSH sandbox**: ForgeGradle writes jars through
> the JDK `zipfs` provider, which consults `Files.isWritable()`, and inside the sandbox that returns
> `false` for every path, so `AccessTransformer`/`reobfJar` fail with
> `ReadOnlyFileSystemException`. With the sandbox off, the build is fully green.

## Opening in IntelliJ IDEA

1. `File > Open` -> select this folder (the one containing `build.gradle`).
2. Let Gradle import. If it complains about the JDK, set
   `File > Project Structure > SDK` to a **JDK 17** and set the Gradle JVM to the same one.
3. Run configurations `runClient` / `runServer` are generated by ForgeGradle automatically.
4. `build.gradle` already enables `minecraft.accessTransformer`, so the AT is active while you code:
   IDEA will resolve the widened members and the verification class will compile.

## Layout

```
src/main/java/com/example/transcenmod/
  TranscenMod.java                     mod entry, registration, network, attribute setup
  entity/TranscenZombie.java           the indestructible zombie
  entity/TranscenLightningBolt.java    per-tick AoE lightning
  event/TranscenEvents.java            cancel/un-cancel tick handlers + the annihilation loop
  network/DeathScreenPacket.java       death overlay trigger
  network/ClientRemovePacket.java      client-side force-removal
  client/TranscenZombieRenderer.java   zombie renderer
  client/TranscenLightningRenderer.java red lightning
  client/ClientEvents.java             HUD bar, title, death overlay, sky tint
  util/CustomEntityRemover.java        low-level entity storage removal (reflection preserved)
  util/ReflectionAccess.java           the reflection helper (getField + SRG aliases)
  util/AccessTransformerVerifier.java  compile-time proof that the AT targets the right members
src/main/resources/
  META-INF/accesstransformer.cfg       the AccessTransformer
  META-INF/mods.toml
  pack.mcmeta
```

## Notes

* `CustomEntityRemover` keeps the original mapped-name-first / SRG-second lookup order. The SRG
  fallback list was corrected and extended, because three ids in the draft were wrong for 1.20.1
  (`ServerLevel.players`, `ServerLevel.entityManager`, `EntityLookup.byId`/`byUuid`).
* The draft's `SimpleChannel.send(NetworkDirection, MSG, Iterable<?>)` overload no longer exists in
  the 1.20.1 Forge network API; the broadcast now goes through `PacketDistributor.DIMENSION`.
* `net.minecraft.core.BlockPos` and `ServerLevel#setDayTime` were fixed up (`Level` has no
  `setDayTime`).
* Cosmetic-only deprecation warnings remain for `new ResourceLocation(String)` and
  `FMLJavaModLoadingContext.get()`; both still work.
