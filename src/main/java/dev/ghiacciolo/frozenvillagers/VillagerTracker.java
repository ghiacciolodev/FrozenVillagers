package dev.ghiacciolo.frozenvillagers;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.ghiacciolo.frozenvillagers.Marks.Mode;
import dev.ghiacciolo.frozenvillagers.Tracked.Control;
import dev.ghiacciolo.frozenvillagers.Tracked.Reason;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Raid;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Villager.Profession;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.raid.RaidTriggerEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Keeps track of every loaded villager and decides what to do with each one.
 *
 * <p>Nothing here scans the world. Villagers are added and removed through
 * Paper's entity events, each one is checked once every check-interval ticks
 * in its own bucket, and the pulsed ones are switched on and off in buckets
 * too. So every tick only touches a small, fixed share of the villagers.
 */
final class VillagerTracker implements Listener {

    // A hurt villager that is slowed down gets its full AI for 10 seconds, to run away.
    private static final int HURT_AWAKE_TICKS = 200;
    // A new bed, workstation or bell wakes the slowed down villagers this close
    // for 30 seconds, so they claim it as fast as in vanilla.
    private static final double PLACED_DISTANCE = 16;
    private static final int PLACED_AWAKE_TICKS = 600;
    // The plugin's own cost is averaged over this many ticks, for /fv status.
    private static final int COST_WINDOW = 1200;
    // Villagers this close to a raid keep their full AI.
    private static final double RAID_DISTANCE = 112;
    // A surplus crowd has to shrink below this share of the threshold before
    // its villagers wake up, so a crowd right at the limit doesn't flip every check.
    private static final double CROWD_HYSTERESIS = 0.75;

    private final Plugin plugin;
    private final Marks marks;
    private Settings settings;

    private final Map<UUID, Tracked> tracked = new HashMap<>();
    private List<Set<Tracked>> checkBuckets = List.of();
    private List<Set<Tracked>> pulseBuckets = List.of();
    // Villagers without a bed and a job, per world and per chunk.
    private final Map<UUID, Map<Long, int[]>> idleCounts = new HashMap<>();
    // world.getRaids() builds a new list every call, so it is read once per tick per world.
    private final Map<UUID, List<Raid>> raidsThisTick = new HashMap<>();
    private int raidsTick = -1;

    private Consumer<Tracked> levelUpCheck = tracked -> { };
    private Consumer<Villager> startedHandling = villager -> { };
    private BukkitTask task;

    // Time spent in the plugin's tick and in checks of newly loaded villagers.
    private long costNanos;
    private int costTicks;
    private double lastCostMicros = -1;

    VillagerTracker(Plugin plugin, Marks marks, Settings settings) {
        this.plugin = plugin;
        this.marks = marks;
        this.settings = settings;
        rebuildBuckets();
    }

    /** Called after each check of a villager, to start a pending level up. */
    void onChecked(Consumer<Tracked> levelUpCheck) {
        this.levelUpCheck = levelUpCheck;
    }

    /** Called when the plugin starts restocking a villager instead of vanilla. */
    void onStartedHandling(Consumer<Villager> startedHandling) {
        this.startedHandling = startedHandling;
    }

    /** The plugin's average time per tick over the last minute, in microseconds, or -1 before that. */
    double averageCostMicros() {
        return lastCostMicros;
    }

