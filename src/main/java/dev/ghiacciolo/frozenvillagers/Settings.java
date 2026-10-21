package dev.ghiacciolo.frozenvillagers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/** The config, read once per load or reload, with warnings about bad values. */
final class Settings {

    enum RestockMode { RESET_USES, VANILLA }

    final Set<String> worlds;
    final int checkInterval;

    final boolean freezeDisablesPhysics;
    final int maxCommandRadius;

    final int pulseCycle;
    final int pulseActive;

    final boolean trappedEnabled;

    final boolean surplusEnabled;
    final int surplusCrowd;
    final long surplusGrace;
    final boolean surplusDisablesCollisions;

    final boolean birthsEnabled;
    final int birthsMaxSurplus;
    final int birthsRadius;

    final boolean restockEnabled;
    final RestockWindows restockWindows;
    final long[] restockTimes;
    final RestockMode restockMode;
    final boolean restockNeedsWorkstation;
    final double workstationReach;
    final String restockMessage;

    final List<String> warnings = new ArrayList<>();

    Settings(FileConfiguration config, Server server) {
        worlds = new HashSet<>(config.getStringList("worlds"));
        for (String name : worlds) {
            // A typo here turns the automatic features off in that world
            // without any error. Only a warning, the world may load later.
            if (server.getWorld(name) == null) {
                warnings.add("World '" + name + "' in the worlds list is not loaded. "
                        + "Check the name, it is case sensitive.");
            }
        }
        checkInterval = atLeast(config, "check-interval", 100, 20);

        freezeDisablesPhysics = config.getBoolean("freeze.disable-physics", false);
        maxCommandRadius = atLeast(config, "freeze.max-command-radius", 32, 1);

        pulseCycle = atLeast(config, "pulse.cycle", 200, 20);
        int active = atLeast(config, "pulse.active", 20, 1);
        if (active >= pulseCycle) {
            warnings.add("pulse.active must be lower than pulse.cycle, using " + pulseCycle / 10 + ".");
            active = pulseCycle / 10;
        }
        pulseActive = active;

        trappedEnabled = config.getBoolean("trapped.enabled", true);

        surplusEnabled = config.getBoolean("surplus.enabled", true);
        surplusCrowd = atLeast(config, "surplus.crowd", 12, 2);
        surplusGrace = Math.max(0, config.getLong("surplus.grace", 6000));
        surplusDisablesCollisions = config.getBoolean("surplus.disable-collisions", false);

        birthsEnabled = config.getBoolean("births.enabled", false);
        birthsMaxSurplus = atLeast(config, "births.max-surplus", 24, 1);
        birthsRadius = atLeast(config, "births.radius", 64, 16);

        restockEnabled = config.getBoolean("restock.enabled", true);
        restockTimes = readTimes(config);
        restockWindows = new RestockWindows(restockTimes);
        restockMode = readMode(config);
        restockNeedsWorkstation = config.getBoolean("restock.require-workstation", false);
        workstationReach = Math.max(1, config.getDouble("restock.workstation-reach", 2.5));
        restockMessage = config.getString("restock.message", "");
    }

    boolean isActiveIn(World world) {
        return worlds.isEmpty() || worlds.contains(world.getName());
    }

    private int atLeast(FileConfiguration config, String path, int fallback, int min) {
        int value = config.getInt(path, fallback);
        if (value < min) {
            warnings.add(path + " must be at least " + min + ", using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    private long[] readTimes(FileConfiguration config) {
        TreeSet<Long> times = new TreeSet<>();
        for (Object raw : config.getList("restock.times", List.of(1000, 6000, 11000))) {
            long time;
            try {
                time = raw instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                warnings.add("restock.times: '" + raw + "' is not a number, skipped.");
                continue;
            }
            if (time < 0 || time >= RestockWindows.DAY) {
                warnings.add("restock.times: " + time + " is not between 0 and 23999, skipped.");
                continue;
            }
            times.add(time);
        }
        if (times.isEmpty()) {
            warnings.add("restock.times has no valid time, using 1000, 6000 and 11000.");
            times.addAll(List.of(1000L, 6000L, 11000L));
        }
        return times.stream().mapToLong(Long::longValue).toArray();
    }

    private RestockMode readMode(FileConfiguration config) {
        String raw = config.getString("restock.mode", "reset-uses");
        try {
            return RestockMode.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            warnings.add("restock.mode must be reset-uses or vanilla, using reset-uses.");
            return RestockMode.RESET_USES;
        }
    }
}
