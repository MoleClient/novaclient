# NovaClient

A Fabric utility client for Minecraft 1.21.11. Combat assists, movement modules, world scanning and rendering tools, all behind one in-game panel.

Press **Right Shift** in game to open it.

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.19.2 or newer
- Fabric API
- Java 21

## Install

1. Grab the jar from [Releases](../../releases).
2. Drop it in `.minecraft/mods/`.
3. Launch the 1.21.11 Fabric profile.

## Modules

**Combat Modes** Sword, Axe and Mace presets that coordinate aim, triggerbot, reach and hotbar swaps. One action owner per tick, so nothing fires twice.

**Combat** Aim assist, triggerbot, auto mace, auto spear, auto lunge swap, axe stun, axe crit, breach swap, pearl catch, jump reset, velocity, hitbox rendering.

**CrystalPVP** Auto crystal, anchor macro, fast use, auto totem, auto XP.

**Movement** Flight, boat fly, water walker, teleporter, scaffold, freecam, tunnel, auto sprint, auto walk.

**World** Stash pinger, prime chunk finder, storage ESP, hole and tunnel ESP, base tracers, schematic builder.

**Utility** Ping spoofer, ping equalizer, autoclicker, fastbreak, auto tool, fast place, fullbright, nickname, spam.

Any module can be bound to a key from the panel.

## Build

```sh
./gradlew build
```

The jar lands in `build/libs/`.

## License

CC0-1.0
