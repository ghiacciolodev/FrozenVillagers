package fvtest;

import dev.ghiacciolo.frozenvillagers.FrozenVillagers;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Commands for the test suite, for the things that commands and RCON can't
 * do or see: the aware and collidable flags, brain memories, a trade that
 * earns XP the vanilla way, and the plugin's restock. Never install it on a
 * real server.
 */
public final class FvTest extends JavaPlugin {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            return false;
        }
        try {
            String result = run(sender, args);
            sender.sendMessage(result);
        } catch (Exception e) {
            sender.sendMessage("error " + e);
        }
        return true;
    }

    private String run(CommandSender sender, String[] args) throws Exception {
        String action = args[0];
        if (action.equals("disable") || action.equals("enable")) {
            // Like removing the plugin or adding it back, without a restart.
            var manager = getServer().getPluginManager();
            var plugin = manager.getPlugin("FrozenVillagers");
            if (action.equals("disable")) {
                manager.disablePlugin(plugin);
            } else {
                manager.enablePlugin(plugin);
            }
            return "ok " + plugin.isEnabled();
        }
        if (action.equals("place")) {
            // Places a block and fires the event a player placing it would.
            var world = getServer().getWorlds().getFirst();
            var block = world.getBlockAt(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            var replaced = block.getState();
            block.setType(org.bukkit.Material.matchMaterial(args[4]));
            var event = new org.bukkit.event.block.BlockPlaceEvent(block, replaced, block.getRelative(0, -1, 0),
                    new org.bukkit.inventory.ItemStack(block.getType()), null, true);
            getServer().getPluginManager().callEvent(event);
            return "placed " + block.getType();
        }
        if (action.equals("spawnbaby")) {
            // A villager "born" from breeding, so the birth limit sees it.
            Location location = new Location(getServer().getWorlds().getFirst(),
                    Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]));
            Villager baby = location.getWorld().spawn(location, Villager.class,
                    CreatureSpawnEvent.SpawnReason.BREEDING, v -> {
                        v.setBaby();
                        v.addScoreboardTag("fvt");
                    });
            return baby.isValid() ? "born" : "cancelled";
        }

        List<Villager> villagers = new ArrayList<>();
        for (Entity entity : getServer().selectEntities(sender, args[1])) {
            if (entity instanceof Villager villager) {
                villagers.add(villager);
            }
        }
        if (villagers.isEmpty()) {
            return "none";
        }
        Villager villager = villagers.getFirst();
        FrozenVillagers plugin = (FrozenVillagers) getServer().getPluginManager().getPlugin("FrozenVillagers");

        switch (action) {
            case "query" -> {
                int uses = 0;
                int demand = 0;
                for (MerchantRecipe recipe : villager.getRecipes()) {
                    uses += recipe.getUses();
                    demand += recipe.getDemand();
                }
                AttributeInstance speed = villager.getAttribute(Attribute.MOVEMENT_SPEED);
                boolean slowed = speed != null && !speed.getModifiers().isEmpty();
                return "aware=" + villager.isAware()
                        + " ai=" + villager.hasAI()
                        + " collidable=" + villager.isCollidable()
                        + " pickup=" + villager.getCanPickupItems()
                        + " optimized=" + plugin.isOptimized(villager)
                        + " level=" + villager.getVillagerLevel()
                        + " xp=" + villager.getVillagerExperience()
                        + " recipes=" + villager.getRecipeCount()
                        + " uses=" + uses
                        + " demand=" + demand
                        + " slowed=" + slowed
                        + " home=" + (villager.getMemory(MemoryKey.HOME) != null)
                        + " y=" + String.format(java.util.Locale.ROOT, "%.2f", villager.getLocation().getY());
            }
            case "aware" -> villager.setAware(Boolean.parseBoolean(args[2]));
            case "npc" -> villager.setMetadata("NPC", new FixedMetadataValue(this, true));
            case "home" -> villager.setMemory(MemoryKey.HOME, at(villager, args));
            case "jobsite" -> villager.setMemory(MemoryKey.JOB_SITE, at(villager, args));
            case "xp" -> villager.setVillagerExperience(Integer.parseInt(args[2]));
            case "use" -> {
                // Every trade used up, as if players had traded all of them.
                List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());
                for (MerchantRecipe recipe : recipes) {
                    recipe.setUses(recipe.getMaxUses());
                }
            }
            case "restock" -> {
                return "restocked=" + plugin.restockIfDue(villager);
            }
            case "vanillarestock" -> villager.restock();
            case "catchup" -> {
                // What vanilla does at dawn for restocks the villager missed.
                // It only catches up on restocks missing from today's two.
                villager.setRestocksToday(0);
                Object handle = villager.getClass().getMethod("getHandle").invoke(villager);
                Method catchUp = handle.getClass().getDeclaredMethod("catchUpDemand");
                catchUp.setAccessible(true);
                catchUp.invoke(handle);
            }
            case "trade" -> {
                return "xp=" + trade(villager);
            }
            default -> {
                return "unknown action " + action;
            }
        }
        return "ok";
    }

    private static Location at(Villager villager, String[] args) {
        return new Location(villager.getWorld(),
                Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
    }

    /**
     * Gives the villager the XP of its first trade the way vanilla does after
     * a real trade, including the planned level up. Through reflection,
     * because the API has no way to do it and this helper only runs on the
     * test server.
     */
    private static int trade(Villager villager) throws Exception {
        Object handle = villager.getClass().getMethod("getHandle").invoke(villager);
        Object offers = handle.getClass().getMethod("getOffers").invoke(handle);
        Object offer = ((List<?>) offers).getFirst();
        Method reward = find(handle.getClass(), "rewardTradeXp");
        reward.setAccessible(true);
        reward.invoke(handle, offer);
        return villager.getVillagerExperience();
    }

    private static Method find(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 1) {
                    return method;
                }
            }
        }
        throw new IllegalStateException("no method " + name);
    }
}
