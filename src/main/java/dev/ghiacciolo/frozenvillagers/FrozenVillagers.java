package dev.ghiacciolo.frozenvillagers;

import java.util.List;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class FrozenVillagers extends JavaPlugin {

    private Settings settings;
    private Marks marks;
    private VillagerTracker tracker;
    private Restocker restocker;
    private LevelUps levelUps;
    private BirthLimit birthLimit;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = loadSettings();
        marks = new Marks(this);
        tracker = new VillagerTracker(this, marks, settings);
        restocker = new Restocker(this, marks, tracker, settings);
        levelUps = new LevelUps(this, tracker);
        birthLimit = new BirthLimit(tracker, settings);
        tracker.onChecked(levelUps::checkPending);
        tracker.onStartedHandling(restocker::rememberDemand);

        PluginManager plugins = getServer().getPluginManager();
        plugins.registerEvents(tracker, this);
        plugins.registerEvents(restocker, this);
        plugins.registerEvents(levelUps, this);
        plugins.registerEvents(birthLimit, this);

        PluginCommand command = getCommand("frozenvillagers");
        if (command != null) {
            Commands commands = new Commands(this);
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }

        tracker.start();
    }

    @Override
    public void onDisable() {
        if (levelUps != null) {
            levelUps.shutdown();
        }
        if (tracker != null) {
            tracker.shutdown();
        }
    }

    private Settings loadSettings() {
        reloadConfig();
        Settings loaded = new Settings(getConfig(), getServer());
        for (String warning : loaded.warnings) {
            getLogger().warning(warning);
        }
        return loaded;
    }

    /** Reloads the config and applies it to every loaded villager. Returns the warnings. */
    List<String> reload() {
        settings = loadSettings();
        tracker.reconfigure(settings);
        restocker.reconfigure(settings);
        birthLimit.reconfigure(settings);
        return settings.warnings;
    }

    Settings settings() {
        return settings;
    }

    Marks marks() {
        return marks;
    }

    VillagerTracker tracker() {
        return tracker;
    }

    Restocker restocker() {
        return restocker;
    }

    LevelUps levelUps() {
        return levelUps;
    }

    /** Whether FrozenVillagers currently has the villager frozen or slowed down. */
    public boolean isOptimized(Villager villager) {
        Tracked entry = tracker.get(villager);
        return entry != null && entry.isManaged();
    }

    /**
     * Restocks a frozen or slowed down villager if one of the restock times
     * has passed, like when a player opens its trades. For plugins that open
     * villager trades in their own way. Returns true if it restocked.
     */
    public boolean restockIfDue(Villager villager) {
        return restocker.restockIfDue(villager);
    }
}
