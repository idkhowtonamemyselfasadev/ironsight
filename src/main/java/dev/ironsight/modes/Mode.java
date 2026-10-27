package dev.ironsight.modes;

/** The four ways to play. Team modes need exactly 2 x perTeam players. */
public enum Mode {
    DUEL("duel", "1v1", 1),
    TEAM2("team2", "2v2", 2),
    TEAM4("team4", "4v4", 4),
    FFA("ffa", "FFA", 0);

    public final String id;
    public final String display;
    public final int perTeam;

    Mode(String id, String display, int perTeam) {
        this.id = id;
        this.display = display;
        this.perTeam = perTeam;
    }

    public boolean team() {
        return this != FFA;
    }

    public static Mode byId(String id) {
        for (Mode m : values()) {
            if (m.id.equalsIgnoreCase(id) || m.display.equalsIgnoreCase(id)) return m;
        }
        return null;
    }
}
