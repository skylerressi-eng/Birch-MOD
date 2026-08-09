# Birch Optimizer

A **Hypixel Skyblock** QOL mod for **Minecraft 26.1.2** on **Fabric**, built to
optimize birch foraging. Everything tracks itself — there is nothing to start,
stop, or reset by hand.

```
Birch Optimizer
Birch/hr: 12,480
BZ Buy: 5.4
Coins/hr net: 66,550
Regen: 12.4s (3 trees)
Session: 4,203 birch / 61 trees
Earned: 22,410 in 34m 12s
Collection: #142 (Birch Collection)
```

In the world, every downed tree carries its own countdown:

```
      18.2s          READY
        |              |
   [stump]         [stump]
```

## Features

| | |
|---|---|
| **Birch/hour** | Rolling 1-hour rate from inventory deltas |
| **Tax-aware coins/hour** | Real income after Hypixel's Bazaar cut |
| **Best payout routing** | Compares raw vs. enchanted birch per log |
| **Automatic tree timers** | Every tree in range tracked, no setup |
| **Self-calibrating regen** | Measures actual regrowth instead of guessing |
| **Ready alerts** | Action-bar + sound when a tree comes back |
| **Session & lifetime stats** | Persisted across restarts |
| **Collection rank** | Captured when you open a collection leaderboard |
| **Skyblock detection** | Overlay stays hidden everywhere else |
| **Route planning** | Orders trees by fastest payoff, not just distance |
| **Tracers & highlights** | Green box on each tree's centre, lines pointing to it |

## Automatic tracking

The timer requires no interaction at all:

1. **Discovery** — every 2 seconds the mod sweeps a 16-block radius for birch
   trunk bases (a birch log with a non-birch block beneath it). You never have
   to aim at a tree to register it.
2. **Chop detection** — a tree's clock starts only when it is **fully downed**:
   every log in its trunk volume gone, not just the first one you broke.
3. **Measurement** — when logs reappear, the true regen duration is recorded.

Because durations are measured rather than assumed, the countdown calibrates to
Hypixel's real rate. `/timer` reports mean, fastest, slowest and sample count.
Until the first cycle completes, the HUD marks the figure `(est)`.

Config flags only ever affect **display** — measurement always runs.

## Route planning

Built for a grove like **the Park**, where trees regrow on a clock and the
optimal loop is a cycle rather than a straight line.

The route is a greedy nearest-first walk, but "nearest" is measured in **time,
not distance**. For each candidate tree the cost is:

```
max(travel time to the tree, its remaining regen)
```

You gain nothing by arriving early, so a tree two seconds further away that is
**ready now** beats a closer tree with twenty seconds left on its clock. That
one rule makes the route degrade gracefully in both directions: when everything
is standing it becomes plain "nearest tree", and when everything is chopped it
becomes "whichever comes back soonest".

### Tracers and highlights

Each routed tree gets a **wireframe box around its centre block** — the trunk
middle, not its feet, so the marker sits inside the tree. Tracers **ping off
that same block**, so line and marker always agree on where the tree is.

| Colour | Meaning |
|--------|---------|
| **Green** | Next stop, ready to chop now |
| **Amber** | Next stop, still regrowing |
| **Blue** | Later stops, chained in order |

The first tracer runs from just below your eye level to the next tree; chained
tracers then hop centre-block to centre-block through the rest of the route.
Tune the centre height with `/route center <0-12>` if Park birches sit taller
or shorter than expected.

## Commands

### `/birch`
| Command | Effect |
|---------|--------|
| `/birch` | Overview: rate, best payout, trees, Skyblock status |
| `/birch stats` | Session + lifetime totals |
| `/birch bazaar` | Live prices, spreads, tax, last refresh |
| `/birch reset` | Clear session counters |
| `/birch hud <true\|false>` | Toggle overlay |
| `/birch hud pos <x> <y>` | Move overlay |
| `/birch hud scale <0.5-3.0>` | Resize overlay |
| `/birch hud bg <true\|false>` | Overlay backdrop |
| `/birch notify <true\|false>` | Ready alerts |
| `/birch notify sound <true\|false>` | Alert sound |
| `/birch tax <true\|false>` | Bazaar tax in projections |
| `/birch skyblockonly <true\|false>` | Hide outside Skyblock |
| `/birch apikey <key>` | Set Hypixel API key |
| `/birch name <username>` | Set your username |

### `/timer`
| Command | Effect |
|---------|--------|
| `/timer` | Full regen statistics |
| `/timer mode` | Toggle floating in-world labels |
| `/timer reset` | Clear tracked trees and measurements |

### `/route`
| Command | Effect |
|---------|--------|
| `/route` | List the planned route with ETAs |
| `/route <true\|false>` | Toggle the route overlay |
| `/route tracers <true\|false>` | Toggle tracer lines |
| `/route chain <true\|false>` | Toggle chained tracers |
| `/route length <1-16>` | How many stops to plan ahead |
| `/route center <0-12>` | Blocks above base treated as tree centre |

### Keybinds
Rebindable under Controls → Birch Optimizer.

