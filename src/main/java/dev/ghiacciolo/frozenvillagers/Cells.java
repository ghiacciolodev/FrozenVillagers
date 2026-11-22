package dev.ghiacciolo.frozenvillagers;

import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;

/** Decides whether a villager is locked in a place it can't walk out of. */
final class Cells {

    private static final int[][] SIDES = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
    private static final String[] SIDE_NAMES = {"north", "south", "east", "west"};

    // Shrinks the hitbox a little, so standing on the floor or touching a wall
    // doesn't count as a collision.
    private static final double MARGIN = 1.0E-3;

    private Cells() {
    }

    static boolean isTrapped(Villager villager) {
        return findExit(villager) == null;
    }

    /**
     * Returns how the villager could get out, or null if it is trapped.
     *
     * <p>A villager is trapped when it can't move to any of the four blocks
     * next to it: not by walking, not by falling, not by jumping up one block.
     * This uses the real collision shapes of the blocks, so glass panes,
     * fences, slabs, trapdoors and workstations all count with their actual
     * size. A side doesn't have to be walled up: an opening lower than the
     * villager (1.95 blocks), or a workstation with no room above the
     * villager's head to jump on it, is closed too. A villager riding a boat
     * or a minecart is trapped.
     */
    static String findExit(Villager villager) {
        if (villager.isInsideVehicle()) {
            return null;
        }
        World world = villager.getWorld();
        Location feet = villager.getLocation();
        BoundingBox box = villager.getBoundingBox().expand(-MARGIN);
        // Jumping needs one more block of space above the head.
        boolean canJump = !world.hasCollisionsIn(box.clone().shift(0, 1, 0));

        for (int i = 0; i < SIDES.length; i++) {
            int[] side = SIDES[i];
            int x = feet.getBlockX() + side[0];
            int z = feet.getBlockZ() + side[1];
            String where = SIDE_NAMES[i] + " (" + x + " " + feet.getBlockY() + " " + z + ")";
            // Unloaded blocks have no collisions, so we can't tell what is
            // there. Not trapped is the safe answer, the next check decides again.
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return "the chunk to the " + where + " is not loaded";
            }
            // Villagers open wooden doors on their own, so a closed one is not a wall.
            if (Tag.WOODEN_DOORS.isTagged(world.getBlockAt(x, feet.getBlockY(), z).getType())) {
                return "there is a wooden door to the " + where + ", villagers open those";
            }
            BoundingBox moved = box.clone().shift(side[0], 0, side[1]);
            if (!world.hasCollisionsIn(moved)) {
                return "it can walk out to the " + where;
            }
            if (canJump && !world.hasCollisionsIn(moved.shift(0, 1, 0))) {
                return "it can jump out to the " + where + ": there is room above its head";
            }
        }
        return null;
    }
}
