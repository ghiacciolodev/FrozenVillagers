"""Performance benchmark for FrozenVillagers, on a separate Paper server.

Builds a world with about as many villagers as a busy survival server, laid
out the way they usually are: trading halls, breeder storage, crowded
villages without enough beds, normal villages and iron farms. Then measures
the server's tick time (MSPT) with and without the plugin.

  python benchmark.py --password <pw> build      on an empty flat world, no plugins
  python benchmark.py --password <pw> measure --label "no plugin"

Set entity-activation-range.villagers to 0 in spigot.yml: no player is
online, and with the default range every villager would count as far from
players, which skips most of its AI and hides the cost. See testing/README.md.
"""
import argparse
import json
import random
import re
import time

from rcon import Rcon, add_arguments

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
add_arguments(parser)
parser.add_argument('action', choices=['build', 'measure', 'freeze-halls'])
parser.add_argument('--layout', choices=['server', 'jobless'], default='server',
                    help='server: like a busy survival server. jobless: only villagers without a job')
parser.add_argument('--label', default='')
parser.add_argument('--warmup', type=int, default=330, help='seconds to wait before measuring')
# A Minecraft day is 20 minutes, so the default covers a full day and night.
parser.add_argument('--samples', type=int, default=20, help='one-minute averages to take')
parser.add_argument('--out', default='benchmark-results.jsonl')
args = parser.parse_args()

rc = Rcon(args.host, args.port, args.password)
random.seed(7)
Y = -60

HALLS, HALL_SIZE = 6, 47
PENS, PEN_SIZE = 16, 35
CROWDED, CROWDED_SIZE, CROWDED_BEDS = 6, 30, 5
VILLAGES, VILLAGE_SIZE = 4, 25
IRON_FARMS = 8

WORKSTATIONS = {
    'armorer': 'blast_furnace', 'butcher': 'smoker', 'cartographer': 'cartography_table',
    'cleric': 'brewing_stand', 'farmer': 'composter', 'fisherman': 'barrel',
    'fletcher': 'fletching_table', 'leatherworker': 'cauldron', 'librarian': 'lectern',
    'mason': 'stonecutter', 'shepherd': 'loom', 'toolsmith': 'smithing_table',
    'weaponsmith': 'grindstone',
}
PROFESSIONS = list(WORKSTATIONS)
LEVEL_XP = {1: 5, 2: 30, 3: 100, 4: 200, 5: 300}


def villager(x, z, profession, level=1, xp=0, tag='bench'):
    rc(f'summon villager {x + 0.5} {Y} {z + 0.5} {{Tags:["{tag}"],PersistenceRequired:1b,CanPickUpLoot:1b,'
       f'VillagerData:{{profession:"minecraft:{profession}",level:{level},type:"minecraft:plains"}},Xp:{xp}}}')


def bed(x, z):
    rc(f'setblock {x} {Y} {z} red_bed[part=foot,facing=east]')
    rc(f'setblock {x + 1} {Y} {z} red_bed[part=head,facing=east]')


def forceload(x1, z1, x2, z2):
    # One command can force load at most 256 chunks.
    for cx in range(x1 >> 4, (x2 >> 4) + 1, 16):
        cx2 = min(cx + 15, x2 >> 4)
        rc(f'forceload add {cx * 16} {z1} {cx2 * 16 + 15} {z2}')


def fence_ring(x1, z1, x2, z2):
    rc(f'fill {x1} {Y} {z1} {x2} {Y} {z2} oak_fence')
    rc(f'fill {x1 + 1} {Y} {z1 + 1} {x2 - 1} {Y} {z2 - 1} air')


def trading_hall(x0, z0, frames):
    """A row of 1x1 glass cells, each with its workstation in front."""
    length = 2 * HALL_SIZE
    rc(f'fill {x0 - 1} {Y - 1} {z0 - 1} {x0 + length} {Y + 2} {z0 + 1} glass')
    for i in range(HALL_SIZE):
        x = x0 + 2 * i
        profession = PROFESSIONS[i % len(PROFESSIONS)]
        rc(f'fill {x} {Y} {z0} {x} {Y + 1} {z0} air')
        rc(f'setblock {x} {Y} {z0 - 1} {WORKSTATIONS[profession]}')
        level = random.randint(1, 5)
        villager(x, z0, profession, level, LEVEL_XP[level], 'hall')
    for i in range(frames):
        x = x0 + i % length
        rc(f'summon item_frame {x} {Y + i // length} {z0 + 2} {{Facing:3b}}')


def pen(x0, z0, babies=False):
    """Breeder storage: jobless villagers without beds, packed in a pen."""
    fence_ring(x0 - 5, z0 - 5, x0 + 5, z0 + 5)
    for i in range(PEN_SIZE):
        x, z = x0 - 4 + i % 9, z0 - 4 + i // 9
        if babies and i % 7 == 6:
            rc(f'summon villager {x + 0.5} {Y} {z + 0.5} {{Tags:["baby"],PersistenceRequired:1b,CanPickUpLoot:1b,'
               f'Age:-2000000,VillagerData:{{profession:"minecraft:none",level:1,type:"minecraft:plains"}}}}')
            continue
        profession = 'nitwit' if i % 5 == 0 else 'none'
        villager(x, z, profession, tag='surplus')


