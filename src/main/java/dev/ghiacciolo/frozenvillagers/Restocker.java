package dev.ghiacciolo.frozenvillagers;

import dev.ghiacciolo.frozenvillagers.Settings.RestockMode;
import dev.ghiacciolo.frozenvillagers.Tracked.Control;
import dev.ghiacciolo.frozenvillagers.Tracked.Reason;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerReplenishTradeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.plugin.Plugin;

/**
 * Vanilla villagers restock by walking to their workstation during work
 * hours. Villagers the plugin freezes or slows down can't do that, so they
 * restock here instead, at fixed times of day, when a player opens their
 * trades.
 */
final class Restocker implements Listener {

    private final Plugin plugin;
    private final Marks marks;
    private final VillagerTracker tracker;
    private Settings settings;

    // Set while the plugin restocks, so its own restocks are not blocked below.
    private boolean restocking;
    // Villagers whose demand is put back again on the next tick.
    private final Set<UUID> restoring = new HashSet<>();

    Restocker(Plugin plugin, Marks marks, VillagerTracker tracker, Settings settings) {
        this.plugin = plugin;
        this.marks = marks;
        this.tracker = tracker;
        this.settings = settings;
    }

    void reconfigure(Settings settings) {
        this.settings = settings;
    }

    /** Whether the plugin restocks this villager instead of vanilla. */
    boolean handles(Villager villager) {
        Tracked entry = tracker.get(villager);
        if (entry == null) {
            return false;
        }
        return entry.control == Control.FROZEN
                || (entry.control == Control.PULSED && entry.reason == Reason.TRAPPED);
    }

    // Runs before the trade screen opens, so the player sees the restocked trades.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager) || !restockIfDue(villager)) {
            return;
        }
        if (!settings.restockMessage.isEmpty()) {
            long ticks = settings.restockWindows.next(villager.getWorld().getFullTime()) - villager.getWorld().getFullTime();
            String message = settings.restockMessage.replace("%next%", RestockWindows.formatTicks(ticks));
            event.getPlayer().sendMessage(MiniMessage.miniMessage().deserialize(message));
        }
    }

    /**
     * A slowed down villager still gets a bit of AI and may try to restock the
     * vanilla way. Its restocks follow the plugin's times, so those are blocked.
     *
     * <p>Vanilla updates the demand even when the restock is blocked: before
     * this event for a normal restock, after it for the dawn catch-up. So the
     * remembered demand is put back now and on the next tick, or prices would
     * drift with restocks that never happened.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onReplenish(VillagerReplenishTradeEvent event) {
        if (restocking || !settings.restockEnabled
                || !(event.getEntity() instanceof Villager villager) || !handles(villager)) {
            return;
        }
        event.setCancelled(true);
        restoreDemand(villager);
        if (restoring.add(villager.getUniqueId())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                restoring.remove(villager.getUniqueId());
                if (villager.isValid()) {
                    restoreDemand(villager);
                }
            });
        }
    }

    /**
     * Remembers the demand of each trade, the one to keep while the plugin
     * handles the villager. Taken when handling starts (also after a level up)
     * and after each of the plugin's restocks.
     */
    void rememberDemand(Villager villager) {
        List<MerchantRecipe> recipes = villager.getRecipes();
        int[] demand = new int[recipes.size()];
        for (int i = 0; i < demand.length; i++) {
            demand[i] = recipes.get(i).getDemand();
        }
        marks.setInts(villager, marks.demand, demand);
    }

    private void restoreDemand(Villager villager) {
        int[] demand = marks.getInts(villager, marks.demand);
        if (demand == null) {
            return;
        }
        List<MerchantRecipe> recipes = villager.getRecipes();
        for (int i = 0; i < Math.min(demand.length, recipes.size()); i++) {
            recipes.get(i).setDemand(demand[i]);
        }
    }

    /** Restocks the villager if a restock window has passed. Returns true if it did. */
    boolean restockIfDue(Villager villager) {
        return explainBlocker(villager) == null && restock(villager);
    }

    /** Returns why the villager can't restock now, or null if it can. */
    String explainBlocker(Villager villager) {
        if (!settings.restockEnabled) {
            return "restocks are turned off in the config";
        }
        if (!handles(villager)) {
            return "it restocks the vanilla way";
        }
        // Clicking a villager just to look at its prices must not spend the window.
        if (!hasUsedTrades(villager)) {
            return "no trade has been used since the last restock";
        }
        World world = villager.getWorld();
        long last = marks.getLong(villager, marks.restockedAt, Long.MIN_VALUE);
        if (!settings.restockWindows.isDue(last, world.getFullTime())) {
            long ticks = settings.restockWindows.next(world.getFullTime()) - world.getFullTime();
            return "it already restocked, the next restock is in " + RestockWindows.formatTicks(ticks);
        }
        if (settings.restockNeedsWorkstation && Workstations.find(villager, settings.workstationReach) == null) {
            return "its workstation is missing or too far";
        }
        return null;
    }

    private boolean restock(Villager villager) {
        restocking = true;
        try {
            if (settings.restockMode == RestockMode.VANILLA) {
                // Also updates the demand, so prices change like in vanilla.
                villager.restock();
            } else {
                for (MerchantRecipe recipe : villager.getRecipes()) {
                    VillagerReplenishTradeEvent event = new VillagerReplenishTradeEvent(villager, recipe);
                    if (event.callEvent()) {
                        event.getRecipe().setUses(0);
                    }
                }
            }
        } finally {
            restocking = false;
        }
        marks.setLong(villager, marks.restockedAt, villager.getWorld().getFullTime());
        // In vanilla mode the restock changed the demand on purpose.
        rememberDemand(villager);
        return true;
    }

    private static boolean hasUsedTrades(Villager villager) {
        for (MerchantRecipe recipe : villager.getRecipes()) {
            if (recipe.getUses() > 0) {
                return true;
            }
        }
        return false;
    }
}
