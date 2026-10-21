package dev.ghiacciolo.frozenvillagers;

import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * What the plugin stores on each villager, in its persistent data. Every
 * change the plugin makes to a villager has a marker here, so it only ever
 * undoes its own changes and never touches what another plugin did.
 */
final class Marks {

    /** The staff's choice for a villager. */
    enum Mode { AUTO, FROZEN, EXCLUDED }

    /** Villagers with this scoreboard tag are never touched by the automatic features. */
    static final String IGNORE_TAG = "frozenvillagers_ignore";

    private final NamespacedKey mode;
    final NamespacedKey aiOff;
    final NamespacedKey pickupOff;
    final NamespacedKey collisionsOff;
    final NamespacedKey physicsOff;
    final NamespacedKey restockedAt;
    final NamespacedKey homelessSince;
    final NamespacedKey demand;

    Marks(Plugin plugin) {
        mode = new NamespacedKey(plugin, "mode");
        aiOff = new NamespacedKey(plugin, "ai_off");
        pickupOff = new NamespacedKey(plugin, "pickup_off");
        collisionsOff = new NamespacedKey(plugin, "collisions_off");
        physicsOff = new NamespacedKey(plugin, "physics_off");
        restockedAt = new NamespacedKey(plugin, "restocked_at");
        homelessSince = new NamespacedKey(plugin, "homeless_since");
        demand = new NamespacedKey(plugin, "demand");
    }

    Mode mode(Villager villager) {
        String raw = villager.getPersistentDataContainer().get(mode, PersistentDataType.STRING);
        if (raw == null) {
            return Mode.AUTO;
        }
        try {
            return Mode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Mode.AUTO;
        }
    }

    void setMode(Villager villager, Mode value) {
        PersistentDataContainer data = villager.getPersistentDataContainer();
        if (value == Mode.AUTO) {
            data.remove(mode);
        } else {
            data.set(mode, PersistentDataType.STRING, value.name().toLowerCase(Locale.ROOT));
        }
    }

    boolean has(Villager villager, NamespacedKey key) {
        return villager.getPersistentDataContainer().has(key);
    }

    void set(Villager villager, NamespacedKey key) {
        villager.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, true);
    }

    void remove(Villager villager, NamespacedKey key) {
        villager.getPersistentDataContainer().remove(key);
    }

    long getLong(Villager villager, NamespacedKey key, long fallback) {
        Long value = villager.getPersistentDataContainer().get(key, PersistentDataType.LONG);
        return value == null ? fallback : value;
    }

    void setLong(Villager villager, NamespacedKey key, long value) {
        villager.getPersistentDataContainer().set(key, PersistentDataType.LONG, value);
    }

    int[] getInts(Villager villager, NamespacedKey key) {
        return villager.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
    }

    void setInts(Villager villager, NamespacedKey key, int[] values) {
        villager.getPersistentDataContainer().set(key, PersistentDataType.INTEGER_ARRAY, values);
    }
}
