package dev.ghiacciolo.frozenvillagers;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Villager.Profession;
import org.bukkit.entity.memory.MemoryKey;

/** The workstation blocks of each profession, as in vanilla. */
final class Workstations {

    private static final Map<Profession, Set<Material>> BLOCKS = Map.ofEntries(
            Map.entry(Profession.ARMORER, Set.of(Material.BLAST_FURNACE)),
            Map.entry(Profession.BUTCHER, Set.of(Material.SMOKER)),
            Map.entry(Profession.CARTOGRAPHER, Set.of(Material.CARTOGRAPHY_TABLE)),
            Map.entry(Profession.CLERIC, Set.of(Material.BREWING_STAND)),
            Map.entry(Profession.FARMER, Set.of(Material.COMPOSTER)),
            Map.entry(Profession.FISHERMAN, Set.of(Material.BARREL)),
            Map.entry(Profession.FLETCHER, Set.of(Material.FLETCHING_TABLE)),
            // Any cauldron works, filled or not.
            Map.entry(Profession.LEATHERWORKER, Set.of(Material.CAULDRON, Material.WATER_CAULDRON,
                    Material.LAVA_CAULDRON, Material.POWDER_SNOW_CAULDRON)),
            Map.entry(Profession.LIBRARIAN, Set.of(Material.LECTERN)),
            Map.entry(Profession.MASON, Set.of(Material.STONECUTTER)),
            Map.entry(Profession.SHEPHERD, Set.of(Material.LOOM)),
            Map.entry(Profession.TOOLSMITH, Set.of(Material.SMITHING_TABLE)),
            Map.entry(Profession.WEAPONSMITH, Set.of(Material.GRINDSTONE)));

    private static final Set<Material> ALL = BLOCKS.values().stream()
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());

    private Workstations() {
    }

    static boolean isWorkstation(Material block) {
        return ALL.contains(block);
    }

    /**
     * Returns the villager's workstation if it is still there and within
     * reach blocks of its feet, or null. The brain remembers the workstation
     * even while the AI is off, but doesn't notice when it is broken, so the
     * block is checked here.
     */
    static Location find(Villager villager, double reach) {
        Location site = villager.getMemory(MemoryKey.JOB_SITE);
        if (site == null || !villager.getWorld().equals(site.getWorld())) {
            return null;
        }
        // Distance first, so a far away workstation never loads its chunk.
        if (site.toCenterLocation().distanceSquared(villager.getLocation()) > reach * reach) {
            return null;
        }
        Material block = site.getBlock().getType();
        if (!BLOCKS.getOrDefault(villager.getProfession(), Set.of()).contains(block)) {
            return null;
        }
        return site;
    }
}
