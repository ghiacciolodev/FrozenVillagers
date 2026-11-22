package dev.ghiacciolo.frozenvillagers;

import java.util.UUID;
import org.bukkit.entity.Villager;

/** What the plugin knows about one loaded villager. Lives only in memory. */
final class Tracked {

    /**
     * What the plugin currently does to the villager.
     *
     * <ul>
     *   <li>NONE: nothing, it has its full AI.</li>
     *   <li>FROZEN: frozen by staff, AI always off.</li>
     *   <li>ASLEEP: AI off, woken up only for a reason (hurt, a block placed
     *       nearby, a level up). Trapped villagers.</li>
     *   <li>PULSED: AI on for a moment every few seconds. Surplus villagers,
     *       and trapped ones with trapped.pulse.</li>
     * </ul>
     */
    enum Control { NONE, FROZEN, ASLEEP, PULSED }

    /** Why the automatic features handle a villager. */
    enum Reason { TRAPPED, SURPLUS }

    final Villager villager;
    final UUID id;

    int checkBucket;
    int pulsePhase;

    Control control = Control.NONE;
    Reason reason;
    // Why the last check decided what it did: a short label to group villagers
    // in /fv status and /fv scan, and the full explanation for /fv inspect.
    String whyShort = "";
    String why = "";

    // Where it was counted as idle, so the count can be moved when it walks away.
    UUID worldId;
    long chunkKey;
    boolean countedIdle;

    // Awake until this server tick because it was hurt or a block was placed nearby.
    int awakeUntil;
    // Awake because it is levelling up.
    boolean levelingUp;

    Tracked(Villager villager) {
        this.villager = villager;
        this.id = villager.getUniqueId();
    }

    boolean isManaged() {
        return control != Control.NONE;
    }

    /** Whether the plugin restocks this villager instead of vanilla. */
    boolean isRestockedByPlugin() {
        return isRestockedByPlugin(control, reason);
    }

    static boolean isRestockedByPlugin(Control control, Reason reason) {
        return control == Control.FROZEN || reason == Reason.TRAPPED;
    }

    /** Woken up for a reason, so its AI must stay on for now. */
    boolean isAwakeForAReason(int now) {
        return levelingUp || now < awakeUntil;
    }
}
