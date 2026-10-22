package dev.ghiacciolo.frozenvillagers;

import dev.ghiacciolo.frozenvillagers.Marks.Mode;
import dev.ghiacciolo.frozenvillagers.Tracked.Control;
import dev.ghiacciolo.frozenvillagers.Tracked.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;

/** /frozenvillagers, for staff. */
final class Commands implements TabExecutor {

    private static final List<String> SUBCOMMANDS =
            List.of("freeze", "unfreeze", "auto", "adopt", "inspect", "status", "reload");
    private static final int LOOK_DISTANCE = 8;

    private final FrozenVillagers plugin;

    Commands(FrozenVillagers plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args.length > 2) {
            usage(sender, label);
            return true;
        }
        String target = args.length == 2 ? args[1] : null;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "freeze" -> setMode(sender, target, Mode.FROZEN);
            case "unfreeze" -> setMode(sender, target, Mode.EXCLUDED);
            case "auto" -> setMode(sender, target, Mode.AUTO);
            case "adopt" -> adopt(sender, target);
            case "inspect" -> inspect(sender, target);
            case "status" -> status(sender);
            case "reload" -> {
                List<String> warnings = plugin.reload();
                sender.sendMessage("FrozenVillagers reloaded.");
                for (String warning : warnings) {
                    sender.sendMessage("Warning: " + warning);
                }
            }
            default -> usage(sender, label);
        }
        return true;
    }

    private static void usage(CommandSender sender, String label) {
        sender.sendMessage("Usage: /" + label + " <freeze|unfreeze|auto|adopt|inspect> [radius|selector]");
        sender.sendMessage("       /" + label + " <status|reload>");
    }

    private void setMode(CommandSender sender, String target, Mode mode) {
        List<Villager> villagers = targets(sender, target);
        if (villagers == null) {
            return;
        }
        for (Villager villager : villagers) {
            plugin.marks().setMode(villager, mode);
            plugin.tracker().checkNow(villager);
        }
        String what = switch (mode) {
            case FROZEN -> "Frozen";
            case EXCLUDED -> "Unfrozen and excluded from the automatic optimizations";
            case AUTO -> "Back to automatic";
        };
        sender.sendMessage(what + ": " + villagers.size() + " villager" + (villagers.size() == 1 ? "" : "s") + ".");
    }

    /**
     * Takes over villagers whose AI another plugin turned off, for example
     * BubbleVillagers or VillagerOptimizer before removing it. Normally the
     * plugin never touches those. After adopting, they are handled like any
     * other villager: trapped ones are slowed down, the others get their AI back.
     *
     * <p>The other plugin's markers are removed too, otherwise it would turn
     * the AI off again the next time the villager loads, as long as it is
     * still installed.
     */
    private void adopt(CommandSender sender, String target) {
        List<Villager> villagers = targets(sender, target);
        if (villagers == null) {
            return;
        }
        int adopted = 0;
        int unmarked = 0;
        Marks marks = plugin.marks();
        for (Villager villager : villagers) {
            if (marks.clearOtherOptimizers(villager)) {
                unmarked++;
            }
            if (!villager.isAware() && !marks.has(villager, marks.aiOff)) {
                marks.set(villager, marks.aiOff);
                marks.setMode(villager, Mode.AUTO);
                plugin.tracker().checkNow(villager);
                adopted++;
            }
        }
        sender.sendMessage("Adopted: " + adopted + " villager" + (adopted == 1 ? "" : "s")
                + " whose AI another plugin had turned off.");
        if (unmarked > 0) {
            sender.sendMessage("Removed the markers of another optimizer plugin from " + unmarked + ".");
        }
    }

    /**
     * The villagers a command is about: the one the player looks at, every
     * villager within a radius of the player, or an entity selector, which
     * also works from the console.
     */
    private List<Villager> targets(CommandSender sender, String target) {
        if (target == null) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("From the console, give a selector, for example @e[type=villager,limit=1].");
                return null;
            }
            if (player.getTargetEntity(LOOK_DISTANCE) instanceof Villager villager) {
                return List.of(villager);
            }
            sender.sendMessage("Look at a villager within " + LOOK_DISTANCE + " blocks, or give a radius.");
            return null;
        }

        if (target.chars().allMatch(Character::isDigit)) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("A radius only works for players. From the console, give a selector.");
                return null;
            }
            int radius = Integer.parseInt(target);
            int max = plugin.settings().maxCommandRadius;
            if (radius < 1 || radius > max) {
                sender.sendMessage("The radius must be between 1 and " + max + ".");
                return null;
            }
            return new ArrayList<>(player.getLocation().getNearbyEntitiesByType(Villager.class, radius));
        }

        List<Entity> selected;
        try {
            selected = plugin.getServer().selectEntities(sender, target);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("Not a radius or a valid selector: " + target);
            return null;
        }
        List<Villager> villagers = new ArrayList<>();
        for (Entity entity : selected) {
            if (entity instanceof Villager villager) {
                villagers.add(villager);
            }
        }
        return villagers;
    }

    private void inspect(CommandSender sender, String target) {
        List<Villager> villagers = targets(sender, target);
        if (villagers == null) {
            return;
        }
        if (villagers.size() != 1) {
            sender.sendMessage("Inspect needs exactly one villager, found " + villagers.size() + ".");
            return;
        }
        Villager villager = villagers.getFirst();
        Tracked entry = plugin.tracker().checkNow(villager);

        sender.sendMessage("Villager: " + villager.getProfession().getKey().getKey()
                + ", level " + villager.getVillagerLevel() + ", " + villager.getVillagerExperience() + " xp"
                + ", " + (villager.isAdult() ? "adult" : "baby")
                + ", bed: " + yesNo(villager.getMemory(MemoryKey.HOME) != null)
                + ", workstation: " + describe(Workstations.find(villager, plugin.settings().workstationReach)) + ".");
        Mode mode = plugin.marks().mode(villager);
        sender.sendMessage("Mode: " + mode.name().toLowerCase(Locale.ROOT) + ". State: " + state(entry) + ".");
        sender.sendMessage("Why: " + entry.why + ".");
        String restock = plugin.restocker().explainBlocker(villager);
        sender.sendMessage("Restock: " + (restock == null ? "will restock when a player opens the trades" : restock) + ".");
    }

    private String state(Tracked entry) {
        if (plugin.levelUps().isRunning(entry.villager)) {
            return "awake to level up";
        }
        return switch (entry.control) {
            case NONE -> "normal";
            case FROZEN -> "frozen";
            case PULSED -> "pulsed (" + (entry.reason == Reason.TRAPPED ? "trapped" : "surplus") + ")";
        };
    }

    private void status(CommandSender sender) {
        int total = 0;
        int frozen = 0;
        int trapped = 0;
        int surplus = 0;
        int foreign = 0;
        for (Tracked entry : plugin.tracker().all()) {
            total++;
            if (entry.control == Control.FROZEN) {
                frozen++;
            } else if (entry.control == Control.PULSED) {
                if (entry.reason == Reason.TRAPPED) {
                    trapped++;
                } else {
                    surplus++;
                }
            } else if (!entry.villager.isAware()) {
                foreign++;
            }
        }
        sender.sendMessage("FrozenVillagers " + plugin.getPluginMeta().getVersion() + ".");
        sender.sendMessage("Loaded villagers: " + total + ", frozen by staff: " + frozen
                + ", pulsed trapped: " + trapped + ", pulsed surplus: " + surplus + ".");
        sender.sendMessage("Without AI because of another plugin: " + foreign + ".");
        double cost = plugin.tracker().averageCostMicros();
        sender.sendMessage(cost < 0
                ? "Plugin cost: measuring, ready after the first minute."
                : String.format(Locale.ROOT, "Plugin cost: %.1f microseconds per tick over the last minute (%.2f%% of a 50 ms tick).",
                        cost, cost / 500));
    }

    private static String describe(Location location) {
        return location == null ? "none" : location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ();
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(option -> option.startsWith(typed)).toList();
        }
        return List.of();
    }
}
