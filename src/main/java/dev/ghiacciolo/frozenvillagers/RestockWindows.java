package dev.ghiacciolo.frozenvillagers;

/**
 * The fixed times of day when villagers handled by the plugin restock.
 *
 * <p>Times are day times, 0 to 23999, where 0 is 06:00 and 1000 is 07:00.
 * Everything here works on the world's full time, which is what /time set
 * changes, so it stays consistent with what players see.
 */
final class RestockWindows {

    static final long DAY = 24000;

    private final long[] times;

    /** Times must be sorted and between 0 and 23999. */
    RestockWindows(long[] times) {
        if (times.length == 0) {
            throw new IllegalArgumentException("at least one restock time is needed");
        }
        this.times = times.clone();
    }

    /** The full time of the most recent window at or before fullTime. */
    long latest(long fullTime) {
        long dayStart = Math.floorDiv(fullTime, DAY) * DAY;
        for (int i = times.length - 1; i >= 0; i--) {
            long window = dayStart + times[i];
            if (window <= fullTime) {
                return window;
            }
        }
        // None today yet, so the last one of yesterday.
        return dayStart - DAY + times[times.length - 1];
    }

    /** The full time of the next window after fullTime. */
    long next(long fullTime) {
        long dayStart = Math.floorDiv(fullTime, DAY) * DAY;
        for (long time : times) {
            long window = dayStart + time;
            if (window > fullTime) {
                return window;
            }
        }
        return dayStart + DAY + times[0];
    }

    /**
     * Whether a villager that last restocked at lastRestock may restock now.
     * It may when a window has passed since then. Several missed windows
     * still give a single restock.
     *
     * <p>A last restock later than now means /time set moved the clock back.
     * It counts as never restocked, or the villager would wait until the
     * clock caught up, possibly for days.
     */
    boolean isDue(long lastRestock, long fullTime) {
        if (lastRestock > fullTime) {
            return true;
        }
        return lastRestock < latest(fullTime);
    }

    /** Game ticks as real time, like "4m 10s". */
    static String formatTicks(long ticks) {
        long seconds = Math.max(0, (ticks + 19) / 20);
        long minutes = seconds / 60;
        return minutes > 0 ? minutes + "m " + seconds % 60 + "s" : seconds + "s";
    }
}
