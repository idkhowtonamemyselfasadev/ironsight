package dev.ironsight.modes;

import dev.ironsight.Ironsight;
import dev.ironsight.config.Settings;
import dev.ironsight.maps.ArenaMap;
import dev.ironsight.net.Net;
import dev.ironsight.net.S2C;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 1v1 / 2v2 / 4v4, CS style. Pick a side, then rounds: 10 s frozen buy time with the
 * loadout menu, 2 min to wipe the other team with one life each. First to 7. Sides swap at
 * halftime.
 */
public final class TeamMatch extends Match {

    private static final String[] TEAM_NAMES = {"Red", "Blue"};
    private static final ChatFormatting[] TEAM_COLOURS = {ChatFormatting.RED, ChatFormatting.BLUE};

    private final Random random = new Random();
    private final int[] score = new int[2];
    private int round;
    private boolean swapped;
    private int lastRoundWinner = -1;

    public TeamMatch(Mode mode, ArenaMap map, MinecraftServer server, int slot) {
        super(mode, map, server, slot);
    }

    public static String teamName(int team) {
        return team >= 0 && team < 2 ? TEAM_NAMES[team] : "-";
    }

    @Override
    protected int teamOf(MatchPlayer mp) {
        return mp == null ? -1 : mp.team;
    }

    @Override
    protected void begin() {
        phase = Phase.TEAM_PICK;
        phaseEndsAt = now + Settings.get().teamPickSeconds * 20L;
        ArenaMap.Spawn s = map.spectator;
        for (MatchPlayer mp : players.values()) {
            mp.player.setGameMode(GameType.ADVENTURE);
            mp.player.teleportTo(level, origin.getX() + s.x(), origin.getY() + s.y(), origin.getZ() + s.z(),
                    Set.of(), s.yaw(), 20f, false);
            freeze(mp, true);
        }
        sendTeamMenu();
        hudDirty = true;
    }

    private int count(int team) {
        int n = 0;
        for (MatchPlayer mp : players.values()) if (mp.team == team && mp.connected) n++;
        return n;
    }

    private void sendTeamMenu() {
        String data = count(0) + "," + count(1) + "," + mode.perTeam;
        int secs = ticksLeft() / 20;
        for (MatchPlayer mp : connected()) {
            if (mp.team < 0) Net.send(mp.player, new S2C.Menu(S2C.Menu.TEAM, data, secs));
        }
    }

    /** A team pick. A full team bounces you to the other one (auto-balance). */
    public void pickTeam(MatchPlayer mp, String which) {
        if (phase != Phase.TEAM_PICK || mp.team >= 0) return;
        int want = "B".equalsIgnoreCase(which) ? 1 : 0;
        if (count(want) >= mode.perTeam) {
            want = 1 - want;
            mp.player.sendSystemMessage(Component.literal("That team is full - you're on " + teamName(want) + ".")
                    .withStyle(ChatFormatting.YELLOW));
        }
        assign(mp, want);
        Net.send(mp.player, new S2C.Menu(S2C.Menu.CLOSE, "", 0));
        sendTeamMenu();
        boolean all = true;
        for (MatchPlayer o : connected()) if (o.team < 0) all = false;
        if (all) startRound();
    }

    private void assign(MatchPlayer mp, int team) {
        mp.team = team;
        Ironsight.LOG.info("[Ironsight] TEAM match={} player={} team={}", id, mp.name, teamName(team));
        hudDirty = true;
        boardDirty = true;
        mp.player.sendSystemMessage(Component.literal("You joined team " + teamName(team) + ".")
                .withStyle(TEAM_COLOURS[team]));
    }

    private void autoAssign() {
        List<MatchPlayer> open = new ArrayList<>();
        for (MatchPlayer mp : connected()) if (mp.team < 0) open.add(mp);
        Collections.shuffle(open, random);
        for (MatchPlayer mp : open) assign(mp, count(0) <= count(1) ? 0 : 1);
    }

    // ------------------------------------------------------------------ rounds

