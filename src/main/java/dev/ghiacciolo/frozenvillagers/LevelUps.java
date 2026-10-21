package dev.ghiacciolo.frozenvillagers;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Lets villagers with the AI off level up.
 *
 * <p>Vanilla levels a villager up in its AI tick, 40 ticks after the trade
 * screen closes, and that tick never runs with the AI off. So the AI comes
 * back for a moment and vanilla does the level up with its new trades. The
 * plugin never levels up a villager itself: vanilla keeps an internal flag
 * that would give a second level later.
 */
final class LevelUps implements Listener {

    private static final long CHECK_EVERY = 5;
    // Vanilla needs 40 ticks. If nothing happened after this, give up.
    private static final long GIVE_UP_AFTER = 300;
    // A player trading pauses the vanilla timer, but not forever.
    private static final long GIVE_UP_WHILE_TRADING = 2400;

    private final Plugin plugin;
    private final VillagerTracker tracker;
    // A transient modifier is never saved, so a villager can't stay stuck
    // without speed if the server stops during a level up.
    private final AttributeModifier standStill;
    private final Set<UUID> running = new HashSet<>();
    private final Set<BukkitTask> tasks = new HashSet<>();

    LevelUps(Plugin plugin, VillagerTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.standStill = new AttributeModifier(new NamespacedKey(plugin, "level_up"), -1,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }

    @EventHandler
    public void onTradeClose(InventoryCloseEvent event) {
        if (event.getInventory() instanceof MerchantInventory inventory
                && inventory.getMerchant() instanceof Villager villager) {
            Tracked entry = tracker.get(villager);
            if (entry != null && tracker.controlsAi(entry) && isPending(villager)) {
                start(entry);
            }
        }
    }

    /**
     * Also checked every few seconds, in case the screen close was missed. The
     * level and XP of each attempt are remembered: vanilla only plans a level
     * up after a trade, which always changes the XP, so the same values never
     * need a second try.
     */
    void checkPending(Tracked entry) {
        if (entry.levelingUp || !tracker.controlsAi(entry)) {
            return;
        }
        Villager villager = entry.villager;
        if (isPending(villager) && !villager.isTrading() && attemptKey(villager) != entry.lastLevelAttempt) {
            start(entry);
        }
    }

    boolean isRunning(Villager villager) {
        return running.contains(villager.getUniqueId());
    }

    private static boolean isPending(Villager villager) {
        return Levels.isLevelUpPending(villager.getVillagerLevel(), villager.getVillagerExperience());
    }

    private static long attemptKey(Villager villager) {
        return (long) villager.getVillagerLevel() << 32 | villager.getVillagerExperience();
    }

    private void start(Tracked entry) {
        Villager villager = entry.villager;
        if (!running.add(entry.id)) {
            return;
        }
        entry.lastLevelAttempt = attemptKey(villager);
        int level = villager.getVillagerLevel();
        tracker.beginLevelUp(entry);
        AttributeInstance speed = villager.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(standStill.getKey()) == null) {
            speed.addTransientModifier(standStill);
        }

        long[] elapsed = {0};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            elapsed[0] += CHECK_EVERY;
            boolean done = !villager.isValid()
                    || villager.getVillagerLevel() > level
                    || !isPending(villager)
                    || elapsed[0] >= GIVE_UP_WHILE_TRADING
                    || (elapsed[0] >= GIVE_UP_AFTER && !villager.isTrading());
            if (done) {
                self[0].cancel();
                tasks.remove(self[0]);
                finish(entry);
            }
        }, CHECK_EVERY, CHECK_EVERY);
        tasks.add(self[0]);
    }

    private void finish(Tracked entry) {
        running.remove(entry.id);
        Villager villager = entry.villager;
        AttributeInstance speed = villager.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(standStill.getKey());
        }
        // If it was unloaded meanwhile, the tracker forgot it already and
        // checks it from scratch when it loads again.
        tracker.endLevelUp(entry);
    }

    void shutdown() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        for (Tracked entry : tracker.all()) {
            if (running.contains(entry.id)) {
                AttributeInstance speed = entry.villager.getAttribute(Attribute.MOVEMENT_SPEED);
                if (speed != null) {
                    speed.removeModifier(standStill.getKey());
                }
            }
        }
        running.clear();
    }
}
