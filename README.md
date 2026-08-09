# Birch Optimizer

A **Hypixel Skyblock** QOL mod for **Minecraft 1.8.9 (Forge)** that helps you
optimize birch foraging. It shows a live HUD with:

- **Birch/hour** — how many birch logs you're collecting per hour (rolling window)
- **Bazaar price** — live Birch Wood buy/sell price, refreshed **every 10 minutes**
- **Coins/hour** — birch/hour × current Bazaar price
- **Leaderboard rank** — your ranking, refreshed **every 10 minutes**

This is `v0.1` — the foundation. More features coming as you send instructions.

## HUD

The overlay renders in the top-left by default. Each value updates live:

```
Birch Optimizer
Birch/hr: 12,480
BZ Buy: 5.4 coins
Coins/hr: 67,392
Rank: #142 (Foraging)
```

## How each feature works

### Birch/hour
Counts birch logs you pick up (`minecraft:log` meta 2 = "Birch Wood", plus any
item named "Birch"). Uses a rolling 1-hour window; before an hour of play it
extrapolates from your session time. See `tracking/BirchTracker.java`.

### Bazaar price
Polls the **public** Hypixel Bazaar API (`/skyblock/bazaar`) every 10 minutes —
no API key needed. Tracks product id `BIRCH_LOG` by default. Choose buy vs sell
price in the config. See `api/BazaarManager.java`.

### Leaderboard rank
Hypixel has no single "birch" leaderboard, so v1 resolves your UUID and scans
the public Hypixel leaderboards, reporting the best position it finds. This
requires a **Hypixel API key** (`/api new` in-game) and your **username** in the
config. Refreshed every 10 minutes. See `api/LeaderboardManager.java`.

> Tell me exactly which leaderboard you want tracked and I'll point it there.

## Configuration

After first launch, edit `config/birchoptimizer.cfg`:

| Key | Section | Default | Purpose |
|-----|---------|---------|---------|
| `hudEnabled` | hud | `true` | Toggle the overlay |
| `hudX` / `hudY` | hud | `5` / `5` | HUD position (pixels) |
| `showBuyPrice` | bazaar | `true` | Show insta-buy (`true`) or insta-sell (`false`) |
| `bazaarProductId` | bazaar | `BIRCH_LOG` | Bazaar product to track |
| `hypixelApiKey` | leaderboard | *(empty)* | Your Hypixel API key |
| `playerName` | leaderboard | *(empty)* | Your Minecraft username |

## Building

> ⚠️ A 1.8.9 Forge build requires a **Java 8 JDK**. It will not build on Java 17/21.

```bash
JAVA_HOME=/path/to/jdk8 ./gradlew build
```

The compiled mod jar lands in `build/libs/`. Drop it into your `.minecraft/mods`
folder (with Forge 1.8.9 installed).

## Project layout

```
src/main/java/com/birchmod/
├── BirchMod.java              # mod entry point
├── config/BirchConfig.java    # persisted settings
├── hud/BirchHud.java          # overlay rendering
├── tracking/BirchTracker.java # birch/hour
├── api/BazaarManager.java     # Bazaar price (10-min poll)
├── api/LeaderboardManager.java# leaderboard rank (10-min poll)
└── util/HttpUtil.java         # HTTP GET helper
```