    private void startRound() {
        round++;
        Settings cfg = Settings.get();
        if (round == cfg.halftimeAfter + 1) {
            swapped = true;
            titleAll(Component.literal("HALFTIME").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                    Component.literal("Switching sides"), 5, 40, 10);
        }
        phase = Phase.FREEZE;
        phaseEndsAt = now + cfg.freezeSeconds * 20L;
        int[] next = new int[2];
        for (MatchPlayer mp : players.values()) {
            if (!mp.connected) continue;
            if (mp.primary == null) {
                // First round default; the menu lets them change it during the freeze.
                mp.primary = "rifle";
                mp.secondary = "pistol";
            }
            List<ArenaMap.Spawn> list = spawnList(mp.team);
            spawn(mp, list.get(next[mp.team]++ % list.size()));
            mp.protectedUntil = 0;
            freeze(mp, true);
            Net.send(mp.player, new S2C.Menu(S2C.Menu.LOADOUT, mp.primary + "," + mp.secondary, cfg.freezeSeconds));
        }
        soundAll("round");
        hudDirty = true;
        boardDirty = true;
        Ironsight.LOG.info("[Ironsight] ROUND START match={} round={} score={}-{} swapped={}", id, round, score[0], score[1], swapped);
    }

    private List<ArenaMap.Spawn> spawnList(int team) {
        boolean a = (team == 0) != swapped;
        return a ? map.teamA : map.teamB;
    }

    @Override
    protected ArenaMap.Spawn spawnFor(MatchPlayer mp) {
        List<ArenaMap.Spawn> list = spawnList(Math.max(0, mp.team));
        return list.get(random.nextInt(list.size()));
    }

    /** Loadout picked during the freeze: swap the guns in hand right away. */
    public void pickLoadout(MatchPlayer mp, String primary, String secondary) {
        if (phase != Phase.FREEZE && phase != Phase.TEAM_PICK) return;
        if (!setLoadout(mp, primary, secondary)) return;
        if (phase == Phase.FREEZE && mp.alive) {
            giveLoadout(mp);
            for (var g : mp.guns) if (g != null) g.refill();
        }
    }

    @Override
    protected void tickRules() {
        switch (phase) {
            case TEAM_PICK -> {
                if (now >= phaseEndsAt) {
                    autoAssign();
                    for (MatchPlayer mp : connected()) Net.send(mp.player, new S2C.Menu(S2C.Menu.CLOSE, "", 0));
                    startRound();
                }
            }
            case FREEZE -> {
                if (now >= phaseEndsAt) {
                    phase = Phase.LIVE;
                    phaseEndsAt = now + Settings.get().roundSeconds * 20L;
                    for (MatchPlayer mp : connected()) {
                        freeze(mp, false);
                        Net.send(mp.player, new S2C.Menu(S2C.Menu.CLOSE, "", 0));
                    }
                    titleAll(Component.literal("GO").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                            Component.literal("Round " + round), 0, 15, 5);
                    hudDirty = true;
                }
            }
            case LIVE -> {
                if (now >= phaseEndsAt) {
                    roundOverOnTime();
                }
            }
            case ROUND_END -> {
                if (now >= phaseEndsAt) {
                    int toWin = Settings.get().roundsToWin;
                    if (score[0] >= toWin || score[1] >= toWin) {
                        finishTeams(score[0] >= toWin ? 0 : 1);
                    } else if (round >= maxRounds()) {
                        // Drawn rounds score nothing, so a match of campers could run forever.
                        // After the cap the higher score wins; a tie goes to more damage dealt.
                        int w = score[0] != score[1] ? (score[0] > score[1] ? 0 : 1) : (damage(0) >= damage(1) ? 0 : 1);
                        Ironsight.LOG.info("[Ironsight] ROUND CAP match={} rounds={} score={}-{}", id, round, score[0], score[1]);
                        finishTeams(w);
                    } else {
                        startRound();
                    }
                }
            }
            default -> { }
        }
    }