def crowded_village(x0, z0):
    """Villagers with jobs but only a few beds, so most keep looking for one."""
    fence_ring(x0 - 9, z0 - 9, x0 + 9, z0 + 9)
    for i in range(CROWDED_SIZE):
        profession = PROFESSIONS[i % len(PROFESSIONS)]
        rc(f'setblock {x0 - 8 + (i % 6) * 3} {Y} {z0 - 8 + (i // 6) * 3} {WORKSTATIONS[profession]}')
        villager(x0 - 7 + (i % 6) * 3, z0 - 7 + (i // 6) * 3, profession, tag='crowded')
    for i in range(CROWDED_BEDS):
        bed(x0 - 8 + i * 3, z0 + 8)


def village(x0, z0):
    """A normal village: everyone has a bed and a workstation."""
    fence_ring(x0 - 11, z0 - 11, x0 + 11, z0 + 11)
    for i in range(VILLAGE_SIZE):
        profession = PROFESSIONS[i % len(PROFESSIONS)]
        cx, cz = x0 - 10 + (i % 5) * 4, z0 - 10 + (i // 5) * 4
        rc(f'setblock {cx} {Y} {cz} {WORKSTATIONS[profession]}')
        bed(cx + 1, cz + 2)
        villager(cx + 2, cz, profession, tag='village')
    rc(f'setblock {x0} {Y} {z0 + 10} bell[attachment=floor]')


def iron_farm(x0, z0):
    """Three villagers locked in a glass room with their beds."""
    rc(f'fill {x0 - 1} {Y - 1} {z0 - 1} {x0 + 5} {Y + 2} {z0 + 3} glass')
    rc(f'fill {x0} {Y} {z0} {x0 + 4} {Y + 1} {z0 + 2} air')
    for i in range(3):
        bed(x0 + 3, z0 + i)
        villager(x0 + 1, z0 + i, 'none', tag='iron')


def build_jobless():
    """Only villagers without a job: breeder storage with some babies, and iron farms."""
    forceload(-16, 190, 48 * 16, 270)
    forceload(-16, 795, 32 * IRON_FARMS, 805)
    time.sleep(5)
    for p in range(32):
        pen(48 * (p % 16), 200 + 60 * (p // 16), babies=True)
    for f in range(IRON_FARMS):
        iron_farm(32 * f, 800)


def build():
    print(rc('gamerule spawn_monsters false'))
    print(rc('gamerule spawn_mobs false'))
    rc('kill @e[type=!player]')
    if args.layout == 'jobless':
        build_jobless()
        time.sleep(2)
        print(rc('execute if entity @e[type=villager]'))
        return
    forceload(-16, -16, 128 * HALLS, 16)
    forceload(-16, 190, 48 * PENS, 210)
    forceload(-16, 385, 64 * CROWDED, 415)
    forceload(-16, 585, 64 * VILLAGES, 615)
    forceload(-16, 795, 32 * IRON_FARMS, 805)
    time.sleep(5)
    for h in range(HALLS):
        trading_hall(128 * h, 0, 73 if h == 0 else 0)
    for p in range(PENS):
        pen(48 * p, 200)
    for c in range(CROWDED):
        crowded_village(64 * c, 400)
    for v in range(VILLAGES):
        village(64 * v, 600)
    for f in range(IRON_FARMS):
        iron_farm(32 * f, 800)
    time.sleep(2)
    print(rc('execute if entity @e[type=villager]'))


def mspt():
    """The 1 minute average of the server tick time, in milliseconds."""
    out = rc('mspt')
    numbers = re.findall(r'(\d+(?:[.,]\d+)?)/(\d+(?:[.,]\d+)?)/(\d+(?:[.,]\d+)?)', out)
    if len(numbers) < 3:
        raise SystemExit('unexpected /mspt output: ' + out)
    return float(numbers[2][0].replace(',', '.'))


GROUPS = ['hall', 'surplus', 'baby', 'crowded', 'village', 'iron']


def count(selector):
    match = re.search(r'Count: (\d+)', rc(f'execute if entity {selector}'))
    return int(match.group(1)) if match else 0


def ai_off_by_group():
    """How many villagers of each group have their AI off right now, out of how many."""
    result = {}
    for group in GROUPS:
        off = count('@e[type=villager,tag=' + group + ',nbt={"Bukkit.Aware":0b}]')
        total = count('@e[type=villager,tag=' + group + ']')
        result[group] = f'{off}/{total}'
    return result


def measure():
    print(f'warming up {args.warmup}s', flush=True)
    time.sleep(args.warmup)
    samples = []
    for i in range(args.samples):
        time.sleep(60)
        samples.append(mspt())
        print(f'  {args.label}: 1m average {samples[-1]:.2f} ms', flush=True)
    result = {'label': args.label, 'mspt': round(sum(samples) / len(samples), 2),
              'min': min(samples), 'max': max(samples), 'samples': samples,
              'villagers': count('@e[type=villager]'), 'ai_off': ai_off_by_group()}
    print(json.dumps(result), flush=True)
    with open(args.out, 'a', encoding='utf-8') as f:
        f.write(json.dumps(result) + '\n')


if args.action == 'build':
    build()
elif args.action == 'freeze-halls':
    print(rc('fv freeze @e[type=villager,tag=hall]'))
else:
    measure()