| Key | Action |
|-----|--------|
| `B` | Toggle HUD |
| `N` | Toggle tree timers |
| *(unbound)* | Reset session |

## How each feature works

### Birch/hour
Samples your inventory 4×/second and records **increases** in birch count.
This matters: block-break and pickup events are server-authoritative and never
fire client-side on Hypixel, so inventory deltas are the reliable signal.
Selling or dropping birch decreases the count and is ignored, so the rate only
reflects gathering. → `tracking/BirchTracker.java`

### Bazaar pricing
Polls the **public** Bazaar API every 10 minutes — no API key needed. Prices
both `BIRCH_LOG` and `ENCHANTED_BIRCH_LOG`, normalises to coins-per-log
(1 enchanted = 160 logs), and picks the better payout. Coin projections apply
Hypixel's Bazaar tax by default, since gross prices overstate real income.
Requests retry with exponential backoff. → `api/BazaarManager.java`

### Session & lifetime stats
Session totals plus lifetime totals persisted to
`config/birchoptimizer-stats.json`, saved on a 30-second throttle and on exit.
"Active time" only accrues while you are actually gathering, so an AFK stretch
does not deflate your averages. → `stats/SessionStats.java`

### Collection leaderboard rank
When you open a Skyblock collection leaderboard, the mod reads it and notes
your position. Skyblock fills those chest GUIs by packet *after* the screen
opens, so the scan polls the open screen rather than reading it once on init.
→ `tracking/CollectionRankTracker.java`

### Skyblock detection
Server address identifies Hypixel; the scoreboard sidebar title identifies
Skyblock. The overlay hides elsewhere unless you turn that off.
→ `util/SkyblockDetector.java`

## Configuration

Everything is settable in-game via `/birch`. The backing file is
`config/birchoptimizer.json`; values are clamped to sane ranges on load.

## Building

> ⚠️ Minecraft 26.1+ requires a **Java 25** JDK for the Gradle JVM.

```bash
./gradlew build
```

The jar lands in `build/libs/`. Drop it in `.minecraft/mods` alongside
[Fabric API](https://modrinth.com/mod/fabric-api).

**Toolchain:** Fabric Loader `0.19.3` · Fabric API `0.155.2+26.1.2` ·
Loom `1.15.5` (`net.fabricmc.fabric-loom`) · Gradle `9.4.0` · Java 25

Because 26.1 is unobfuscated, there is no `mappings` line, dependencies use
plain `implementation` (not `modImplementation`), and `jar` replaces `remapJar`.

## Why 26.1.2 / Fabric

Hypixel enforces a rolling version window on SkyBlock: since **February 24,
2026** only the **two most recent major content updates** are allowed, so 1.8.9
is no longer usable there. Minecraft 26.1 also switched to year-based versioning
(`26` = 2026, `.1` = first drop) and is the **first unobfuscated** release —
Yarn mappings are retired and mods build against official Mojang names.

## Notable 26.1 APIs used

Confirmed against the real 26.1.2 / Fabric API 0.155.2 jars:

| Purpose | API |
|---------|-----|
| HUD | `HudElementRegistry` + `HudElement.extractRenderState` |
| World render | `LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES` (was `WorldRenderEvents`) |
| Text (screen) | `GuiGraphicsExtractor.text(font, s, x, y, argb, shadow)` |
| Text (world) | `Font.drawInBatch(..., Font.DisplayMode.SEE_THROUGH, ...)` |
| Lines | `RenderTypes.lines()` (moved to `renderer.rendertype`) |
| Commands | `ClientCommandRegistrationCallback` + `ClientCommands.literal` |
| Keybinds | `KeyMappingHelper` + `KeyMapping.Category.register` |
| Chat | `Gui.getChat().addClientSystemMessage` |
| Identifiers | `Identifier.fromNamespaceAndPath` |

## Project layout

```
src/main/java/com/birchmod/
├── BirchMod.java                       # ClientModInitializer entrypoint
├── api/BazaarManager.java              # tax-aware multi-product pricing
├── api/LeaderboardManager.java         # leaderboard rank (10-min poll)
├── command/BirchCommand.java           # /birch …
├── command/RouteCommand.java           # /route …
├── command/TimerCommand.java           # /timer …
├── config/BirchConfig.java             # JSON config, clamped on load
├── hud/BirchHud.java                   # overlay (26.1 render-state pipeline)
├── input/Keybinds.java                 # rebindable shortcuts
├── render/TracerRenderer.java          # tracers + green centre highlights
├── render/TreeTimerRenderer.java       # floating in-world countdowns
├── route/RouteBuilder.java             # time-cost route planner
├── stats/SessionStats.java             # session + persisted lifetime stats
├── tracking/BirchTracker.java          # birch/hour via inventory deltas
├── tracking/TreeRegenTracker.java      # automatic per-tree regen timing
├── tracking/CollectionRankTracker.java # collection leaderboard scanner
├── util/HttpUtil.java                  # HTTP GET with retry/backoff
├── util/Notifier.java                  # action-bar + sound alerts
└── util/SkyblockDetector.java          # Hypixel / Skyblock detection
```
