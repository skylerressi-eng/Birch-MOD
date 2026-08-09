# Birch Optimizer

A **Hypixel Skyblock** QOL mod for **Minecraft 26.1.2** on **Fabric**. It shows a
live HUD to help you optimize birch foraging:

- **Birch/hour** — how much birch you're collecting per hour
- **Bazaar price** — live Birch Wood price, refreshed **every 10 minutes**
- **Coins/hour** — birch/hour × current Bazaar price
- **Tree regen timer** — how long until the tree you chopped grows back
- **Leaderboard rank** — your ranking, refreshed **every 10 minutes**

```
Birch Optimizer
Birch/hr: 12,480
BZ Buy: 5.4 coins
Coins/hr: 67,392
Regen: 12.4s
Rank: #142 (Foraging)
```

## Why 26.1.2 / Fabric

Hypixel now enforces a rolling version window on SkyBlock: since **February 24,
2026** only the **two most recent major content updates** are allowed, so 1.8.9
is no longer usable there. Minecraft 26.1 also switched to year-based versioning
(`26` = 2026, `.1` = first drop) and is the **first unobfuscated** release —
Yarn mappings are retired and mods build against official Mojang names.

Modern Skyblock modding is Fabric-based (SkyHanni, Skyblocker, Firmament), so
this mod targets Fabric.

## How each feature works

### Birch/hour
Samples your inventory 4×/second and records **increases** in birch count.
This matters: block-break and pickup events are server-authoritative and never
fire client-side on Hypixel, so inventory deltas are the reliable signal.
Selling or dropping birch decreases the count and is ignored, so the rate only
reflects gathering. Rolling 1-hour window. → `tracking/BirchTracker.java`

### Tree regen timer
Fully client-side and **self-calibrating**:

1. While you look at a birch log, that block position is remembered.
2. When it turns to air, a countdown starts.
3. When it turns back into a log, the **actual** regen duration is measured and
   folded into a running average.

So the timer learns Hypixel's real regen rate instead of trusting a hardcoded
constant. Until it has seen one full cycle it shows `(est)` using
`defaultRegenSeconds`. → `tracking/TreeRegenTracker.java`

### Bazaar price
Polls the **public** Hypixel Bazaar API every 10 minutes — no API key needed.
Tracks `BIRCH_LOG` by default; buy vs sell is configurable.
→ `api/BazaarManager.java`

### Leaderboard rank
Hypixel has no dedicated "birch" leaderboard, so this resolves your UUID and
scans the public leaderboards, reporting the best position found. Needs a
**Hypixel API key** (`/api new` in-game) and your **username**.
→ `api/LeaderboardManager.java`

> Tell me which leaderboard you actually want and I'll target it directly.

## Configuration

Edit `config/birchoptimizer.json` (created on first launch):

| Key | Default | Purpose |
|-----|---------|---------|
| `hudEnabled` | `true` | Toggle the overlay |
| `hudX` / `hudY` | `5` / `5` | HUD position in pixels |
| `showBuyPrice` | `true` | Insta-buy (`true`) or insta-sell (`false`) |
| `bazaarProductId` | `BIRCH_LOG` | Bazaar product to track |
| `regenTimerEnabled` | `true` | Toggle the tree regen timer |
| `defaultRegenSeconds` | `60.0` | Fallback until a real cycle is measured |
| `hypixelApiKey` | *(empty)* | Your Hypixel API key |
| `playerName` | *(empty)* | Your Minecraft username |

## Building

> ⚠️ Minecraft 26.1+ requires a **Java 25** JDK for the Gradle JVM.

```bash
./gradlew build
```

The jar lands in `build/libs/`. Drop it in `.minecraft/mods` alongside
[Fabric API](https://modrinth.com/mod/fabric-api).

**Toolchain:** Fabric Loader `0.19.3` · Fabric API `0.155.2+26.1.2` ·
Loom `1.15` (`net.fabricmc.fabric-loom`) · Gradle `9.4.0`

Because 26.1 is unobfuscated, there is no `mappings` line, dependencies use
plain `implementation` (not `modImplementation`), and `jar` replaces `remapJar`.

## Project layout

```
src/main/java/com/birchmod/
├── BirchMod.java                   # ClientModInitializer entrypoint
├── config/BirchConfig.java         # JSON config
├── hud/BirchHud.java               # HudElement (26.1 render-state pipeline)
├── tracking/BirchTracker.java      # birch/hour via inventory deltas
├── tracking/TreeRegenTracker.java  # self-calibrating regen timer
├── api/BazaarManager.java          # Bazaar price (10-min poll)
├── api/LeaderboardManager.java     # leaderboard rank (10-min poll)
└── util/HttpUtil.java              # HTTP GET helper
```
