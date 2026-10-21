package dev.ghiacciolo.frozenvillagers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RestockWindowsTest {

    private static final long DAY = RestockWindows.DAY;
    private static final long NEVER = Long.MIN_VALUE;

    // 07:00, 12:00 and 17:00.
    private final RestockWindows windows = new RestockWindows(new long[] {1000, 6000, 11000});

    @Test
    void latestIsTheLastPassedWindowOfToday() {
        assertEquals(5 * DAY + 1000, windows.latest(5 * DAY + 1000));
        assertEquals(5 * DAY + 1000, windows.latest(5 * DAY + 5999));
        assertEquals(5 * DAY + 6000, windows.latest(5 * DAY + 6000));
        assertEquals(5 * DAY + 11000, windows.latest(5 * DAY + 23999));
    }

    @Test
    void beforeTheFirstWindowTheLatestIsYesterdaysLast() {
        assertEquals(4 * DAY + 11000, windows.latest(5 * DAY + 500));
    }

    @Test
    void nextIsTheFirstWindowAfterNow() {
        assertEquals(5 * DAY + 1000, windows.next(5 * DAY));
        assertEquals(5 * DAY + 6000, windows.next(5 * DAY + 1000));
        assertEquals(6 * DAY + 1000, windows.next(5 * DAY + 11000));
    }

    @Test
    void neverRestockedIsAlwaysDue() {
        assertTrue(windows.isDue(NEVER, 0));
        assertTrue(windows.isDue(NEVER, 5 * DAY + 3000));
    }

    @Test
    void onlyOnceUntilTheNextWindow() {
        long restocked = 5 * DAY + 1500;
        assertFalse(windows.isDue(restocked, 5 * DAY + 1500));
        assertFalse(windows.isDue(restocked, 5 * DAY + 5999));
        assertTrue(windows.isDue(restocked, 5 * DAY + 6000));
    }

    @Test
    void severalMissedWindowsGiveOneRestock() {
        long restocked = 5 * DAY + 1500;
        long now = 7 * DAY + 2000;
        assertTrue(windows.isDue(restocked, now));
        assertFalse(windows.isDue(now, now));
    }

    @Test
    void yesterdaysWindowsCountBeforeTodaysFirst() {
        // Restocked in the morning, then nobody came until the next dawn:
        // the 12:00 and 17:00 windows passed in between.
        assertTrue(windows.isDue(5 * DAY + 1500, 6 * DAY + 100));
        // Restocked after the last window, still before the next one.
        assertFalse(windows.isDue(5 * DAY + 12000, 6 * DAY + 900));
    }

    @Test
    void clockSetBackwardsDoesNotBlockRestocks() {
        // Restocked at day 20, then /time set moved the clock to day 3.
        assertTrue(windows.isDue(20 * DAY + 1500, 3 * DAY + 200));
    }

    @Test
    void aWindowAtMidnightOfTheDayWorks() {
        RestockWindows dawn = new RestockWindows(new long[] {0});
        assertTrue(dawn.isDue(5 * DAY - 1, 5 * DAY));
        assertFalse(dawn.isDue(5 * DAY, 5 * DAY + 23999));
    }

    @Test
    void needsAtLeastOneTime() {
        assertThrows(IllegalArgumentException.class, () -> new RestockWindows(new long[0]));
    }

    @Test
    void formatsTicksAsRealTime() {
        assertEquals("0s", RestockWindows.formatTicks(0));
        assertEquals("1s", RestockWindows.formatTicks(1));
        assertEquals("1m 0s", RestockWindows.formatTicks(1200));
        assertEquals("4m 10s", RestockWindows.formatTicks(5000));
    }
}
