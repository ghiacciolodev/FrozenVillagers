<p align="center">
  <img src="docs/icon.png" width="128" alt="">
</p>

<h1 align="center">FrozenVillagers</h1>

<p align="center">
  <a href="https://github.com/ghiacciolodev/FrozenVillagers/releases">GitHub Releases</a>
</p>

A Paper plugin that cuts villager lag without changing how villagers play. Made for Paper 26.2.

## Contents

- [Why it exists](#why-it-exists)
- [What it does](#what-it-does)
- [Installation](#installation)
- [How it works](#how-it-works)
- [Commands](#commands)
- [Configuration](#configuration)
- [Switching from BubbleVillagers or VillagerOptimizer](#switching-from-bubblevillagers-or-villageroptimizer)
- [Benchmark](#benchmark)
- [Compatibility](#compatibility)
- [Building](#building)
- [Testing](#testing)
- [License](#license)

## Why it exists

On a busy survival server villagers are often the most expensive entities. On our server they took about 14% of every tick, more than any other entity type. A profile showed where that time goes:

- **Searching for beds, jobs and bells they can't get.** A villager without a bed, a job or a bell looks for one every 1 to 2 seconds, scanning 48 blocks around it, forever. In a breeder's storage or a crowded village almost every search fails and starts again. This alone was more than a third of the villager cost.
- **The brain, even for villagers that can't move.** A villager locked in a trading hall cell still runs its sensors and decides what to do every tick, and keeps looking for a bed it can never reach.
- **Moving and pushing each other** in crowded chunks.

Turning the AI off fixes the cost but breaks the game: frozen villagers don't restock, don't level up, and if you freeze the wrong ones, iron farms stop making golems and breeders stop breeding. FrozenVillagers only slows down villagers whose AI isn't doing anything useful, and keeps everything else working the vanilla way.

## What it does

| Feature | Who | What changes for players | On by default |
|---|---|---|---|
| [Trapped villagers](#trapped-villagers) | Locked in a 1x1 cell or a vehicle, with a job, traded at least once, no bed | Nothing visible: they couldn't move anyway | Yes |
| [Surplus villagers](#surplus-villagers) | Jobless villagers without a bed, packed in a crowd of others like them | They move in short bursts instead of all the time | Yes |
| [Staff freeze](#staff-freeze) | Any villager staff chooses with `/fv freeze` | The villager stands still | Staff only |
| [Birth limit](#birth-limit) | Breeders with too much surplus around them | No more babies until the surplus goes down | No |

For every villager the plugin freezes or slows down:

- **trading** works as usual, with the same prices, discounts and reputation
- **restocks** happen at fixed times of day when a player opens the trades (see [Restocks](#restocks))
- **level ups** happen the vanilla way, with the new trades (see [Level ups](#level-ups))
- gravity, water, damage, zombie conversion and curing keep working

What the plugin never does:

- never makes villagers invincible, or hides them from zombies
- never removes a villager
- never touches villagers that another plugin changed (AI off, NoAI, item pickup off), unless staff [adopts](#switching-from-bubblevillagers-or-villageroptimizer) them
- never touches NPCs from Citizens and similar plugins, or villagers with the `frozenvillagers_ignore` tag
- never touches villagers with a bed in the automatic features, so iron farms keep working

## Installation

1. Download the jar from the [releases](https://github.com/ghiacciolodev/FrozenVillagers/releases) and put it in the `plugins` folder.
2. Start the server. The defaults work out of the box: trapped and surplus villagers are handled automatically.
3. Optionally freeze your biggest trading halls with `/fv freeze <radius>`, and check what the plugin does with `/fv status` and `/fv inspect`.

If you used BubbleVillagers or VillagerOptimizer before, read [Switching](#switching-from-bubblevillagers-or-villageroptimizer).

## How it works

### Turning the AI off and on

The plugin uses `Mob#setAware(false)`. On Paper it stops the whole AI step: sensors, brain, pathfinding, the bed, job and bell searches, and the level up timer. Gravity, water and damage keep working, and so does trading.

Two things still run every tick with the AI off, and the plugin turns them off too, because neither does anything for a villager that can't move:

- **item pickup**: the villager looks for items on the ground around it every tick
- **pushing**: the villager looks for entities to push every tick

A "slowed down" villager (called *pulsed* in `/fv inspect`) gets its AI back for 1 second every 10 seconds. That is enough to notice a new bed or job, gossip, and react. Some things give it its full AI right away:

- **getting hurt**: full AI for 10 seconds, so it can run away
- **a bed, workstation or bell placed within 16 blocks**: full AI for 30 seconds, so it claims it as fast as in vanilla, for example when you fill a trading hall from a storage of jobless villagers
- **a raid nearby**: nothing is slowed down while it lasts

### Trapped villagers

A villager is trapped when it can't move to any of the four blocks next to it, by walking, falling or jumping one block up. The plugin checks this with the real collision shapes of the blocks, so glass, panes, fences, walls, slabs and workstations all count with their real size. A wall one block high with room above isn't a cell, because the villager can jump over it. A closed wooden door isn't a wall either, because villagers open them. Villagers in boats and minecarts are trapped too.

A trapped villager is only slowed down if, on top of that:

- **it has a profession and traded at least once.** A villager that never traded can still change job, which is how players reroll trades by breaking and replacing the workstation. That needs the full AI, so those are left alone.
- **it has no bed.** Iron farm villagers need a bed and their AI to make golems, so a villager with a bed is never touched.

When a player opens the cell, the villager gets its full AI back within a few seconds.

### Surplus villagers

The surplus is what piles up next to a breeder: babies grow up, there's no bed or job left for them, and they keep searching forever. A villager counts as surplus when it:

- has no profession, or is a nitwit
- is an adult (breeders often rely on babies wandering into the exit, so babies are never slowed down)
- has had no bed for at least 5 minutes
- is in a crowd: at least 12 villagers without a bed and a job in the 3x3 chunks around it

These rules keep the rest of the game safe:

- **iron farms**: their villagers have beds, so they are never surplus
- **natural villages**: only a few villagers lack a bed, so the crowd is never big enough
- **breeders**: the parents have beds and keep breeding. Only the surplus they produce is slowed down

A surplus villager goes back to normal as soon as it finds a bed or a job, or when the crowd drops well below the limit. The limit to wake up is lower than the limit to slow down, so a crowd right at the limit doesn't flip every few seconds.

### Staff freeze

Staff can freeze any villager with `/fv freeze`, wherever it is. Frozen villagers have their AI, item pickup and pushing off. With `freeze.disable-physics` their physics are off too (NoAI): they no longer fall or get pushed by water.

Each villager has one of three modes, saved on the villager:

| Mode | Command | Meaning |
|---|---|---|
| auto | `/fv auto` | The automatic features decide. This is the default |
| frozen | `/fv freeze` | Always frozen, even outside a cell |
| excluded | `/fv unfreeze` | Never touched, even in a cell |

`/fv unfreeze` excludes the villager because otherwise, for a villager in a cell, the automatic features would slow it down again a few seconds later.

### Restocks

Vanilla villagers restock by walking to their workstation during work hours, which a frozen or slowed down villager can't do. So the plugin restocks them when a player opens their trades, once per restock window. By default the windows are at 07:00, 12:00 and 17:00 in game time.

- A click with full trades doesn't spend the window.
- If several windows passed since the last restock, the villager restocks once.
- Setting the time backwards with `/time set` doesn't block restocks.
- `restock.mode: reset-uses` refills the trades and keeps prices as they are. `vanilla` uses the vanilla restock, so trades used a lot get a bit more expensive, like in vanilla.
- With `restock.require-workstation` only villagers whose workstation is still next to them restock.

Villagers the plugin handles only restock at these times, also if their bit of AI would let them restock the vanilla way. Blocking a vanilla restock doesn't change prices either: vanilla updates the demand of every trade even when its restock is blocked, so the plugin remembers the demand and puts it back.

### Level ups

After a trade that earns a level, vanilla waits 40 ticks after the trade screen closes and levels the villager up, in its AI tick. With the AI off that tick never runs.

So when the trade screen of a frozen or slowed down villager closes and it has enough XP for the next level, the plugin turns its AI back on, holds it in place, and lets vanilla do the level up. About 2 seconds later the villager has its new level and trades, and it is frozen again. The plugin never levels a villager up by itself: vanilla keeps an internal flag that would give it a second level later.

### Birth limit

When two villagers are about to have a baby, the plugin counts the villagers without a bed and a job within `births.radius` blocks of them, babies included. If there are `births.max-surplus` or more, the baby isn't born. The count works across chunks, because a breeder usually drops its babies into storage a few chunks away. Iron farm and village villagers have beds, so they don't count.

### Performance

The plugin itself does very little work:

- No scanning of the world. Villagers are added and removed through Paper's entity events.
- Each villager is checked once every 5 seconds, spread over the ticks: with 1,000 villagers that's about 10 checks per tick.
- Each check reads a few values first, and only checks the blocks around a villager when the cheap checks pass.
- The surplus crowds are counted per chunk and updated when a villager moves, dies or changes state, so finding out how crowded an area is takes 9 lookups.
- The pulsed villagers are switched in groups, so only a few change each tick.
- No listener on pathfinding or other events that fire all the time.

`/fv status` shows what the plugin itself costs. In the [benchmark](#benchmark) world, with 1,146 villagers and 842 of them slowed down, it took between 85 and 200 microseconds per tick, about 0.13 ms on average: 0.3% of a tick, against about 32 ms saved.

### What is saved on the villager

The aware flag and item pickup are saved with the villager, so a frozen villager stays frozen after a restart. Next to them the plugin saves markers for what it changed, the staff mode, the last restock and when the villager lost its bed. Pushing is not saved by Minecraft, so the plugin turns it off again whenever the villager loads.

When the plugin is disabled, slowed down villagers get their full AI back right away. Paper disables plugins before it saves the world when the server stops, so this also holds if you stop the server and remove the plugin. Villagers frozen by staff stay frozen: unfreeze them first if you remove the plugin.

## Commands

All commands need `frozenvillagers.admin`, which ops have by default. `/fv` is short for `/frozenvillagers`.

| Command | What it does |
|---|---|
| `/fv freeze [radius\|selector]` | Freezes villagers |
| `/fv unfreeze [radius\|selector]` | Gives them their AI back and excludes them from the automatic features |
| `/fv auto [radius\|selector]` | Puts them back to automatic |
| `/fv adopt [radius\|selector]` | Takes over villagers whose AI another plugin turned off |
| `/fv inspect [selector]` | Shows what the plugin does with a villager and why, its bed, workstation and restock |
| `/fv status` | Counts loaded, frozen and slowed down villagers and villagers another plugin turned off, and shows the plugin's own cost per tick |
| `/fv reload` | Reloads the config |

Without an argument a command works on the villager you are looking at. A number is a radius around you. A selector like `@e[type=villager,distance=..10]` works from the console too.

## Configuration

```yaml
worlds: []
check-interval: 100
freeze:
  disable-physics: false
  max-command-radius: 32
pulse:
  cycle: 200
  active: 20
trapped:
  enabled: true
surplus:
  enabled: true
  crowd: 12
  grace: 6000
  disable-collisions: false
births:
  enabled: false
  max-surplus: 24
  radius: 64
restock:
  enabled: true
  times: [1000, 6000, 11000]
  mode: reset-uses
  require-workstation: false
  workstation-reach: 2.5
  message: ""
```

- `worlds`: worlds where the automatic features run. Empty means all. Staff freezes work everywhere.
- `check-interval`: ticks between two checks of the same villager. At least 20.
- `freeze.disable-physics`: frozen villagers also lose their physics (NoAI). Only for villagers standing on solid ground.
- `freeze.max-command-radius`: the largest radius for `/fv freeze`, `unfreeze`, `auto` and `adopt`.
- `pulse.cycle`, `pulse.active`: slowed down villagers have their AI for `active` ticks every `cycle` ticks.
- `trapped.enabled`: slow down [trapped villagers](#trapped-villagers).
- `surplus.enabled`, `crowd`, `grace`: slow down [surplus villagers](#surplus-villagers), with the crowd size and the ticks without a bed.
- `surplus.disable-collisions`: turn off pushing between surplus villagers. Saves work in big crowds, but entity cramming no longer kills them, so a crowd can grow without limit. Use it together with the birth limit.
- `births`: the [birth limit](#birth-limit).
- `restock.times`: day times of the restock windows, from 0 (06:00) to 23999. 1000 is 07:00, 6000 is 12:00, 11000 is 17:00.
- `restock.mode`: `reset-uses` or `vanilla`, see [Restocks](#restocks).
- `restock.require-workstation`, `workstation-reach`: only restock villagers whose workstation is within this many blocks of their feet.
- `restock.message`: sent to the player after a restock, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html) format. `%next%` is the time until the next restock. Empty sends nothing.

Run `/fv reload` after changing the config. Bad values are reported in the console and replaced by the defaults.

## Switching from BubbleVillagers or VillagerOptimizer

Villagers optimized by those plugins have their AI turned off, and FrozenVillagers never touches villagers another plugin changed. `/fv adopt` hands them over:

1. Put FrozenVillagers in `plugins` and restart. The old plugin can stay installed for now.
2. `/fv status` shows how many loaded villagers have their AI off from the old plugin.
3. Adopt them: `/fv adopt 32` around each trading hall, or `/fv adopt @e[type=villager]` from the console for every loaded villager.
4. Remove the old plugin and restart.

Adopting also removes the marker the old plugin keeps on each villager (BubbleVillagers, VillagerOptimizer and AntiVillagerLag use one). Without that, the old plugin would turn their AI off again the next time they load, as long as it is installed. Adopted villagers are then handled like any other: trapped ones are slowed down and restock and level up again, the others get their AI back. Villagers in unloaded chunks keep the old state until you adopt them when they are loaded.

This was tested on a server with 4,000 villagers optimized by BubbleVillagers: adopting took under a second, the villagers stayed adopted after a restart with both plugins installed, and after BubbleVillagers was removed.

## Benchmark

Measured on a Paper 26.2 test server (build 133) on an AMD Ryzen 5 5600 with 32 GB of RAM, 4 GB for the server. Each result is the average tick time (MSPT) over a full Minecraft day, day and night, after 5.5 minutes of warm-up. Above 50 ms the server can't keep up with 20 ticks per second and starts to lag.

No player was online, so `entity-activation-range.villagers` was set to `0`: every villager runs its full AI, like with players around. Two runs without the plugin gave 50.7 and 49.6 ms, so results vary by about 1 ms between identical runs. How to run it yourself is in [testing/README.md](testing/README.md#benchmark).

### A world like a busy survival server

1,146 villagers: 6 trading halls of 47 villagers in 1x1 cells without beds, 16 storage pens with 35 jobless villagers each, 6 villages with 30 villagers and only 5 beds, 4 normal villages with 25 villagers, and 8 iron farms with 3 villagers each.

| | MSPT | Lowest minute | Highest minute |
|---|---|---|---|
| Without the plugin | **50.7 ms** | 46.2 | 60.5 |
| FrozenVillagers, default config | **18.6 ms** (-63%) | 12.6 | 33.3 |
| FrozenVillagers, trading halls frozen by staff | **13.4 ms** (-74%) | 9.0 | 23.9 |

Villagers with their AI off at the end of the measurement:

| | Trading halls | Storage pens | Crowded villages | Villages | Iron farms |
|---|---|---|---|---|---|
| FrozenVillagers, default config | 256 / 282 | 509 / 560 | 0 / 180 | 0 / 100 | 0 / 24 |
| FrozenVillagers, halls frozen by staff | 282 / 282 | 498 / 560 | 0 / 180 | 0 / 100 | 0 / 24 |

Slowed down villagers get their AI back for 1 second every 10, so some of them are always awake. Villages and iron farms are never touched.

### Only villagers without a job

1,144 villagers: 32 storage pens with 30 jobless adults and nitwits and 5 babies each, like the storage of a breeder, and the same 8 iron farms.

| | MSPT | Lowest minute | Highest minute |
|---|---|---|---|
| Without the plugin | **48.6 ms** | 45.3 | 54.5 |
| FrozenVillagers, default config | **16.3 ms** (-66%) | 15.6 | 20.7 |

FrozenVillagers slowed down 875 of the 960 adults. The 160 babies and the iron farm villagers kept their full AI, so breeders and farms keep working.

### Stress test with a player online

4,000 villagers around spawn, with a player walking among them and the default activation ranges: 10 rows of 100 trading cells (workstation in front, an open block above it to click the villager, glass roof), 40 pens with 50 jobless villagers each, and a field with 1,000 villagers with jobs, free to walk, without beds. The plugin was installed from the start with its automatic features off, then turned on with `/fv reload` in the same session. For this test only, `surplus.grace` was 10 seconds instead of 5 minutes, to see the effect right away.

| | MSPT (1 minute) | TPS |
|---|---|---|
| Automatic features off | **208 ms** | 4.8 |
| Automatic features on | **88 ms** (-58%) | 11.3 |
| Plus the field frozen by staff with `/fv freeze` | **33 ms** (-84%) | **20.0** |
| All 4,000 frozen by staff | **7.2 ms** (-97%) | **20.0** |

Turning the features on took effect within a minute and covered all 1,000 trading cells and the 2,000 villagers in the pens. The 1,000 free villagers in the field kept their full AI until staff froze them. With 4,000 villagers the plugin itself cost about 0.25 ms per tick.

In a trading hall the slowed down villagers still move a little: each one gets its AI for 1 second every 10, and the seconds are spread out, so at any moment about 1 in 10 turns its head or shuffles in its cell. Use `/fv freeze` on a hall to keep it completely still.

## Compatibility

- Paper 26.2 and Java 25. Not tested on Folia, Purpur or Spigot.
- For plugins that open villager trades in their own way, `FrozenVillagers#restockIfDue(Villager)` restocks a villager like a player click does.

## Building

```
./gradlew build
```

The jar is in `build/libs`. Gradle downloads Java 25 if it isn't installed.

## Testing

The plugin has unit tests for the restock and level logic, a functional test suite of 127 checks that runs against a real Paper server, including restarts and removing the plugin, and a benchmark. See [testing/README.md](testing/README.md).

## License

MIT