    /** 2 x roundsToWin - 1 decides it CS style; 4 more rounds of room for draws. */
    private int maxRounds() {
        return Settings.get().roundsToWin * 2 + 3;
    }

    private float damage(int team) {
        float d = 0;
        for (MatchPlayer mp : players.values()) if (mp.team == team) d += mp.damage;
        return d;
    }

    private int alive(int team) {
        int n = 0;
        for (MatchPlayer mp : players.values()) if (mp.team == team && mp.alive && mp.connected) n++;
        return n;
    }

    private float health(int team) {
        float h = 0;
        for (MatchPlayer mp : players.values()) {
            if (mp.team == team && mp.alive && mp.connected) h += mp.player.getHealth();
        }
        return h;
    }

    @Override
    protected void onDeath(MatchPlayer victim, MatchPlayer killer) {
        if (phase != Phase.LIVE) return;
        int a = alive(0), b = alive(1);
        if (a == 0 || b == 0) {
            roundWon(a == 0 && b == 0 ? -1 : (a == 0 ? 1 : 0), "eliminated");
        }
    }

    private void roundOverOnTime() {
        int a = alive(0), b = alive(1);
        int winner;
        if (a != b) winner = a > b ? 0 : 1;
        else {
            float ha = health(0), hb = health(1);
            winner = Math.abs(ha - hb) < 0.01f ? -1 : (ha > hb ? 0 : 1);
        }
        roundWon(winner, "time");
    }

    private void roundWon(int team, String reason) {
        lastRoundWinner = team;
        if (team >= 0) score[team]++;
        phase = Phase.ROUND_END;
        phaseEndsAt = now + Settings.get().roundEndSeconds * 20L;
        for (MatchPlayer mp : connected()) {
            if (mp.alive) freeze(mp, false);
        }
        Component title = team < 0
                ? Component.literal("DRAW").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD)
                : Component.literal(teamName(team).toUpperCase() + " WINS THE ROUND").withStyle(TEAM_COLOURS[team], ChatFormatting.BOLD);
        titleAll(title, Component.literal(score[0] + " - " + score[1]), 3, 50, 10);
        hudDirty = true;
        Ironsight.LOG.info("[Ironsight] ROUND END match={} round={} winner={} reason={} score={}-{}", id, round,
                teamName(team), reason, score[0], score[1]);
    }

    private void finishTeams(int team) {
        MatchPlayer best = null;
        MatchPlayer anyAlive = null;
        for (MatchPlayer mp : players.values()) {
            if (mp.team != team || !mp.connected) continue;
            if (best == null || mp.kills > best.kills || (mp.kills == best.kills && mp.damage > best.damage)) best = mp;
            if (mp.alive) anyAlive = mp;
        }
        end(teamName(team) + " wins " + score[0] + "-" + score[1], anyAlive != null ? anyAlive : best, best);
    }

    @Override
    protected void afterLeave(MatchPlayer mp) {
        if (phase == Phase.ENDED) return;
        // A team with nobody left forfeits the match.
        for (int t = 0; t < 2; t++) {
            if (phase != Phase.TEAM_PICK && count(t) == 0) {
                score[1 - t] = Math.max(score[1 - t], Settings.get().roundsToWin);
                Ironsight.LOG.info("[Ironsight] FORFEIT match={} team={}", id, teamName(t));
                finishTeams(1 - t);
                return;
            }
        }
        if (phase == Phase.TEAM_PICK && connected().isEmpty()) {
            end("Nobody left", null, null);
        }
    }

    @Override
    protected S2C.Hud hud(MatchPlayer viewer) {
        return new S2C.Hud((byte) mode.ordinal(), (byte) phase.ordinal(), ticksLeft(), (byte) round,
                (byte) score[0], (byte) score[1], (byte) viewer.team, (byte) alive(0), (byte) alive(1), (byte) 0,
                (byte) 0, (byte) Settings.get().roundsToWin, map.name);
    }

    public int lastRoundWinner() {
        return lastRoundWinner;
    }
}
