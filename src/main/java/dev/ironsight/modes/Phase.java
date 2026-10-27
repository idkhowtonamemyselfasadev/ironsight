package dev.ironsight.modes;

public enum Phase {
    /** Team modes: choose a side. */
    TEAM_PICK,
    /** Frozen at spawn, loadout menu open. FFA uses it as a short start countdown. */
    FREEZE,
    LIVE,
    /** Team modes: round result on screen. */
    ROUND_END,
    /** Winner celebrated, the MVP votes for the next map. */
    ENDED
}
