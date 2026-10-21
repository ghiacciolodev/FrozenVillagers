package dev.ghiacciolo.frozenvillagers;

/** Villager levels, with the same thresholds as vanilla. */
final class Levels {

    static final int MAX = 5;

    // Total XP needed to reach the next level, by current level.
    private static final int[] NEXT_LEVEL_XP = {0, 10, 70, 150, 250};

    private Levels() {
    }

    /**
     * Whether a villager has enough XP for the next level. Vanilla checks the
     * same thing after every trade, and then levels the villager up in its AI
     * tick about two seconds after the trade screen closes.
     */
    static boolean isLevelUpPending(int level, int xp) {
        return level >= 1 && level < MAX && xp >= NEXT_LEVEL_XP[level];
    }

    /** Whether the villager can no longer lose its profession, like in vanilla. */
    static boolean isTradeLocked(int level, int xp) {
        return xp > 0 || level > 1;
    }
}
