package dev.ghiacciolo.frozenvillagers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LevelsTest {

    @Test
    void levelUpNeedsTheVanillaThreshold() {
        assertFalse(Levels.isLevelUpPending(1, 9));
        assertTrue(Levels.isLevelUpPending(1, 10));
        assertFalse(Levels.isLevelUpPending(2, 69));
        assertTrue(Levels.isLevelUpPending(2, 70));
        assertTrue(Levels.isLevelUpPending(3, 150));
        assertTrue(Levels.isLevelUpPending(4, 250));
    }

    @Test
    void masterVillagersNeverLevelUp() {
        assertFalse(Levels.isLevelUpPending(5, 10_000));
    }

    @Test
    void brokenLevelsAreIgnored() {
        assertFalse(Levels.isLevelUpPending(0, 100));
        assertFalse(Levels.isLevelUpPending(6, 100));
    }

    @Test
    void tradeLockedAfterTheFirstTrade() {
        assertFalse(Levels.isTradeLocked(1, 0));
        assertTrue(Levels.isTradeLocked(1, 1));
        assertTrue(Levels.isTradeLocked(2, 0));
    }
}
