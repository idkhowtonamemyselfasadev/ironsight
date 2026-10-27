package dev.ironsight.client;

import dev.ironsight.config.GunConfig;
import dev.ironsight.config.GunStats;
import dev.ironsight.net.S2C;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the client knows about the match. Written by packet handlers (on the client
 * thread), read by the HUD every frame. All per-frame data lives in fixed arrays and
 * strings that are rebuilt only when a packet changes them, so drawing allocates nothing.
 */
@Environment(EnvType.CLIENT)
public final class ClientState {

    private ClientState() {
    }

    public static long clientTick;

    // --- match / hud ---------------------------------------------------------------
    public static S2C.Hud hud = S2C.Hud.none();
    public static long phaseEndsAtTick;
    public static String mapName = "";

    public static boolean inMatch() {
        return hud.mode() >= 0;
    }

    /** Phase ordinals mirror dev.ironsight.modes.Phase. */
    public static final int TEAM_PICK = 0, FREEZE = 1, LIVE = 2, ROUND_END = 3, ENDED = 4;

    public static int phase() {
        return hud.phase();
    }

    public static boolean ffa() {
        return hud.mode() == 3;
    }

    public static int secondsLeft() {
        return (int) Math.max(0, (phaseEndsAtTick - clientTick + 19) / 20);
    }

    // Cached HUD text
    public static String timerText = "";
    private static int timerSecs = -1;
    public static String scoreLeft = "", scoreRight = "", centerSub = "", livesText = "";

    public static void applyHud(S2C.Hud h) {
        hud = h;
        phaseEndsAtTick = clientTick + h.ticksLeft();
        mapName = h.map();
        if (h.mode() == 3) {
            scoreLeft = "Kills " + h.scoreA();
            scoreRight = h.playersLeft() + " left";
            livesText = h.lives() + (h.lives() == 1 ? " LIFE" : " LIVES");
        } else {
            scoreLeft = String.valueOf(h.scoreA());
            scoreRight = String.valueOf(h.scoreB());
            livesText = "";
        }
        centerSub = switch (h.phase()) {
            case TEAM_PICK -> "PICK A TEAM";
            case FREEZE -> h.mode() == 3 ? "GET READY" : "BUY TIME";
            case ROUND_END -> "ROUND OVER";
            case ENDED -> "MATCH OVER";
            default -> h.mode() == 3 ? "FREE FOR ALL" : "ROUND " + h.round() + "  ·  FIRST TO " + h.toWin();
        };
        timerSecs = -1;
        if (!inMatch()) {
            for (int i = 0; i < AMMO_MAG.length; i++) AMMO_MAG[i] = -1;
            glints.clear();
        }
    }

    /** Called once per frame by the HUD; only rebuilds the string when the second changes. */
    public static String timer() {
        int s = secondsLeft();
        if (s != timerSecs) {
            timerSecs = s;
            timerText = String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        }
        return timerText;
    }

    // --- ammo ----------------------------------------------------------------------
    public static final int[] AMMO_MAG = new int[16];
    public static final int[] AMMO_RESERVE = new int[16];
    public static final long[] RELOAD_END = new long[16];
    public static final int[] RELOAD_TOTAL = new int[16];
    private static final String[] AMMO_TEXT = new String[16];
    private static final int[] AMMO_TEXT_MAG = new int[16];
    private static final int[] AMMO_TEXT_RES = new int[16];

    static {
        for (int i = 0; i < 16; i++) {
            AMMO_MAG[i] = -1;
            AMMO_TEXT_MAG[i] = -2;
        }
    }

    public static void applyAmmo(S2C.Ammo a) {
        int i = a.gun();
        if (i < 0 || i >= 16) return;
        AMMO_MAG[i] = a.mag();
        AMMO_RESERVE[i] = a.reserve();
        RELOAD_END[i] = a.reloadLeft() > 0 ? clientTick + a.reloadLeft() : 0;
        RELOAD_TOTAL[i] = a.reloadTotal();
    }

    public static String ammoText(int i) {
        if (AMMO_TEXT_MAG[i] != AMMO_MAG[i] || AMMO_TEXT_RES[i] != AMMO_RESERVE[i]) {
            AMMO_TEXT_MAG[i] = AMMO_MAG[i];
            AMMO_TEXT_RES[i] = AMMO_RESERVE[i];
            AMMO_TEXT[i] = Math.max(0, AMMO_MAG[i]) + " / " + AMMO_RESERVE[i];
        }
        return AMMO_TEXT[i];
    }

