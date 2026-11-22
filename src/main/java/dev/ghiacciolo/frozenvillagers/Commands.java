package dev.ghiacciolo.frozenvillagers;

import dev.ghiacciolo.frozenvillagers.Marks.Mode;
import dev.ghiacciolo.frozenvillagers.Tracked.Reason;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
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
            List.of("check", "inspect", "scan", "status", "freeze", "unfreeze", "auto", "adopt", "reload");
    private static final int LOOK_DISTANCE = 8;
    private static final int SCAN_DEFAULT_RADIUS = 16;
    // /fv scan marks the villagers that are not optimized with particles for this long.
    private static final int MARK_TICKS = 200;
    private static final int MARK_EVERY = 10;
    private static final Particle.DustOptions MARK = new Particle.DustOptions(Color.RED, 1.5f);

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
            case "check" -> check(sender, target);
            case "inspect" -> inspect(sender, target);
            case "scan" -> scan(sender, target);
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
        sender.sendMessage("Usage: /" + label + " <check|inspect|freeze|unfreeze|auto|adopt> [radius|selector]");
        sender.sendMessage("       /" + label + " scan [radius|selector]");
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
        for (Villager villager : villagers) {
            if (plugin.marks().clearOtherOptimizers(villager)) {
                unmarked++;
            }
            if (plugin.tracker().takeOver(villager)) {
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
            int max = plugin.settings().maxCommandRadius;
            int radius;
            try {
                radius = Integer.parseInt(target);
            } catch (NumberFormatException e) {
                // Too many digits for an int: out of range like any other big number.
                radius = Integer.MAX_VALUE;
            }
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

    /** One line: is the villager optimized, and if not, why. */
    private void check(CommandSender sender, String target) {
        Villager villager = one(sender, target, "Check");
        if (villager == null) {
            return;
        }
        Tracked entry = plugin.tracker().checkNow(villager);
        sender.sendMessage(verdict(entry));
    }

    private void inspect(CommandSender sender, String target) {
        Villager villager = one(sender, target, "Inspect");
        if (villager == null) {
            return;
        }
        Tracked entry = plugin.tracker().checkNow(villager);

        sender.sendMessage(verdict(entry));
        sender.sendMessage("State: " + state(entry) + ". Mode: " + plugin.marks().mode(villager).name().toLowerCase(Locale.ROOT)
                + " (/fv freeze, unfreeze, auto).");
        sender.sendMessage("Why: " + entry.why + ".");
        sender.sendMessage("Right now: AI " + onOff(villager.isAware()) + ", item pickup " + onOff(villager.getCanPickupItems())
                + ", pushing " + onOff(villager.isCollidable()) + ", physics " + onOff(villager.hasAI()) + ".");
        sender.sendMessage("Villager: " + villager.getProfession().getKey().getKey()
                + ", level " + villager.getVillagerLevel() + ", " + villager.getVillagerExperience() + " xp"
                + ", " + (villager.isAdult() ? "adult" : "baby")
                + ", bed: " + yesNo(villager.getMemory(MemoryKey.HOME) != null)
                + ", workstation: " + describe(Workstations.find(villager, plugin.settings().workstationReach))
                + ".");
        String restock = plugin.restocker().explainBlocker(villager);
        sender.sendMessage("Restock: " + (restock == null ? "will restock when a player opens the trades" : restock) + ".");
    }

    /**
     * Counts the villagers in an area, optimized or not and why, and marks
     * the ones that are not optimized with particles that only the player
     * running the command sees.
     */
    private void scan(CommandSender sender, String target) {
        if (target == null && sender instanceof Player) {
            target = String.valueOf(Math.min(SCAN_DEFAULT_RADIUS, plugin.settings().maxCommandRadius));
        }
        if (target == null) {
            sender.sendMessage("From the console, give a selector, for example @e[type=villager,distance=..16].");
            return;
        }
        List<Villager> villagers = targets(sender, target);
        if (villagers == null) {
            return;
        }
        List<Tracked> entries = new ArrayList<>();
        for (Villager villager : villagers) {
            entries.add(plugin.tracker().checkNow(villager));
        }
        Counts counts = count(entries);
        sender.sendMessage("Villagers found: " + entries.size() + ", AI on right now: " + counts.aiOn + ".");
        sender.sendMessage(Component.text("Optimized: " + counts.optimized() + ". " + counts.optimizedDetail() + ".",
                NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Not optimized: " + counts.notOptimized() + counts.notOptimizedDetail() + ".",
                counts.notOptimized() == 0 ? NamedTextColor.GREEN : NamedTextColor.RED));

        List<Villager> marked = new ArrayList<>();
        for (Tracked entry : entries) {
            if (!entry.isManaged()) {
                marked.add(entry.villager);
            }
        }
        if (sender instanceof Player player && !marked.isEmpty()) {
            markWithParticles(player, marked);
            sender.sendMessage("They are marked with red particles for " + MARK_TICKS / 20
                    + " seconds. Look at one and use /fv inspect to see why.");
        }
    }

    private void markWithParticles(Player player, List<Villager> villagers) {
        int[] runs = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || ++runs[0] * MARK_EVERY > MARK_TICKS) {
                task.cancel();
                return;
            }
            for (Villager villager : villagers) {
                if (villager.isValid() && villager.getWorld().equals(player.getWorld())) {
                    Location above = villager.getLocation().add(0, villager.getHeight() + 0.4, 0);
                    player.spawnParticle(Particle.DUST, above, 6, 0.15, 0.25, 0.15, 0, MARK);
                }
            }
        }, 0, MARK_EVERY);
    }

    /** Green if the plugin optimizes it, red with the reason if not. */
    private Component verdict(Tracked entry) {
        Villager villager = entry.villager;
        String who = capitalize(villager.getProfession().getKey().getKey()) + " at " + describe(villager.getLocation());
        if (plugin.levelUps().isRunning(villager)) {
            return Component.text(who + ": optimized, awake for a moment to level up.", NamedTextColor.GREEN);
        }
        return switch (entry.control) {
            case NONE -> Component.text(who + ": NOT optimized, " + entry.why + ".", NamedTextColor.RED);
            case FROZEN -> Component.text(who + ": optimized, frozen by staff. AI always off.", NamedTextColor.GREEN);
            case ASLEEP -> Component.text(who + ": optimized, trapped in its cell. AI off, it only wakes up when hurt, "
                    + "to level up or for a bed, workstation or bell placed nearby.", NamedTextColor.GREEN);
            case PULSED -> Component.text(who + ": optimized, " + (entry.reason == Reason.TRAPPED ? "trapped" : "surplus")
                    + ". AI on for " + seconds(plugin.settings().pulseActive) + " every "
                    + seconds(plugin.settings().pulseCycle) + ".", NamedTextColor.GREEN);
        };
    }

    /** The one villager a command is about, or null after telling the sender why. */
    private Villager one(CommandSender sender, String target, String what) {
        List<Villager> villagers = targets(sender, target);
        if (villagers == null) {
            return null;
        }
        if (villagers.size() != 1) {
            sender.sendMessage(what + " needs exactly one villager, found " + villagers.size() + ".");
            return null;
        }
        return villagers.getFirst();
    }

    private String state(Tracked entry) {
        if (plugin.levelUps().isRunning(entry.villager)) {
            return "awake to level up";
        }
        return switch (entry.control) {
            case NONE -> "normal";
            case FROZEN -> "frozen";
            case ASLEEP -> "asleep (trapped)";
            case PULSED -> "pulsed (" + (entry.reason == Reason.TRAPPED ? "trapped" : "surplus") + ")";
        };
    }

    /** How many villagers are in each state, and why the others are not optimized. */
    private static final class Counts {
        int frozen;
        int trapped;
        int surplus;
        int aiOn;
        final Map<String, Integer> notOptimized = new LinkedHashMap<>();

        int optimized() {
            return frozen + trapped + surplus;
        }

        int notOptimized() {
            return notOptimized.values().stream().mapToInt(Integer::intValue).sum();
        }

        String optimizedDetail() {
            return "Frozen by staff: " + frozen + ", trapped: " + trapped + ", surplus: " + surplus;
        }

        /** The reasons, most common first. */
        String notOptimizedDetail() {
            if (notOptimized.isEmpty()) {
                return "";
            }
            StringJoiner joiner = new StringJoiner(", ", ". ", "");
            notOptimized.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(reason -> joiner.add(reason.getKey() + ": " + reason.getValue()));
            return joiner.toString();
        }
    }

    private static Counts count(Iterable<Tracked> entries) {
        Counts counts = new Counts();
        for (Tracked entry : entries) {
            if (entry.villager.isAware() && entry.villager.hasAI()) {
                counts.aiOn++;
            }
            switch (entry.control) {
                case FROZEN -> counts.frozen++;
                case ASLEEP, PULSED -> {
                    if (entry.reason == Reason.TRAPPED) {
                        counts.trapped++;
                    } else {
                        counts.surplus++;
                    }
                }
                case NONE -> counts.notOptimized.merge(entry.whyShort, 1, Integer::sum);
            }
        }
        return counts;
    }

    private void status(CommandSender sender) {
        Counts counts = count(plugin.tracker().all());
        int total = counts.optimized() + counts.notOptimized();
        sender.sendMessage("FrozenVillagers " + plugin.getPluginMeta().getVersion() + ".");
        sender.sendMessage("Loaded villagers: " + total + ", AI on right now: " + counts.aiOn + ".");
        sender.sendMessage(Component.text("Optimized: " + counts.optimized() + ". " + counts.optimizedDetail() + ".",
                NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Not optimized: " + counts.notOptimized() + counts.notOptimizedDetail() + ".",
                NamedTextColor.YELLOW));
        double cost = plugin.tracker().averageCostMicros();
        sender.sendMessage(cost < 0
                ? "Plugin cost: measuring, ready after the first minute."
                : String.format(Locale.ROOT, "Plugin cost: %.1f microseconds per tick over the last minute (%.2f%% of a 50 ms tick).",
                        cost, cost / 500));
        sender.sendMessage("/fv scan shows the villagers around you, /fv check the one you look at.");
    }

    private static String seconds(int ticks) {
        return ticks % 20 == 0 ? ticks / 20 + " s" : String.format(Locale.ROOT, "%.1f s", ticks / 20.0);
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1).replace('_', ' ');
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
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
