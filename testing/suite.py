"""Functional tests for FrozenVillagers, run against a live Paper test server.

Needs a flat world (surface at y -60), RCON enabled, the plugin and the FvTest
helper plugin installed. See testing/README.md. It builds structures,
summons and kills villagers, changes the time and rewrites the plugin's
config, so never run it on a server with players.

Run it once, restart the server, then run it again with --after-restart.
"""
import argparse
import re
import time

from rcon import Rcon, add_arguments

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
add_arguments(parser)
parser.add_argument('--config', required=True, help='path to plugins/FrozenVillagers/config.yml on the test server')
parser.add_argument('--after-restart', action='store_true', help='run the checks that follow a restart')
parser.add_argument('--after-removal', action='store_true',
                    help='run after stopping the server, removing the plugin and the helper, and starting it again')
parser.add_argument('--only', help='run only the groups whose name contains this')
args = parser.parse_args()

rc = Rcon(args.host, args.port, args.password)
results = []

Y = -60  # feet height on the default flat world

# Fast values so the tests don't wait long: every villager is checked every
# second and pulsed villagers are awake 8 ticks out of every 40.
DEFAULTS = {
    'worlds': '[]',
    'check-interval': 20,
    'freeze': {'disable-physics': 'false', 'max-command-radius': 32},
    'pulse': {'cycle': 40, 'active': 8},
    'trapped': {'enabled': 'true'},
    'surplus': {'enabled': 'true', 'crowd': 12, 'grace': 0, 'disable-collisions': 'false'},
    'births': {'enabled': 'false', 'max-surplus': 6, 'radius': 64},
    'restock': {'enabled': 'true', 'times': '[1000, 6000, 11000]', 'mode': 'reset-uses',
                'require-workstation': 'false', 'workstation-reach': 2.5, 'message': '""'},
}


