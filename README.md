# Birch Optimizer

A **Hypixel Skyblock** QOL mod for **Minecraft 26.1.2** on **Fabric**. It shows a
live HUD to help you optimize birch foraging:

- **Birch/hour** — how much birch you're collecting per hour
- **Bazaar price** — live Birch Wood price, refreshed **every 10 minutes**
- **Coins/hour** — birch/hour × current Bazaar price
- **Per-tree regen timers** — a countdown floating above every downed tree
- **Collection rank** — noted automatically when you open a collection leaderboard
- **Leaderboard rank** — your ranking, refreshed **every 10 minutes**

```
Birch Optimizer
Birch/hr: 12,480
BZ Buy: 5.4 coins
Coins/hr: 67,392
Regen: 12.4s (3 trees)
Collection: #142 (Birch Collection)
```

In the world, each downed tree carries its own floating countdown:

```
      18.2s          READY
        |              |
   [stump]         [stump]
```

## Commands

| Command | Effect |
|---------|--------|
| `/timer mode` | Toggle the floating in-world tree timers |
| `/timer reset` | Forget tracked trees and measurements |
| `/timer` | Show status: tracked trees, measured regen time |

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

### Per-tree regen timers
Fully client-side and **self-calibrating**. Each tree is tracked separately:

1. Looking at a birch log registers that tree, keyed by its **base log** (the
   trunk is walked down to its lowest connected log).
2. The countdown starts only when the tree is **fully downed** — every log in
   its trunk volume is gone, not just the first one you broke.
3. When logs reappear, the **actual** regen duration is measured and folded
   into a running average.

So the timer learns Hypixel's real regen rate instead of trusting a hardcoded
constant. Until it has seen one full cycle it shows `(est)` using
`defaultRegenSeconds`.
→ `tracking/TreeRegenTracker.java`, `render/TreeTimerRenderer.java`

The floating label is billboarded (always faces you) and drawn see-through so
it stays readable through terrain. Toggle with `/timer mode`.

### Collection leaderboard rank
When you open a Skyblock collection leaderboard, the mod reads it and notes
your position. Skyblock fills those chest GUIs by packet *after* the screen
opens, so the scan polls the open screen rather than reading it once on init.
A line counts as yours when it names you or says "your rank"; the position is
parsed from it and shown on the HUD. → `tracking/CollectionRankTracker.java`

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
| `regenTimerEnabled` | `true` | Toggle all regen tracking |
| `worldTimersEnabled` | `true` | Floating in-world timers (`/timer mode`) |
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
├── BirchMod.java                       # ClientModInitializer entrypoint
├── command/TimerCommand.java           # /timer mode | reset | status
├── config/BirchConfig.java             # JSON config
├── hud/BirchHud.java                   # HudElement (26.1 render-state pipeline)
├── render/TreeTimerRenderer.java       # floating in-world tree countdowns
├── tracking/BirchTracker.java          # birch/hour via inventory deltas
├── tracking/TreeRegenTracker.java      # per-tree self-calibrating regen timer
├── tracking/CollectionRankTracker.java # collection leaderboard scanner
├── api/BazaarManager.java              # Bazaar price (10-min poll)
├── api/LeaderboardManager.java         # leaderboard rank (10-min poll)
└── util/HttpUtil.java                  # HTTP GET helper
```

## Notable 26.1 APIs used

Confirmed against the real 26.1.2 / Fabric API 0.155.2 jars:

| Purpose | API |
|---------|-----|
| HUD | `HudElementRegistry` + `HudElement.extractRenderState` |
| World render | `LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES` (was `WorldRenderEvents`) |
| Text (screen) | `GuiGraphicsExtractor.text(font, s, x, y, argb, shadow)` |
| Text (world) | `Font.drawInBatch(..., Font.DisplayMode.SEE_THROUGH, ...)` |
| Commands | `ClientCommandRegistrationCallback` + `ClientCommands.literal` |
| Identifiers | `Identifier.fromNamespaceAndPath` |
