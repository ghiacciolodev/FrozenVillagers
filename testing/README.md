# Testing

FrozenVillagers is tested in three ways:

- **Unit tests** for the logic that doesn't need a server: the restock times and the level thresholds. They run with `./gradlew build`.
- **A functional test suite**, `suite.py`, that sends commands to a real Paper server through RCON and checks what happens to real villagers.
- **A benchmark**, `benchmark.py`, that measures the server tick time with about a thousand villagers, with and without the plugin.

Never run the suite or the benchmark on a server with players. They build structures, summon and kill villagers, change the time and rewrite the plugin's config.

## What's here

- `suite.py`: the functional tests, grouped by feature:
  - trapped villagers: 1x1 cells, fences, walls they can jump, wooden doors, minecarts, beds, villagers that never traded, the ignore tag, NPCs, opening and closing a cell
  - hurt villagers waking up
  - villagers changed by other plugins: AI off, NoAI, item pickup off, and `/fv adopt`
  - staff commands: freeze, unfreeze, auto, physics, bad selectors
  - restocks: the times, once per window, missed windows, `/time set` backwards, full trades, both modes, the workstation option, vanilla restocks and the dawn catch-up blocked without changing prices, normal villagers untouched
  - level ups: pulsed, frozen and frozen without physics villagers, the new trades, no extra level afterwards, XP without a planned level up, master villagers
  - surplus: grace time, crowd size and hysteresis, beds, jobs, babies, nitwits, collisions, hurt villagers, waking up when a workstation is placed nearby
  - the birth limit: storage in other chunks, the radius, babies counting
  - config: world filter, warnings for bad values
  - chunk reloads, disabling the plugin, restarts, removing the plugin, and the plugin's own cost in `/fv status`
- `benchmark.py`: builds the benchmark world and measures it.
- `helper/`: a small plugin used only by the tests. Commands can't see the aware or collidable flags, set brain memories, make a villager earn XP the way a real trade does, or place a block the way a player does, so the helper does that. Never install it on a real server.
- `rcon.py`: the RCON client shared by the scripts.

## Functional tests

1. Use a fresh Paper 26.2 server with a flat world. In `server.properties`:

   ```
   level-type=minecraft\:flat
   enable-rcon=true
   rcon.password=<something>
   ```

2. Build the plugin and the helper from the root of the repository:

   ```
   ./gradlew build
   ./gradlew -p testing/helper build
   ```

3. Put `build/libs/FrozenVillagers-<version>.jar` and `testing/helper/build/libs/FvTest.jar` in the server's `plugins` folder and start it.

4. Run the suite, restart the server, and run the checks that follow a restart:

   ```
   python suite.py --password <rcon password> --config <server>/plugins/FrozenVillagers/config.yml
   python suite.py --password <rcon password> --config <server>/plugins/FrozenVillagers/config.yml --after-restart
   ```

   `--only <name>` runs only the groups whose name contains it, for example `--only restock`.

5. To check what stays on the villagers when the plugin is removed: stop the server, take the plugin and the helper out of `plugins`, start it again and run the checks below. They only use vanilla commands. Then put both jars back.

   ```
   python suite.py --password <rcon password> --config <server>/plugins/FrozenVillagers/config.yml --after-removal
   ```

The scripts need Python 3.10 or newer, with no extra packages.

## Benchmark

The benchmark world has 1,146 villagers, laid out like on a busy survival server:

| Group | Villagers | What they are |
|---|---|---|
| 6 trading halls | 282 | 1x1 glass cells with the workstation in front, no beds. One hall has 73 item frames |
| 16 storage pens | 560 | Jobless villagers and nitwits without beds, 35 per pen, like the output of a breeder |
| 6 crowded villages | 180 | Villagers with jobs but only 5 beds for 30 of them |
| 4 villages | 100 | Everyone has a bed and a workstation, with a bell |
| 8 iron farms | 24 | 3 villagers locked in a room with their beds |

`--layout jobless` builds a second world with only villagers without a job: 32 storage pens of 35 (adults, nitwits and 5 babies each, 1,120 in total) and the 8 iron farms. It shows what the plugin does for breeder storage alone, and that iron farm villagers, which have beds, are left alone.

Use a separate server, not the one for the functional tests, so their villagers don't get in the way:

1. A fresh Paper 26.2 server with a flat world and RCON, without any plugin.
2. In `spigot.yml`, set `entity-activation-range.villagers` to `0`. No player is online during the benchmark, and with the default range every villager would count as far from players, which skips most of its AI. With `0` every villager runs its full AI, like with players nearby.
3. Start the server and build the world:

   ```
   python benchmark.py --password <pw> build
   ```

4. Let it run for a few minutes so the villagers claim their beds and workstations, then stop the server and keep a copy of the world. Every scenario starts from that copy.
5. For each scenario: restore the world, put the plugin (or none) in `plugins`, start the server and measure:

   ```
   python benchmark.py --password <pw> measure --label "no plugin"
   python benchmark.py --password <pw> measure --label "FrozenVillagers"
   ```

   The warm-up is 5.5 minutes for every scenario: longer than the 5 minutes of `surplus.grace`, like on a server that has been running for a while, and the same for all of them so they measure the same part of the day. `freeze-halls` freezes the trading halls like staff would with `/fv freeze`.

Each result is the average of 20 one-minute averages of `/mspt`, a full Minecraft day with its night, when villagers all look for a bed at once. It is appended to `benchmark-results.jsonl` with the number of villagers of each group that have their AI off at the end. Run the no plugin scenario twice to see how much results vary between identical runs.