    public static boolean reloading(int i) {
        return RELOAD_END[i] > clientTick;
    }

    // --- kill feed (ring of 6) -----------------------------------------------------
    public static final int FEED = 6;
    public static final String[] FEED_KILLER = new String[FEED];
    public static final String[] FEED_VICTIM = new String[FEED];
    public static final String[] FEED_GUN = new String[FEED];
    public static final int[] FEED_KCOL = new int[FEED];
    public static final int[] FEED_VCOL = new int[FEED];
    public static final boolean[] FEED_HEAD = new boolean[FEED];
    public static final boolean[] FEED_MINE = new boolean[FEED];
    public static final long[] FEED_AT = new long[FEED];
    public static int feedHead;

    public static int teamColour(int team, boolean mine) {
        if (team == 0) return 0xFFFF5555;
        if (team == 1) return 0xFF5599FF;
        return mine ? 0xFFFFD24A : 0xFFE8E8E8;
    }

    public static void addKill(S2C.Kill k, String me) {
        int i = feedHead = (feedHead + 1) % FEED;
        boolean mine = me.equals(k.killer()) || me.equals(k.victim());
        GunStats s = k.gun() >= 0 ? GunConfig.get(GunConfig.idAt(k.gun())) : null;
        FEED_KILLER[i] = k.killer();
        FEED_VICTIM[i] = k.victim();
        FEED_GUN[i] = s != null ? s.name : (k.killer().isEmpty() ? "fell" : "");
        FEED_KCOL[i] = teamColour(k.killerTeam(), me.equals(k.killer()));
        FEED_VCOL[i] = teamColour(k.victimTeam(), me.equals(k.victim()));
        FEED_HEAD[i] = k.headshot();
        FEED_MINE[i] = mine;
        FEED_AT[i] = clientTick;
    }

    // --- hit markers & damage numbers ------------------------------------------------
    public static long hitAt = -100;
    public static boolean hitHead, hitKill;
    public static final int NUMBERS = 8;
    public static final String[] NUM_TEXT = new String[NUMBERS];
    public static final long[] NUM_AT = new long[NUMBERS];
    public static final int[] NUM_COL = new int[NUMBERS];
    public static final float[] NUM_X = new float[NUMBERS];
    public static int numHead;

    public static void applyHit(S2C.Hit h) {
        hitAt = clientTick;
        hitHead = (h.flags() & 1) != 0;
        hitKill = (h.flags() & 2) != 0;
        int i = numHead = (numHead + 1) % NUMBERS;
        NUM_TEXT[i] = String.format(Locale.ROOT, h.damage() >= 10 ? "%.0f" : "%.1f", h.damage());
        NUM_AT[i] = clientTick;
        NUM_COL[i] = hitKill ? 0xFFFF4040 : hitHead ? 0xFFFFD24A : 0xFFFFFFFF;
        NUM_X[i] = (float) (Math.random() * 30 - 15);
    }

    // --- scoreboard -----------------------------------------------------------------
    public record Row(String name, int team, int kills, int deaths, int damage, boolean alive, int lives, boolean left,
                      String k, String d, String dmg) { }

    public static List<Row> board = List.of();

    public static void applyBoard(String rows) {
        List<Row> out = new ArrayList<>();
        if (!rows.isEmpty()) {
            for (String r : rows.split(";")) {
                String[] f = r.split("\\|");
                if (f.length < 7) continue;
                int k = Integer.parseInt(f[2]), d = Integer.parseInt(f[3]), dmg = Integer.parseInt(f[4]);
                out.add(new Row(f[0], Integer.parseInt(f[1]), k, d, dmg, "1".equals(f[5]), Integer.parseInt(f[6]),
                        f.length > 7, String.valueOf(k), String.valueOf(d), String.valueOf(dmg)));
            }
        }
        out.sort((a, b) -> a.team() != b.team() ? Integer.compare(a.team(), b.team()) : Integer.compare(b.kills(), a.kills()));
        board = out;
    }

    // --- glints ---------------------------------------------------------------------
    public static final it.unimi.dsi.fastutil.ints.IntOpenHashSet glints = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    public static void reset() {
        applyHud(S2C.Hud.none());
        board = List.of();
        glints.clear();
        for (int i = 0; i < FEED; i++) FEED_AT[i] = -1000;
    }
}
