# DonutsAuctions

`DonutsAuctions` is a client-side Fabric mod for DonutSMP that passively tracks Auction House listings (`/ah ...`) and stores them in a local SQLite database for price analysis.

## What It Does
- Passive AH capture while you manually use auction menus.
- Local analytics screen (keybind) for:
  - min / avg / median prices
  - trend graph and raw buckets (`25h`, `7d`, `30d`)
  - seller stats and cheapest listing overview
- Deal scouting based on local historical data.
- Optional exports (CSV/JSON) in dev mode.

## Requirements
- Java: `21`
- Minecraft: `1.21.10`
- Mod loader: Fabric (`fabric-loader >= 0.18.4`)
- Required mod dependencies:
  - `fabric-api >= 0.138.4+1.21.10`
  - `fabric-language-kotlin >= 1.13.9+kotlin.2.3.10`
  - `modmenu >= 12.0.0`
  - `cloth-config2 >= 17.0.0`

## Build / Run
- Run tests:
```bash
./gradlew test --no-daemon
```
- Build remapped jar:
```bash
./gradlew remapJar --no-daemon
```
- Build and copy directly to your local Prism instance mods folder:
```bash
./gradlew runlocal --no-daemon
```

## Runtime Paths
- Config file:
  - `config/donutsauctions.properties`
- Default DB:
  - `config/donutsauctions/auctions.db`
- Default export dir:
  - `config/donutsauctions/exports`

## Controls
- Default hotkey to open analytics GUI: keypad multiply (`KP_MULTIPLY`, remappable in controls).

## Data Policy
- Shulker listings are currently excluded on purpose to avoid distorting per-item analytics.
- Details: `docs/shulker-policy.md`
