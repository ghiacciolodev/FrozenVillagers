package dev.ghiacciolo.frozenvillagers;

import java.util.UUID;
import org.bukkit.entity.Villager;

/** What the plugin knows about one loaded villager. Lives only in memory. */
final class Tracked {

    /** What the plugin currently does to the villager. */
    enum Control { NONE, FROZEN, PULSED }

    /** Why a villager is pulsed. */
    enum Reason { TRAPPED, SURPLUS }

    final Villager villager;
    final UUID id;

    int checkBucket;
    int pulsePhase;

    Control control = Control.NONE;
    Reason reason;
    // Why the last check decided what it did, for /fv inspect.
    String why = "";

    // Where it was counted as idle, so the count can be moved when it walks away.
    UUID worldId;
    long chunkKey;
    boolean countedIdle;

    // Awake until this server tick because it was hurt.
    int awakeUntil;
    // Awake because it is levelling up.
    boolean levelingUp;
    // Level and XP when we last tried a level up, so we don't retry forever.
    long lastLevelAttempt = -1;

    Tracked(Villager villager) {
        this.villager = villager;
        this.id = villager.getUniqueId();
    }

    boolean isManaged() {
        return control != Control.NONE;
    }
}
