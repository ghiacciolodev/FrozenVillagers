package dev.ghiacciolo.frozenvillagers;

import org.bukkit.Location;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Stops breeding when there is too much surplus around the parents. The
 * surplus is counted in a radius, not in one chunk, because a breeder
 * usually drops its babies into storage a few chunks away. No villager is
 * ever removed, only new babies stop being born.
 */
final class BirthLimit implements Listener {

    private final VillagerTracker tracker;
    private Settings settings;

    BirthLimit(VillagerTracker tracker, Settings settings) {
        this.tracker = tracker;
        this.settings = settings;
    }

    void reconfigure(Settings settings) {
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!settings.birthsEnabled
                || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.BREEDING
                || !(event.getEntity() instanceof Villager)) {
            return;
        }
        Location location = event.getLocation();
        if (!settings.isActiveIn(location.getWorld())) {
            return;
        }
        if (surplusAround(location) >= settings.birthsMaxSurplus) {
            event.setCancelled(true);
        }
    }

    /** Villagers without a bed and a job around a place, babies included. */
    int surplusAround(Location location) {
        int radiusChunks = (settings.birthsRadius + 15) / 16;
        return tracker.countIdleAround(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4,
                radiusChunks);
    }
}
