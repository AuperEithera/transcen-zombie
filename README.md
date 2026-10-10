# Transcendence Zombie

![1.20.1](https://img.shields.io/badge/minecraft-1.20.1-62b47a?style=flat-square)
![1.19.2](https://img.shields.io/badge/minecraft-1.19.2-62b47a?style=flat-square)
![1.16.5](https://img.shields.io/badge/minecraft-1.16.5-62b47a?style=flat-square)
![1.12.2](https://img.shields.io/badge/minecraft-1.12.2-62b47a?style=flat-square)
![Forge](https://img.shields.io/badge/forge-14.23.5.2860%20%7C%2036.2.34%20%7C%2043.3.0%20%7C%2047.4.26-e8a33d?style=flat-square)
![Java](https://img.shields.io/badge/java-8%20%7C%2017-orange?style=flat-square)
![License](https://img.shields.io/badge/license-666-blue?style=flat-square)
![release](https://img.shields.io/github/v/release/AuperEithera/transcen-zombie?style=flat-square&color=red)

A Minecraft mod that adds a single, extremely persistent hostile zombie. Four builds live side by side in
this repository, one per Minecraft generation. They are separate Gradle projects, not a multi-module build,
because the API surface differs enough between generations that sharing sources would cost more than it
saves.

| directory | minecraft | forge | java | injections |
| --- | --- | --- | --- | --- |
| `1.20.1/` | 1.20.1 | 47.4.26 | 17 | Sponge Mixin |
| `1.19.2/` | 1.19.2 | 43.3.0 | 17 | Sponge Mixin |
| `1.16.5/` | 1.16.5 | 36.2.34 | 8 | Sponge Mixin |
| `1.12.2/` | 1.12.2 | 14.23.5.2860 | 8 | coremod (`IClassTransformer`) |

The 1.12.2 build is the odd one out, and two of its differences are worth knowing before reading its
sources, because both fail in ways a compile cannot see:

- **No Mixin.** That generation predates Sponge Mixin being available to ordinary mods, so the two places
  the other builds reach with `@Inject` are done with a Forge `IClassTransformer` instead. It is
  self-contained -- no MixinBootstrap, no separate coremod jar. `gradlew verifyTransformer` in that
  directory is the check that the injected bytecode both verifies and takes effect.
- **`mcmod.info` must be a JSON array, and the access transformer must be named in the manifest.** An
  object-form `mcmod.info` makes FML's `MetadataCollection.parseModMetadataList` iterate a null `modList`;
  a missing `FMLAT` manifest attribute makes Forge silently skip the access transformer. Both build and
  compile perfectly, and both were caught only by launching the built jar against a real Forge server.


## Note

- carry the spawn egg at all times to avoid being insta-killed, even in creative mode
- the zombie cannot be removed by ordinary methods, including most removal path other mods use
- left click with the egg held disarms the encounter and clears the zombies, right click re-arms it
- third-party HUD overlays are blocked while an encounter runs.
- note that the 1.16.5 version is weaker
