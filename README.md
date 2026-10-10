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
