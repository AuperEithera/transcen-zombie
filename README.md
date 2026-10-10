# Transcendence Zombie

A Minecraft mod that adds a single, extremely persistent hostile zombie. Three builds live side by side in
this repository, one per Minecraft generation. They are separate Gradle projects, not a multi-module build,
because the API surface differs enough between generations that sharing sources would cost more than it
saves.

| directory | minecraft | forge | java |
| --- | --- | --- | --- |
| `1.20.1/` | 1.20.1 | 47.4.26 | 17 |
| `1.19.2/` | 1.19.2 | 43.3.0 | 17 |
| `1.16.5/` | 1.16.5 | 36.2.34 | 8 |

## what it does

- a spawn egg that summons one zombie and arms the encounter
- the zombie cannot be removed by anything, including `/kill` and the removal path other mods use
- left click with the egg held disarms the encounter and clears the zombies, right click re-arms it
- blood-red lightning at the wipe, striking at vanilla range for 1919810 damage
- a fireball every second at the nearest living entity, explosion power 4
- 114514 max health, 2048 attack damage, 1024 follow range, 30 armour, 20 toughness
- cycling sky tint during the encounter, plus a colour-cycling status bar
- third-party HUD overlays are blocked while an encounter runs. the vanilla HUD is untouched

## building

Each directory is a normal ForgeGradle project.

```
cd 1.20.1
./gradlew build
```

1.16.5 needs one extra flag, because ForgeGradle 5 in that generation validates a TLS certificate against
`libraries.minecraft.net` that is no longer served:

```
./gradlew build -Dnet.minecraftforge.gradle.check.certs=false
```

The jar lands in `build/libs/`.

## notes on the port

The three builds are not translations of one another in the usual sense. Two things in particular differ by
generation and are worth knowing before reading the source:

- **Entity removal.** On 1.20.1 and 1.19.2 the entity performs its own removal, so the zombie refuses by
  overriding `setRemoved` and `remove`, and the mod has to tear entities out of the level's storages by hand
  to clear them. On 1.16.5 removal is driven by `ServerWorld`, which only reads a flag the entity sets, so
  refusing removal is a matter of never setting the flag, and clearing an entity is a matter of setting it.
  That is why `1.16.5/` carries no reflection helper while the other two do.

- **HUD suppression.** 1.20.1 and 1.19.2 intercept `ForgeGui.render` and redraw the vanilla pass by hand.
  1.16.5 has no overlay registry at all; the equivalent boundary is the pair of private methods on
  `ForgeIngameGui` that raise `RenderGameOverlayEvent`, and silencing those leaves the vanilla drawing in
  place.

## licence

666