def config(**changes):
    """Writes the test config with some values changed, e.g. surplus__crowd=5, and reloads."""
    values = {k: (dict(v) if isinstance(v, dict) else v) for k, v in DEFAULTS.items()}
    for key, value in changes.items():
        parts = key.split('__')
        if len(parts) == 2:
            values[parts[0].replace('_', '-')][parts[1].replace('_', '-')] = value
        else:
            values[key.replace('_', '-')] = value
    lines = []
    for key, value in values.items():
        if isinstance(value, dict):
            lines.append(f'{key}:')
            lines += [f'  {k}: {v}' for k, v in value.items()]
        else:
            lines.append(f'{key}: {value}')
    with open(args.config, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    return rc('fv reload')


def check(name, cond, detail=''):
    results.append((name, bool(cond)))
    print(('PASS ' if cond else 'FAIL ') + name + ('' if cond else '  -> ' + str(detail)), flush=True)


def sel(tag):
    return f'@e[type=villager,tag={tag},limit=1]'


def q(tag):
    out = rc(f'fvtest query {sel(tag)}')
    values = dict(part.split('=', 1) for part in out.split() if '=' in part)
    return values or {'missing': out}


def state(tag):
    match = re.search(r'State: (.*?)\.', rc(f'fv inspect {sel(tag)}'))
    return match.group(1) if match else 'missing'


def inspect(tag):
    return rc(f'fv inspect {sel(tag)}')


def status():
    out = rc('fv status')
    numbers = re.search(r'Loaded villagers: (\d+), frozen by staff: (\d+), pulsed trapped: (\d+), pulsed surplus: (\d+)', out)
    return [int(n) for n in numbers.groups()] if numbers else None


def summon(tag, x, z, profession='librarian', xp=5, level=1, y=Y, extra=''):
    rc(f'summon villager {x + 0.5} {y} {z + 0.5} {{Tags:["{tag}","fvt"],PersistenceRequired:1b,'
       f'VillagerData:{{profession:"minecraft:{profession}",level:{level},type:"minecraft:plains"}},Xp:{xp},'
       f'CanPickUpLoot:1b{extra}}}')


def cell(x, z):
    """A 1x1 glass cell on the ground, two blocks high."""
    rc(f'fill {x - 1} {Y} {z - 1} {x + 1} {Y + 2} {z + 1} glass')
    rc(f'fill {x} {Y} {z} {x} {Y + 1} {z} air')


def clear(x, z, r=6):
    rc(f'fill {x - r} {Y} {z - r} {x + r} {Y + 8} {z + r} air')


def sample_aware(tag, seconds, step=0.1):
    samples = []
    end = time.time() + seconds
    while time.time() < end:
        samples.append(q(tag).get('aware') == 'true')
        time.sleep(step)
    return samples


def wait_checks():
    # Villagers are checked every 20 ticks in the test config.
    time.sleep(1.6)


def site(i):
    """One test site per chunk along x, so the sites don't touch."""
    return 8 + 16 * i, 8


def setup():
    print(rc('gamerule spawn_monsters false'))
    print(rc('gamerule advance_time false'))
    rc('kill @e[type=villager,tag=fvt]')
    rc('kill @e[type=minecart]')
    for area in ('0 0 639 15', '1984 0 2031 47', '2976 0 3263 15'):
        rc(f'forceload add {area}')
    time.sleep(2)
    for i in range(40):
        clear(*site(i), r=7)
    rc(f'fill 1984 {Y} 0 2031 {Y + 6} 47 air')
    rc(f'fill 2976 {Y} 0 3263 {Y + 6} 15 air')
    print(config())


# ---------------------------------------------------------------- trapped

def test_trapped():
    x, z = site(0)
    cell(x, z)
    summon('cell', x, z)
    time.sleep(0.5)
    check('trapped trade-locked villager without a bed is pulsed', state('cell') == 'pulsed (trapped)', inspect('cell'))
    v = q('cell')
    check('pulsed trapped villager has item pickup off', v.get('pickup') == 'false', v)
    check('pulsed trapped villager has pushing off', v.get('collidable') == 'false', v)
    check('pulsed trapped villager keeps its physics', v.get('ai') == 'true', v)
    samples = sample_aware('cell', 4)
    share = sum(samples) / max(1, len(samples))
    check('pulsed villager is awake part of the time', 0.05 < share < 0.5, f'awake share {share:.2f}')

    x, z = site(1)
    cell(x, z)
    rc(f'setblock {x} {Y} {z - 1} lectern')
    summon('fresh', x, z, xp=0)
    wait_checks()
    check('villager that never traded is left alone (rerolling)', state('fresh') == 'normal', inspect('fresh'))

    x, z = site(2)
    summon('open', x, z)
    wait_checks()
    check('villager that can walk around is left alone', state('open') == 'normal', inspect('open'))

    x, z = site(3)
    rc(f'fill {x - 1} {Y} {z - 1} {x + 1} {Y} {z + 1} stone')
    rc(f'setblock {x} {Y} {z} air')
    summon('jump', x, z)
    wait_checks()
    check('villager behind a wall it can jump is left alone', state('jump') == 'normal', inspect('jump'))

    x, z = site(4)
    rc(f'fill {x - 1} {Y} {z - 1} {x + 1} {Y} {z + 1} oak_fence')
    rc(f'setblock {x} {Y} {z} air')
    summon('fence', x, z)
    wait_checks()
    check('villager inside a ring of fences is pulsed', state('fence') == 'pulsed (trapped)', inspect('fence'))

    x, z = site(5)
    cell(x, z)
    rc(f'setblock {x} {Y} {z - 1} oak_door[half=lower,facing=south]')
    rc(f'setblock {x} {Y + 1} {z - 1} oak_door[half=upper,facing=south]')
    summon('door', x, z)
    wait_checks()
    check('villager behind a wooden door is left alone', state('door') == 'normal', inspect('door'))

    x, z = site(6)
    rc(f'summon minecart {x + 0.5} {Y} {z + 0.5} {{Tags:["fvt"],Passengers:[{{id:"minecraft:villager",'
       f'Tags:["cart","fvt"],PersistenceRequired:1b,'
       f'VillagerData:{{profession:"minecraft:librarian",level:1,type:"minecraft:plains"}},Xp:5}}]}}')
    wait_checks()
    check('villager in a minecart is pulsed', state('cart') == 'pulsed (trapped)', inspect('cart'))

    x, z = site(7)
    cell(x, z)
    rc(f'setblock {x + 1} {Y} {z + 1} red_bed[part=foot,facing=north]')
    summon('bed', x, z)
    rc(f'fvtest home {sel("bed")} {x + 1} {Y} {z + 1}')
    wait_checks()
    check('trapped villager with a bed is left alone (iron farms)', state('bed') == 'normal', inspect('bed'))

    x, z = site(8)
    cell(x, z)
    rc(f'summon villager {x + 0.5} {Y} {z + 0.5} {{Tags:["ignored","fvt","frozenvillagers_ignore"],'
       f'VillagerData:{{profession:"minecraft:librarian",level:1,type:"minecraft:plains"}},Xp:5}}')
    wait_checks()
    check('villager with the ignore tag is left alone', state('ignored') == 'normal', inspect('ignored'))

    x, z = site(9)
    cell(x, z)
    summon('npc', x, z)
    rc(f'fvtest npc {sel("npc")}')
    rc(f'fv auto {sel("npc")}')
    wait_checks()
    check('NPC from another plugin is left alone', state('npc') == 'normal', inspect('npc'))
    check('NPC got its AI back', q('npc').get('aware') == 'true', q('npc'))

    # The usual trading hall cell: workstation in front, an open block above it
    # to click the villager, and a roof that leaves no room to jump on it.
    x, z = site(24)
    cell(x, z)
    rc(f'setblock {x} {Y} {z - 1} loom')
    rc(f'setblock {x} {Y + 1} {z - 1} air')
    rc(f'fill {x - 1} {Y + 2} {z - 1} {x + 1} {Y + 2} {z + 1} glass')
    summon('click', x, z, profession='shepherd')
    wait_checks()
    check('a cell open above the workstation for clicking is still a cell', state('click') == 'pulsed (trapped)',
          inspect('click'))

    # Without the roof the villager can jump on the workstation and walk out.
    x, z = site(25)
    cell(x, z)
    rc(f'setblock {x} {Y} {z - 1} loom')
    rc(f'setblock {x} {Y + 1} {z - 1} air')
    rc(f'fill {x - 1} {Y + 2} {z - 1} {x + 1} {Y + 2} {z + 1} air')
    summon('noroof', x, z, profession='shepherd')
    wait_checks()
    check('without a roof it can jump on the workstation, so it is left alone', state('noroof') == 'normal',
          inspect('noroof'))

    # Open the first cell: the villager wakes up and gets everything back.
    x, z = site(0)
    rc(f'fill {x + 1} {Y} {z} {x + 1} {Y + 1} {z} air')
    wait_checks()
    v = q('cell')
    check('opening the cell gives the villager its full AI back', state('cell') == 'normal' and v.get('aware') == 'true', v)
    check('opening the cell restores pickup and pushing', v.get('pickup') == 'true' and v.get('collidable') == 'true', v)
    rc(f'fill {x + 1} {Y} {z} {x + 1} {Y + 1} {z} glass')
    rc(f'tp {sel("cell")} {x + 0.5} {Y} {z + 0.5}')
    wait_checks()
    check('closing it again pulses it again', state('cell') == 'pulsed (trapped)', inspect('cell'))

    config(trapped__enabled='false')
    check('trapped.enabled false gives it its AI back', state('cell') == 'normal' and q('cell').get('aware') == 'true')
    config()


def test_damage():
    x, z = site(10)
    cell(x, z)
    summon('hurt', x, z)
    time.sleep(0.5)
    rc(f'damage {sel("hurt")} 1 minecraft:generic')
    samples = sample_aware('hurt', 3)
    check('a hurt pulsed villager stays awake to run away', all(samples), f'{sum(samples)}/{len(samples)} awake')


# ---------------------------------------------------------------- foreign

def test_foreign():
    x, z = site(11)
    cell(x, z)
    summon('foreign', x, z)
    time.sleep(0.5)
    rc(f'fv auto {sel("foreign")}')
    rc(f'fvtest aware {sel("foreign")} true')
    rc(f'tag {sel("foreign")} add frozenvillagers_ignore')
    wait_checks()
    rc(f'fvtest aware {sel("foreign")} false')
    rc(f'tag {sel("foreign")} remove frozenvillagers_ignore')
    wait_checks()
    check('villager whose AI another plugin turned off is left alone', state('foreign') == 'normal', inspect('foreign'))
    check('its AI stays off', all(not s for s in sample_aware('foreign', 1.5)))
    rc(f'fv freeze {sel("foreign")}')
    rc(f'fv unfreeze {sel("foreign")}')
    check('freeze and unfreeze keep the other plugin\'s AI off', q('foreign').get('aware') == 'false', q('foreign'))

    rc(f'fvtest bvmark {sel("foreign")}')
    out = rc(f'fv adopt {sel("foreign")}')
    check('/fv adopt takes over a villager another plugin froze', 'Adopted: 1 villager' in out, out)
    check('...and removes the BubbleVillagers marker, so it no longer refreezes it',
          'Removed the markers' in out and 'bvmark=false' in rc(f'fvtest hasbvmark {sel("foreign")}'), out)
    check('...in a cell it is pulsed like ours', state('foreign') == 'pulsed (trapped)', inspect('foreign'))
    x, z = site(23)
    summon('migrated', x, z)
    rc(f'tag {sel("migrated")} add frozenvillagers_ignore')
    rc(f'fvtest aware {sel("migrated")} false')
    rc(f'tag {sel("migrated")} remove frozenvillagers_ignore')
    rc(f'fv adopt {sel("migrated")}')
    check('...in the open it gets its AI back', q('migrated').get('aware') == 'true' and state('migrated') == 'normal',
          q('migrated'))
    out = rc(f'fv adopt {sel("migrated")}')
    check('adopting a villager with its AI on does nothing', 'Adopted: 0 villagers' in out, out)

    x, z = site(12)
    cell(x, z)
    summon('noai', x, z, extra=',NoAI:1b')
    wait_checks()
    check('villager with NoAI from another plugin is left alone', state('noai') == 'normal', inspect('noai'))
    rc(f'fv freeze {sel("noai")}')
    rc(f'fv unfreeze {sel("noai")}')
    check('freeze and unfreeze keep the other plugin\'s NoAI', q('noai').get('ai') == 'false', q('noai'))

    x, z = site(13)
    summon('nopickup', x, z)
    rc(f'data merge entity {sel("nopickup")} {{CanPickUpLoot:0b}}')
    rc(f'fv freeze {sel("nopickup")}')
    rc(f'fv unfreeze {sel("nopickup")}')
    check('item pickup turned off by someone else stays off', q('nopickup').get('pickup') == 'false', q('nopickup'))


# ---------------------------------------------------------------- staff

def test_staff():
    x, z = site(14)
    summon('frz', x, z, y=Y + 4)
    out = rc(f'fv freeze {sel("frz")}')
    check('/fv freeze reports one villager', 'Frozen: 1 villager' in out, out)
    time.sleep(2)
    v = q('frz')
    check('frozen villager has AI, pickup and pushing off', v.get('aware') == 'false' and v.get('pickup') == 'false'
          and v.get('collidable') == 'false', v)
    check('frozen villager still falls (physics on)', float(v.get('y', 0)) < Y + 0.5, v)
    check('frozen villager stays frozen in the open', state('frz') == 'frozen', inspect('frz'))

    x, z = site(15)
    cell(x, z)
    summon('excl', x, z)
    time.sleep(0.5)
    rc(f'fv unfreeze {sel("excl")}')
    wait_checks()
    check('/fv unfreeze excludes it even in a cell', state('excl') == 'normal' and 'excluded' in inspect('excl'), inspect('excl'))
    rc(f'fv auto {sel("excl")}')
    wait_checks()
    check('/fv auto puts it back to automatic', state('excl') == 'pulsed (trapped)', inspect('excl'))
    rc(f'fv unfreeze {sel("excl")}')

    config(freeze__disable_physics='true')
    x, z = site(16)
    summon('float', x, z, y=Y + 4)
    rc(f'fv freeze {sel("float")}')
    time.sleep(2)
    v = q('float')
    check('disable-physics also turns NoAI on', v.get('ai') == 'false', v)
    check('with physics off it stays in the air', float(v.get('y', 0)) > Y + 3, v)
    config()
    check('disable-physics false gives the physics back', q('float').get('ai') == 'true', q('float'))
    rc(f'fv unfreeze {sel("float")}')

    out = rc('fv freeze @e[type=villager,tag=nonexistent]')
    check('a selector that matches nothing freezes nothing', 'Frozen: 0 villagers' in out, out)
    out = rc('fv freeze @q')
    check('a bad selector gives an error', 'Not a radius or a valid selector' in out, out)
    out = rc('fv freeze 5')
    check('a radius from the console gives an error', 'only works for players' in out, out)
    out = rc('fv inspect @e[type=villager,tag=fvt]')
    check('inspect with many villagers asks for one', 'exactly one' in out, out)


# ---------------------------------------------------------------- restock

def restock(tag):
    return 'restocked=true' in rc(f'fvtest restock {sel(tag)}')


def test_restock():
    x, z = site(17)
    cell(x, z)
    summon('rs', x, z)
    time.sleep(0.5)
    rc(f'fv freeze {sel("rs")}')
    rc('time set 500')
    rc(f'fvtest use {sel("rs")}')
    check('first restock ever happens right away', restock('rs'))
    check('restock resets the uses', q('rs').get('uses') == '0', q('rs'))
    rc(f'fvtest use {sel("rs")}')
    check('no second restock before the next time', not restock('rs'))
    rc('time set 1500')
    check('restocks after 07:00', restock('rs'))
    rc(f'fvtest use {sel("rs")}')
    rc('time set 1600')
    check('only once per window', not restock('rs'))
    rc('time set 6000')
    check('restocks again at 12:00', restock('rs'))
    rc('time set 11000')
    check('a click with full trades does not restock', not restock('rs'))
    rc(f'fvtest use {sel("rs")}')
    check('...and does not spend the window', restock('rs'))
    rc(f'fvtest use {sel("rs")}')
    rc('time set 300')
    check('/time set backwards does not block restocks', restock('rs'))
    rc(f'fvtest use {sel("rs")}')
    rc('time add 24000')
    check('missed windows overnight give one restock', restock('rs'))
    check('reset-uses keeps prices: demand stays 0', q('rs').get('demand') == '0', q('rs'))

    config(restock__mode='vanilla')
    rc(f'fvtest use {sel("rs")}')
    rc('time add 24000')
    check('vanilla mode restocks too', restock('rs'))
    check('vanilla mode raises the demand of used trades', int(q('rs').get('demand', 0)) > 0, q('rs'))

    config(restock__require_workstation='true')
    rc(f'fvtest use {sel("rs")}')
    rc('time add 24000')
    check('require-workstation blocks villagers without one', not restock('rs'))
    rc(f'setblock {x} {Y} {z - 1} lectern')
    rc(f'fvtest jobsite {sel("rs")} {x} {Y} {z - 1}')
    check('...and allows it with a lectern next to it', restock('rs'))
    config()

    rc(f'fvtest use {sel("rs")}')
    rc(f'fvtest vanillarestock {sel("rs")}')
    check('vanilla restocks are blocked for frozen villagers', int(q('rs').get('uses', 0)) > 0, q('rs'))
    demand = q('rs').get('demand')
    rc(f'fvtest vanillarestock {sel("rs")}')
    check('a blocked vanilla restock does not change prices', q('rs').get('demand') == demand, (demand, q('rs')))
    rc(f'fvtest catchup {sel("rs")}')
    time.sleep(0.3)
    v = q('rs')
    check('a blocked dawn catch-up neither restocks nor changes prices',
          v.get('demand') == demand and int(v.get('uses', 0)) > 0, (demand, v))
    rc('time add 24000')
    rc(f'fvtest use {sel("open")}')
    check('a normal villager is not restocked by the plugin', not restock('open'))
    rc(f'fvtest vanillarestock {sel("open")}')
    check('a normal villager restocks the vanilla way', q('open').get('uses') == '0', q('open'))

    config(restock__enabled='false')
    rc('time add 24000')
    check('restock.enabled false turns restocks off', not restock('rs'))
    config()


# ---------------------------------------------------------------- level up

def wait_for(predicate, seconds):
    end = time.time() + seconds
    while time.time() < end:
        if predicate():
            return True
        time.sleep(0.2)
    return False


def test_level_up():
    x, z = site(18)
    cell(x, z)
    summon('lv', x, z)
    time.sleep(0.5)
    before = q('lv')
    rc(f'fvtest xp {sel("lv")} 9')
    rc(f'fvtest trade {sel("lv")}')
    levelled = wait_for(lambda: q('lv').get('level') == '2', 8)
    after = q('lv')
    check('a pulsed villager levels up after a trade', levelled, after)
    check('it gets the new trades of level 2', int(after.get('recipes', 0)) > int(before.get('recipes', 0)), (before, after))
    check('it goes back to pulsed after the level up', wait_for(lambda: state('lv') == 'pulsed (trapped)', 3), inspect('lv'))
    check('the stand still modifier is removed', q('lv').get('slowed') == 'false', q('lv'))
    rc(f'fv unfreeze {sel("lv")}')
    time.sleep(4)
    check('no extra level when its AI comes back', q('lv').get('level') == '2', q('lv'))

    x, z = site(19)
    summon('lv2', x, z)
    rc(f'fv freeze {sel("lv2")}')
    rc(f'fvtest xp {sel("lv2")} 9')
    rc(f'fvtest trade {sel("lv2")}')
    check('a frozen villager levels up too', wait_for(lambda: q('lv2').get('level') == '2', 8), q('lv2'))
    time.sleep(1)
    v = q('lv2')
    check('it stays where it was while awake', abs(float(v.get('y', 0)) - Y) < 0.1, v)
    check('it is frozen again afterwards', state('lv2') == 'frozen' and v.get('aware') == 'false', v)

    config(freeze__disable_physics='true')
    x, z = site(20)
    summon('lv3', x, z)
    rc(f'fv freeze {sel("lv3")}')
    rc(f'fvtest xp {sel("lv3")} 9')
    rc(f'fvtest trade {sel("lv3")}')
    check('a frozen villager without physics levels up', wait_for(lambda: q('lv3').get('level') == '2', 8), q('lv3'))
    check('its physics are off again afterwards', wait_for(lambda: q('lv3').get('ai') == 'false', 3), q('lv3'))
    config()

    x, z = site(21)
    cell(x, z)
    summon('lv4', x, z)
    time.sleep(0.5)
    rc(f'fvtest xp {sel("lv4")} 80')
    # XP without a planned level up, like after /data: vanilla won't level
    # it up, the plugin must try once and then give up.
    check('XP without a planned level up wakes it once', wait_for(lambda: state('lv4') == 'awake to level up', 4), inspect('lv4'))
    check('...then it gives up and is pulsed again', wait_for(lambda: state('lv4') == 'pulsed (trapped)', 20), inspect('lv4'))
    time.sleep(3)
    check('...and does not try again with the same XP', state('lv4') == 'pulsed (trapped)' and q('lv4').get('level') == '1', inspect('lv4'))

    x, z = site(22)
    cell(x, z)
    summon('lv5', x, z, level=5, xp=500)
    time.sleep(2)
    check('a master villager is never woken for a level up', state('lv5') == 'pulsed (trapped)', inspect('lv5'))


# ---------------------------------------------------------------- surplus

SX, SZ = 2000, 24


def pen():
    """A 9x9 fenced pen: the villagers inside can walk, so they are not trapped."""
    rc(f'fill {SX - 5} {Y} {SZ - 5} {SX + 5} {Y} {SZ + 5} oak_fence')
    rc(f'fill {SX - 4} {Y} {SZ - 4} {SX + 4} {Y} {SZ + 4} air')


def spawn_surplus(tag, count, extra=''):
    for i in range(count):
        dx, dz = i % 7 - 3, i // 7 - 1
        rc(f'summon villager {SX + dx + 0.5} {Y} {SZ + dz + 0.5} {{Tags:["{tag}","sp","fvt"],PersistenceRequired:1b,'
           f'VillagerData:{{profession:"minecraft:none",level:1,type:"minecraft:plains"}}{extra}}}')


def surplus_count():
    numbers = status()
    return numbers[3] if numbers else -1


def test_surplus():
    pen()
    config(surplus__grace=6000)
    spawn_surplus('s', 14)
    wait_checks()
    check('a new crowd waits for the grace time', surplus_count() == 0, rc('fv status'))
    config()
    wait_checks()
    check('a crowd of 14 jobless villagers without beds is pulsed', surplus_count() == 14, rc('fv status'))
    check('surplus villagers keep their pushing by default', q('s').get('collidable') == 'true', q('s'))
    config(surplus__disable_collisions='true')
    check('disable-collisions turns pushing off', q('s').get('collidable') == 'false', q('s'))
    config()
    check('...and back on', q('s').get('collidable') == 'true', q('s'))

    rc(f'damage {sel("s")} 1 minecraft:generic')
    samples = sample_aware('s', 2)
    check('a hurt surplus villager stays awake', all(samples), f'{sum(samples)}/{len(samples)}')

    rc('tag @e[type=villager,tag=sp,limit=1,sort=furthest] add placed')
    time.sleep(9)  # past the 10 seconds a hurt villager stays awake
    before = sample_aware('placed', 2)
    check('before: a surplus villager is asleep most of the time', sum(before) / len(before) < 0.5,
          f'{sum(before)}/{len(before)}')
    print(rc(f'fvtest place {SX + 4} {Y} {SZ + 4} lectern'))
    samples = sample_aware('placed', 3)
    check('placing a workstation nearby wakes surplus villagers to claim it', all(samples), f'{sum(samples)}/{len(samples)}')
    rc(f'setblock {SX + 4} {Y} {SZ + 4} air')

    rc(f'fvtest home {sel("s")} {SX} {Y} {SZ}')
    rc(f'setblock {SX} {Y} {SZ - 4} red_bed[part=foot,facing=north]')
    rc(f'fvtest home {sel("s")} {SX} {Y} {SZ - 4}')
    wait_checks()
    check('a villager in the crowd that has a bed is left alone', state('s') == 'normal', inspect('s'))

    rc('data merge entity @e[type=villager,tag=sp,limit=1,sort=furthest] '
       '{VillagerData:{profession:"minecraft:farmer",level:1,type:"minecraft:plains"},Xp:1,Tags:["job","sp","fvt"]}')
    wait_checks()
    check('a villager that gets a job is left alone', state('job') == 'normal', inspect('job'))

    rc('kill @e[type=villager,tag=sp,limit=3]')
    wait_checks()
    count = surplus_count()
    check('a crowd just under the limit stays pulsed (no flipping)', count >= 9, rc('fv status'))
    rc('kill @e[type=villager,tag=sp,limit=4]')
    wait_checks()
    check('a crowd well under the limit wakes up', surplus_count() == 0, rc('fv status'))

    rc('kill @e[type=villager,tag=sp]')
    spawn_surplus('a', 9)
    spawn_surplus('baby', 5, ',Age:-24000')
    wait_checks()
    check('babies count for the crowd but are never pulsed',
          surplus_count() == 9 and state('baby') == 'normal', rc('fv status'))
    rc('kill @e[type=villager,tag=sp]')
    spawn_surplus('n', 12)
    rc('data merge entity @e[type=villager,tag=n,limit=6] '
       '{VillagerData:{profession:"minecraft:nitwit",level:1,type:"minecraft:plains"}}')
    wait_checks()
    check('nitwits count as surplus too', surplus_count() == 12, rc('fv status'))
    config(surplus__enabled='false')
    check('surplus.enabled false wakes them all', surplus_count() == 0, rc('fv status'))
    config()
    rc('kill @e[type=villager,tag=sp]')


# ---------------------------------------------------------------- births

BX, BZ = 3000, 8


def baby():
    return rc(f'fvtest spawnbaby {BX + 0.5} {Y} {BZ + 0.5}')


def test_births():
    def storage(x, count, tag):
        for i in range(count):
            rc(f'summon villager {x + i % 4 + 0.5} {Y} {BZ + 0.5} {{Tags:["{tag}","bt","fvt"],PersistenceRequired:1b,'
               f'VillagerData:{{profession:"minecraft:none",level:1,type:"minecraft:plains"}}}}')

    storage(BX + 40, 6, 'near')
    time.sleep(0.5)
    check('births are allowed when the limit is off', baby() == 'born')
    rc('kill @e[type=villager,tag=fvt,x=2990,y=-70,z=0,dx=20,dy=20,dz=20]')
    config(births__enabled='true')
    check('storage 40 blocks away in another chunk blocks births', baby() == 'cancelled')
    rc('kill @e[type=villager,tag=near,limit=2]')
    time.sleep(0.5)
    check('under the limit babies are born again', baby() == 'born')
    check('...and each baby counts, so the limit holds', baby() == 'born' and baby() == 'cancelled')
    rc('kill @e[type=villager,tag=bt]')
    rc('kill @e[type=villager,tag=fvt,x=2990,y=-70,z=0,dx=20,dy=20,dz=20]')
    storage(BX + 200, 10, 'far')
    time.sleep(0.5)
    check('storage further than the radius does not count', baby() == 'born')
    config()
    rc('kill @e[type=villager,tag=bt]')


# ---------------------------------------------------------------- config

def test_config():
    out = config(worlds='[some_other_world]')
    check('a world that does not exist gives a warning', 'some_other_world' in out, out)
    check('other worlds only: trapped villagers wake up', state('fence') == 'normal')
    check('...staff frozen villagers stay frozen', state('frz') == 'frozen')
    config()
    check('all worlds again: trapped villagers are pulsed again', state('fence') == 'pulsed (trapped)')

    out = config(pulse__active=100, restock__times='[abc, 30000]', restock__mode='sometimes', check_interval=5)
    check('pulse.active longer than the cycle gives a warning', 'pulse.active' in out, out)
    check('bad restock times give warnings', 'abc' in out and '30000' in out, out)
    check('a bad restock mode gives a warning', 'restock.mode' in out, out)
    check('a too short check-interval gives a warning', 'check-interval' in out, out)
    check('the plugin keeps working with bad values', state('fence') == 'pulsed (trapped)', inspect('fence'))
    config()


# ---------------------------------------------------------------- persistence

def test_chunk_reload():
    rc('forceload remove 0 0 639 15')
    time.sleep(3)
    rc('forceload add 0 0 639 15')
    time.sleep(3)
    check('after a chunk reload staff frozen stay frozen', state('frz') == 'frozen', inspect('frz'))
    check('...with pushing off again (it is not saved)', q('frz').get('collidable') == 'false', q('frz'))
    check('...trapped villagers are pulsed again', state('fence') == 'pulsed (trapped)', inspect('fence'))
    check('...excluded villagers stay excluded', state('excl') == 'normal' and 'excluded' in inspect('excl'))


def test_disable():
    rc(f'fv auto {sel("cell")}')
    check('before: the trapped villager is pulsed', state('cell') == 'pulsed (trapped)', inspect('cell'))
    rc('fvtest disable')
    time.sleep(0.5)
    v = q('cell')
    check('disabling the plugin gives pulsed villagers their full AI back',
          v.get('aware') == 'true' and v.get('pickup') == 'true' and v.get('collidable') == 'true', v)
    check('...and keeps staff frozen villagers frozen', q('frz').get('aware') == 'false', q('frz'))
    rc('fvtest enable')
    time.sleep(1)
    check('enabling it again pulses them again', state('cell') == 'pulsed (trapped)', inspect('cell'))


def test_status():
    numbers = status()
    check('/fv status counts the villagers', numbers is not None and numbers[0] > 0 and numbers[1] > 0, rc('fv status'))
    cost = re.search(r'Plugin cost: ([\d.]+) microseconds', rc('fv status'))
    check('/fv status shows the plugin cost', cost is not None, rc('fv status'))
    check('...and it is tiny (under 0.5 ms per tick)', cost is not None and float(cost.group(1)) < 500, rc('fv status'))


def after_restart():
    config()
    for area in ('0 0 639 15',):
        rc(f'forceload add {area}')
    time.sleep(4)
    check('after a restart staff frozen stay frozen', state('frz') == 'frozen' and q('frz').get('aware') == 'false', q('frz'))
    check('...trapped villagers are pulsed again', state('fence') == 'pulsed (trapped)', inspect('fence'))
    check('...excluded villagers stay excluded', 'excluded' in inspect('excl'))


GROUPS = [test_trapped, test_damage, test_foreign, test_staff, test_restock, test_level_up,
          test_surplus, test_births, test_config, test_chunk_reload, test_status, test_disable]

def after_removal():
    """Run with the plugin removed: only vanilla commands, on what was saved."""
    time.sleep(3)
    aware = rc(f'data get entity {sel("cell")} "Bukkit.Aware"')
    check('without the plugin, slowed down villagers have their AI back', aware.endswith('1b'), aware)
    pickup = rc(f'data get entity {sel("cell")} CanPickUpLoot')
    check('...and pick up items again', pickup.endswith('1b'), pickup)
    frozen = rc(f'data get entity {sel("frz")} "Bukkit.Aware"')
    check('...and staff frozen villagers stay frozen', frozen.endswith('0b'), frozen)


if args.after_removal:
    after_removal()
elif args.after_restart:
    after_restart()
else:
    setup()
    for group in GROUPS:
        if args.only and args.only not in group.__name__:
            continue
        print(f'--- {group.__name__}', flush=True)
        group()
    rc('save-all flush')
    print('Now restart the server and run again with --after-restart.')

failed = [name for name, ok in results if not ok]
print(f'\n{len(results) - len(failed)}/{len(results)} passed')
if failed:
    raise SystemExit(1)