    void start() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                track(villager);
            }
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    void reconfigure(Settings settings) {
        this.settings = settings;
        rebuildBuckets();
        for (Tracked entry : List.copyOf(tracked.values())) {
            check(entry);
        }
    }

    /**
     * Gives slowed down villagers their AI back, so nothing stays half off if
     * the plugin is removed. Villagers frozen by staff stay frozen, that is
     * what staff asked for.
     */
    void shutdown() {
        if (task != null) {
            task.cancel();
        }
        for (Tracked entry : tracked.values()) {
            entry.levelingUp = false;
            if (entry.control == Control.PULSED) {
                apply(entry, Control.NONE, null);
            } else if (entry.control == Control.FROZEN) {
                // It may be awake for a level up right now.
                apply(entry, Control.FROZEN, null);
            }
        }
        tracked.clear();
    }

    Tracked get(Villager villager) {
        return tracked.get(villager.getUniqueId());
    }

    Collection<Tracked> all() {
        return tracked.values();
    }

    /** Checks a villager right away, for example after a staff command. */
    Tracked checkNow(Villager villager) {
        Tracked entry = tracked.get(villager.getUniqueId());
        if (entry == null) {
            return track(villager);
        }
        check(entry);
        return entry;
    }

    private Tracked track(Villager villager) {
        Tracked entry = tracked.get(villager.getUniqueId());
        if (entry != null) {
            return entry;
        }
        entry = new Tracked(villager);
        tracked.put(entry.id, entry);
        entry.checkBucket = Math.floorMod(villager.getEntityId(), checkBuckets.size());
        entry.pulsePhase = Math.floorMod(entry.id.hashCode(), pulseBuckets.size());
        checkBuckets.get(entry.checkBucket).add(entry);
        // Right away rather than at its turn: a big trading hall that loads
        // would otherwise run its full AI for a few seconds.
        check(entry);
        return entry;
    }

    private void untrack(UUID id) {
        Tracked entry = tracked.remove(id);
        if (entry == null) {
            return;
        }
        checkBuckets.get(entry.checkBucket).remove(entry);
        pulseBuckets.get(entry.pulsePhase).remove(entry);
        if (entry.countedIdle) {
            changeIdle(entry.worldId, entry.chunkKey, -1);
        }
    }

    private void rebuildBuckets() {
        checkBuckets = newBuckets(settings.checkInterval);
        pulseBuckets = newBuckets(settings.pulseCycle);
        for (Tracked entry : tracked.values()) {
            entry.checkBucket = Math.floorMod(entry.villager.getEntityId(), checkBuckets.size());
            entry.pulsePhase = Math.floorMod(entry.id.hashCode(), pulseBuckets.size());
            checkBuckets.get(entry.checkBucket).add(entry);
            if (entry.control == Control.PULSED) {
                pulseBuckets.get(entry.pulsePhase).add(entry);
            }
        }
    }

    private static List<Set<Tracked>> newBuckets(int size) {
        List<Set<Tracked>> buckets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            buckets.add(new HashSet<>());
        }
        return buckets;
    }

    private void tick() {
        long start = System.nanoTime();
        int now = plugin.getServer().getCurrentTick();

        // Copied because a check can untrack a villager that is no longer valid.
        Set<Tracked> due = checkBuckets.get(Math.floorMod(now, checkBuckets.size()));
        if (!due.isEmpty()) {
            for (Tracked entry : due.toArray(Tracked[]::new)) {
                check(entry);
            }
        }

        // Each pulsed villager wakes up when the tick reaches its phase and goes
        // back to sleep pulse.active ticks later. Phases are spread, so only a
        // few villagers switch on any tick.
        int cycle = pulseBuckets.size();
        for (Tracked entry : pulseBuckets.get(Math.floorMod(now, cycle))) {
            if (!entry.levelingUp && marks.has(entry.villager, marks.aiOff)) {
                entry.villager.setAware(true);
            }
        }
        for (Tracked entry : pulseBuckets.get(Math.floorMod(now - settings.pulseActive, cycle))) {
            if (!entry.levelingUp && now >= entry.awakeUntil && marks.has(entry.villager, marks.aiOff)) {
                entry.villager.setAware(false);
            }
        }

        costNanos += System.nanoTime() - start;
        if (++costTicks >= COST_WINDOW) {
            lastCostMicros = costNanos / 1000.0 / costTicks;
            costNanos = 0;
            costTicks = 0;
        }
    }

    // Fires for new spawns and for villagers loaded from chunks.
    @EventHandler
    public void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Villager villager) {
            long start = System.nanoTime();
            track(villager);
            costNanos += System.nanoTime() - start;
        }
    }

    // Fires on death, chunk unload and changing world.
    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Villager) {
            untrack(event.getEntity().getUniqueId());
        }
    }

    // A dead villager stays in the world for its death animation, about a
    // second. It no longer counts for crowds and the birth limit from now on.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Villager) {
            untrack(event.getEntity().getUniqueId());
        }
    }

    // A slowed down villager that gets hurt needs its AI to run away.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        Tracked entry = tracked.get(villager.getUniqueId());
        if (entry != null) {
            wake(entry, HURT_AWAKE_TICKS);
        }
    }

    // A new bed, workstation or bell may be what a slowed down villager is
    // looking for, e.g. when a player fills a trading hall from a storage.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Material type = event.getBlockPlaced().getType();
        if (!Tag.BEDS.isTagged(type) && type != Material.BELL && !Workstations.isWorkstation(type)) {
            return;
        }
        Location center = event.getBlockPlaced().getLocation().toCenterLocation();
        for (Villager villager : center.getNearbyEntitiesByType(Villager.class, PLACED_DISTANCE)) {
            Tracked entry = tracked.get(villager.getUniqueId());
            if (entry != null) {
                wake(entry, PLACED_AWAKE_TICKS);
            }
        }
    }

    /** Gives a slowed down villager its full AI for a while. */
    private void wake(Tracked entry, int ticks) {
        if (entry.control != Control.PULSED) {
            return;
        }
        entry.awakeUntil = Math.max(entry.awakeUntil, plugin.getServer().getCurrentTick() + ticks);
        if (marks.has(entry.villager, marks.aiOff)) {
            entry.villager.setAware(true);
        }
    }

    // The raid is not in the world's raid list yet when this fires, so the
    // villagers around it are checked again on the next tick.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRaid(RaidTriggerEvent event) {
        Location center = event.getRaid().getLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Tracked entry : List.copyOf(tracked.values())) {
                if (entry.control == Control.PULSED && isNear(entry.villager.getLocation(), center)) {
                    check(entry);
                }
            }
        });
    }

    /** Decides what the plugin should do with a villager, and does it. */
    private void check(Tracked entry) {
        Villager villager = entry.villager;
        if (!villager.isValid()) {
            untrack(entry.id);
            return;
        }
        Location location = villager.getLocation();
        boolean bed = villager.getMemory(MemoryKey.HOME) != null;
        boolean idle = !bed && isJobless(villager.getProfession());
        updateIdle(entry, villager, location, idle);

        // Levelling up needs the AI for a moment, the check after it decides again.
        if (entry.levelingUp) {
            return;
        }

        Mode mode = marks.mode(villager);
        Control desired;
        Reason reason = null;
        if (mode == Mode.FROZEN) {
            desired = Control.FROZEN;
            entry.why = "frozen by staff";
        } else {
            String blocker = autoBlocker(villager, mode);
            if (blocker != null) {
                desired = Control.NONE;
                entry.why = blocker;
            } else if (isTrapped(villager, bed)) {
                desired = Control.PULSED;
                reason = Reason.TRAPPED;
                entry.why = "it can't leave its cell and has no bed to look for";
            } else if (isSurplus(entry, villager, location, idle)) {
                desired = Control.PULSED;
                reason = Reason.SURPLUS;
                entry.why = "it is surplus: no bed and no job, in a crowd of others like it";
            } else {
                desired = Control.NONE;
                entry.why = "nothing to optimize, it uses its AI";
            }
        }
        apply(entry, desired, reason);
        levelUpCheck.accept(entry);
    }

    /** Returns why the automatic features must leave a villager alone, or null. */
    private String autoBlocker(Villager villager, Mode mode) {
        if (mode == Mode.EXCLUDED) {
            return "excluded by staff";
        }
        if (!settings.isActiveIn(villager.getWorld())) {
            return "the plugin is not active in this world";
        }
        if (villager.getScoreboardTags().contains(Marks.IGNORE_TAG)) {
            return "it has the " + Marks.IGNORE_TAG + " tag";
        }
        // Citizens and similar plugins mark their NPCs like this.
        if (villager.hasMetadata("NPC")) {
            return "it is an NPC from another plugin";
        }
        if (!villager.isAware() && !marks.has(villager, marks.aiOff)) {
            return "its AI was turned off by another plugin";
        }
        if (!villager.hasAI() && !marks.has(villager, marks.physicsOff)) {
            return "it has NoAI from another plugin";
        }
        if (!villager.isAdult()) {
            return "it is a baby";
        }
        return null;
    }

    private boolean isTrapped(Villager villager, boolean bed) {
        if (!settings.trappedEnabled || bed || isJobless(villager.getProfession())) {
            return false;
        }
        // A villager that never traded can still change job, which is how
        // players reroll trades. That needs the full AI.
        if (!Levels.isTradeLocked(villager.getVillagerLevel(), villager.getVillagerExperience())) {
            return false;
        }
        return Cells.isTrapped(villager);
    }

    private boolean isSurplus(Tracked entry, Villager villager, Location location, boolean idle) {
        if (!settings.surplusEnabled || !idle) {
            return false;
        }
        long since = marks.getLong(villager, marks.homelessSince, Long.MAX_VALUE);
        if (villager.getWorld().getGameTime() - since < settings.surplusGrace) {
            return false;
        }
        int needed = settings.surplusCrowd;
        if (entry.control == Control.PULSED && entry.reason == Reason.SURPLUS) {
            needed = (int) Math.ceil(needed * CROWD_HYSTERESIS);
        }
        if (countIdleAround(villager.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, 1) < needed) {
            return false;
        }
        return !isNearRaid(villager.getWorld(), location);
    }

    private static boolean isJobless(Profession profession) {
        return profession.equals(Profession.NONE) || profession.equals(Profession.NITWIT);
    }

    private void updateIdle(Tracked entry, Villager villager, Location location, boolean idle) {
        UUID world = location.getWorld().getUID();
        long key = chunkKey(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        boolean moved = !world.equals(entry.worldId) || key != entry.chunkKey;
        if (entry.countedIdle && (!idle || moved)) {
            changeIdle(entry.worldId, entry.chunkKey, -1);
            entry.countedIdle = false;
        }
        if (idle && !entry.countedIdle) {
            changeIdle(world, key, 1);
            entry.countedIdle = true;
        }
        entry.worldId = world;
        entry.chunkKey = key;

        // When it lost its bed, for the surplus grace time. Written only when it changes.
        boolean stored = marks.has(villager, marks.homelessSince);
        if (idle && !stored) {
            marks.setLong(villager, marks.homelessSince, villager.getWorld().getGameTime());
        } else if (!idle && stored) {
            marks.remove(villager, marks.homelessSince);
        }
    }

    private void changeIdle(UUID world, long key, int delta) {
        Map<Long, int[]> chunks = idleCounts.computeIfAbsent(world, w -> new HashMap<>());
        int[] count = chunks.computeIfAbsent(key, k -> new int[1]);
        count[0] += delta;
        if (count[0] <= 0) {
            chunks.remove(key);
        }
    }

    /** Villagers without a bed and a job in the square of chunks around a chunk. */
    int countIdleAround(World world, int chunkX, int chunkZ, int radiusChunks) {
        Map<Long, int[]> chunks = idleCounts.get(world.getUID());
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int x = chunkX - radiusChunks; x <= chunkX + radiusChunks; x++) {
            for (int z = chunkZ - radiusChunks; z <= chunkZ + radiusChunks; z++) {
                int[] count = chunks.get(chunkKey(x, z));
                if (count != null) {
                    total += count[0];
                }
            }
        }
        return total;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private boolean isNearRaid(World world, Location location) {
        int now = plugin.getServer().getCurrentTick();
        if (now != raidsTick) {
            raidsThisTick.clear();
            raidsTick = now;
        }
        List<Raid> raids = raidsThisTick.computeIfAbsent(world.getUID(), id -> world.getRaids());
        for (Raid raid : raids) {
            if (raid.getStatus() == Raid.RaidStatus.ONGOING && isNear(location, raid.getLocation())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNear(Location a, Location b) {
        return a.getWorld().equals(b.getWorld()) && a.distanceSquared(b) <= RAID_DISTANCE * RAID_DISTANCE;
    }

    /** Wakes a frozen or pulsed villager up for a level up. */
    void beginLevelUp(Tracked entry) {
        entry.levelingUp = true;
        Villager villager = entry.villager;
        villager.setAware(true);
        if (marks.has(villager, marks.physicsOff)) {
            villager.setAI(true);
        }
    }

    void endLevelUp(Tracked entry) {
        entry.levelingUp = false;
        if (tracked.get(entry.id) != entry) {
            return;
        }
        // From scratch, so the AI goes off again whatever it ends up as.
        pulseBuckets.get(entry.pulsePhase).remove(entry);
        entry.control = Control.NONE;
        check(entry);
    }

    /** Whether the plugin itself turned this villager's AI off. */
    boolean controlsAi(Tracked entry) {
        return entry.isManaged() && marks.has(entry.villager, marks.aiOff);
    }

    private void apply(Tracked entry, Control desired, Reason reason) {
        Villager villager = entry.villager;
        boolean entering = entry.control != desired;
        if (isRestockedByPlugin(desired, reason) && (entering || !isRestockedByPlugin(entry.control, entry.reason))) {
            startedHandling.accept(villager);
        }
        if (desired != Control.PULSED) {
            pulseBuckets.get(entry.pulsePhase).remove(entry);
        }
        switch (desired) {
            case NONE -> {
                restoreAi(villager);
                restorePickup(villager);
                restoreCollisions(villager);
                restorePhysics(villager);
            }
            case FROZEN -> {
                aiOff(villager);
                pickupOff(villager);
                collisionsOff(villager);
                if (settings.freezeDisablesPhysics) {
                    physicsOff(villager);
                } else {
                    restorePhysics(villager);
                }
            }
            case PULSED -> {
                pickupOff(villager);
                if (reason == Reason.TRAPPED || settings.surplusDisablesCollisions) {
                    collisionsOff(villager);
                } else {
                    restoreCollisions(villager);
                }
                restorePhysics(villager);
                pulseBuckets.get(entry.pulsePhase).add(entry);
                // Starts in its off phase. Later checks leave the switching to the pulses.
                if (entering) {
                    aiOff(villager);
                }
            }
        }
        entry.control = desired;
        entry.reason = reason;
    }

    private static boolean isRestockedByPlugin(Control control, Reason reason) {
        return control == Control.FROZEN || (control == Control.PULSED && reason == Reason.TRAPPED);
    }

    // setAware(false) stops the AI step: sensors, brain, pathfinding, the job,
    // bed and bell searches, and the level up timer. Gravity, water and damage
    // keep working, and so does trading.
    private void aiOff(Villager villager) {
        if (marks.has(villager, marks.aiOff)) {
            villager.setAware(false);
        } else if (villager.isAware()) {
            villager.setAware(false);
            marks.set(villager, marks.aiOff);
        }
        // Unaware without our marker: another plugin turned it off, so it stays theirs.
    }

    private void restoreAi(Villager villager) {
        if (marks.has(villager, marks.aiOff)) {
            villager.setAware(true);
            marks.remove(villager, marks.aiOff);
        }
    }

    // Even without AI a villager looks for items to pick up every tick.
    private void pickupOff(Villager villager) {
        if (villager.getCanPickupItems()) {
            villager.setCanPickupItems(false);
            marks.set(villager, marks.pickupOff);
        }
    }

    private void restorePickup(Villager villager) {
        if (marks.has(villager, marks.pickupOff)) {
            villager.setCanPickupItems(true);
            marks.remove(villager, marks.pickupOff);
        }
    }

    // Even without AI a villager looks for entities to push every tick.
    // Collidable is not saved, so it is back on after a chunk load and turned
    // off again here. Off without our marker is another plugin's choice.
    private void collisionsOff(Villager villager) {
        if (villager.isCollidable()) {
            villager.setCollidable(false);
            marks.set(villager, marks.collisionsOff);
        }
    }

    private void restoreCollisions(Villager villager) {
        if (marks.has(villager, marks.collisionsOff)) {
            villager.setCollidable(true);
            marks.remove(villager, marks.collisionsOff);
        }
    }

    // NoAI also stops movement: no gravity, no water pushing.
    private void physicsOff(Villager villager) {
        if (villager.hasAI()) {
            villager.setAI(false);
            marks.set(villager, marks.physicsOff);
        }
    }

    private void restorePhysics(Villager villager) {
        if (marks.has(villager, marks.physicsOff)) {
            villager.setAI(true);
            marks.remove(villager, marks.physicsOff);
        }
    }
}
