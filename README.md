# Birch Optimizer

A **Hypixel Skyblock** QOL mod for **Minecraft 26.1.2** on **Fabric**, built to
optimize birch foraging. Everything tracks itself — there is nothing to start,
stop, or reset by hand.

A 26.2 build ships too — see [`dist/`](dist/) for both jars and which one to
take.

```
Birch Optimizer
Birch/hr: 12,480
BZ Buy: 5.4
Coins/hr net: 66,550
Regen: 12.4s (3 trees)
Session: 4,203 birch / 61 trees
Earned: 22,410 in 34m 12s
Collection: #142 (Birch Collection)
Tier 7/9: 1,520 to go (14m)
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
| **Birch/hour** | Measured over time spent foraging, not time logged in |
| **Tax-aware coins/hour** | Real income after Hypixel's Bazaar cut |
| **Best payout routing** | Compares raw vs. enchanted birch per log, and says which |
| **Collection tier** | Which tier, how much is left, and how long that will take |
| **Automatic tree timers** | Every tree in range tracked, no setup |
| **Self-calibrating regen** | Measures actual regrowth instead of guessing |
| **Ready alerts** | Action-bar + sound when a tree comes back |
| **Leftover nudge** | Tells you when you walk off a trunk you did not finish |
| **Stats screen** | Everything the mod measures, in one place, live |
| **Route recording** | Records the loop you walk, by the trees you fell |
| **Lap timer** | This lap against your best, per route |
| **Route splits** | Hop by hop: which leg is actually costing you the lap |
| **Route sharing** | A route as a code or a file, for sending to somebody |
| **Session & lifetime stats** | Persisted across restarts |
| **Collection rank** | Captured when you open a collection leaderboard |
| **Skyblock detection** | Overlay stays hidden everywhere else |
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

## What it measures about you

Two numbers underneath every estimate the mod makes are measured from play
rather than configured, and both persist between sessions:

- **Birch regrowth.** Timed from trees actually coming back, so the countdown
  calibrates to Hypixel's real rate instead of a guess. Marked `(est)` until a
  cycle has been watched.
- **Travel speed.** A Skyblock speed stat moves you several times faster than
  vanilla sprinting. Assuming a constant made every ETA pessimistic and made
  the optimiser pick fewer trees than it should.

Both are on the **Stats** tab, along with the session and lifetime totals, your
lap times, which form of birch currently pays best, and how many legs of your
route have been timed. Nothing on that screen is a setting — it is all
measurement, which is what makes it the place to check whether changing a
setting helped.

## Collection tier

Hypixel serves the collection ladder as static game data, so the tiers need no
API key. Your position on it does — the total comes from your profile — but the
rungs and what they unlock are visible either way.

With both, the HUD can answer the question a rate alone cannot:

```
Tier 7/9: 1,520 to go (14m)
```

Birch Log has nine tiers, topping out at 25,000. The same display works for any
wood the Bazaar product is pointed at, because the ladder is looked up by that
id rather than hard-coded to birch.

## Route splits

A lap timer says a loop came in eight seconds off its best. That is worth
knowing and it is not actionable — eight seconds are missing and it does not say
where from.

`/route splits` compares each hop against how long it should take, two ways: the
straight-line distance over your measured travel speed, and the time that hop
has really taken across every session. Where the two disagree, the ground does
not match the map.

```
Slowest: stop 4 → 5 costs +8.2s (19.0 blocks in 26.1s)
```

Ranked by **seconds lost, not by ratio**. A two-block step between neighbouring
trees always reads as several times slower than predicted, because the
prediction is a fraction of a second and the real thing includes turning to face
the tree; a trek across a gully reads as only 44% slow and costs eight seconds.
Seconds are what a lap is made of, so seconds decide.

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
Loom `1.15-SNAPSHOT` (`net.fabricmc.fabric-loom`) · Gradle `9.4.0` · Java 25

The 26.2 line needs a newer toolchain — Loom `1.18.2`, which in turn wants
Gradle `9.8.0` — and is kept on the `mc26.2-line` tag.

Because 26.1 is unobfuscated, there is no `mappings` line, dependencies use
plain `implementation` (not `modImplementation`), and `jar` replaces `remapJar`.

## Why 26.1 / Fabric

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
│
├── api/BazaarManager.java              # tax-aware multi-product pricing
├── api/CollectionApi.java              # birch collection total (10-min poll)
├── api/CollectionTiers.java            # the collection ladder (no key needed)
│
├── command/BirchCommand.java           # /birch …
├── command/RouteCommand.java           # /route …
├── command/TimerCommand.java           # /timer …
├── command/Help.java                   # the command reference, in the game
│
├── config/BirchConfig.java             # JSON config, migrated and clamped
│
├── gui/BirchScreen.java                # settings, scrolling + searchable
├── gui/StatsScreen.java                # everything measured, on one screen
├── gui/Chrome.java                     # the frame every screen shares
├── gui/OverlayTab.java                 # \
├── gui/TreesTab.java                   #  } the four settings tabs
├── gui/RouteTab.java                   #  |
├── gui/AlertsTab.java                  # /
├── gui/OptionSlider.java               # numeric setting as a slider
├── gui/RoutesScreen.java               # saved routes: pick, rename, delete
├── gui/RouteListWidget.java            # one clickable row per route
├── gui/ImportScreen.java               # paste a route somebody sent you
│
├── hud/BirchHud.java                   # overlay (26.1 render-state pipeline)
├── input/Keybinds.java                 # rebindable shortcuts
│
├── render/TracerRenderer.java          # tracers + green centre highlights
├── render/TreeTimerRenderer.java       # floating in-world countdowns
├── render/VertexWriter.java            # writes whatever format the pipeline wants
│
├── route/RouteRecorder.java            # records a loop from the trees you fell
├── route/RecordedRoute.java            # a recorded loop
├── route/RouteLibrary.java             # saved loops, and how they score
├── route/RouteFollower.java            # walks a loop in recorded order
├── route/Advance.java                  # when the green marker moves on
├── route/Splits.java                   # which hop is costing you the lap
├── route/TreeSight.java                # resolves a recorded point to a log
├── route/Stop.java                     # one stop, as renderers see it
├── route/FreePlanner.java              # planning with no recorded route
├── route/RouteBuilder.java             # time-cost route planner
├── route/RouteOptimizer.java           # compiles recordings into one loop
├── route/TravelGraph.java              # measured travel times, persisted
├── route/LapTracker.java               # how long a lap actually takes
├── route/RouteCodec.java               # route ⇄ shareable string
├── route/RouteFiles.java               # route ⇄ file on disk
│
├── stats/SessionStats.java             # session + persisted lifetime stats
│
├── tracking/BirchTracker.java          # birch/hour over foraging time
├── tracking/TreeRegenTracker.java      # automatic per-tree regen timing
├── tracking/MarkerChoice.java          # which log a marker lands on
├── tracking/MovementTracker.java       # how fast you actually travel
├── tracking/LeftoverWatch.java         # warns on a trunk you left standing
├── tracking/CollectionRankTracker.java # reads rank off the collections screen
│
├── util/HttpUtil.java                  # HTTP GET with retry/backoff
├── util/SafeFile.java                  # atomic writes, with a fallback copy
├── util/Guard.java                     # fail-safe wrapper around entry points
├── util/Notifier.java                  # action-bar + sound alerts
└── util/SkyblockDetector.java          # Hypixel / Skyblock detection
```
