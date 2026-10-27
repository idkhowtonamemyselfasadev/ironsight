package dev.ironsight.modes;

import dev.ironsight.Ironsight;
import dev.ironsight.config.Settings;
import dev.ironsight.maps.ArenaMap;
import dev.ironsight.maps.Arenas;
import dev.ironsight.maps.MapRegistry;
import dev.ironsight.net.Net;
import dev.ironsight.net.S2C;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Queues and running matches. A queue per mode; when a team queue holds exactly enough
 * players (2, 4, 8) a match starts. FFA starts a countdown once it has the minimum and
 * starts early when full. Any number of matches can run at once, each in its own arena slot.
 */
public final class MatchManager {

    private static final Map<Mode, List<UUID>> QUEUES = new EnumMap<>(Mode.class);
    private static final List<Match> MATCHES = new ArrayList<>();
    private static final Map<UUID, Match> BY_PLAYER = new HashMap<>();
    private static final Map<Mode, ArenaMap> NEXT_MAP = new EnumMap<>(Mode.class);
    private static final Map<Mode, String> LAST_MAP = new EnumMap<>(Mode.class);
    private static final Random RANDOM = new Random();

    private static MinecraftServer server;
    private static long tick;
    private static long ffaCountdownEnds = -1;

    static {
        for (Mode m : Mode.values()) QUEUES.put(m, new ArrayList<>());
    }

    private MatchManager() {
    }

    public static void init(MinecraftServer s) {
        server = s;
    }

    public static long tick() {
        return tick;
    }

    public static Match matchOf(ServerPlayer p) {
        return BY_PLAYER.get(p.getUUID());
    }

    public static MatchPlayer participant(ServerPlayer p) {
        Match m = matchOf(p);
        return m == null ? null : m.get(p);
    }

    public static List<Match> matches() {
        return MATCHES;
    }

    // ------------------------------------------------------------------ queues

    public static Mode queuedFor(ServerPlayer p) {
        for (Map.Entry<Mode, List<UUID>> e : QUEUES.entrySet()) {
            if (e.getValue().contains(p.getUUID())) return e.getKey();
        }
        return null;
    }

    public static void join(ServerPlayer p, Mode mode) {
        if (matchOf(p) != null) {
            p.sendSystemMessage(Component.literal("You're already in a match. /leave first.").withStyle(ChatFormatting.RED));
            return;
        }
        if (validMaps(mode, mode.team() ? mode.perTeam * 2 : 2).isEmpty()) {
            p.sendSystemMessage(Component.literal("No map supports " + mode.display + " right now.").withStyle(ChatFormatting.RED));
            return;
        }
        leaveQueue(p, false);
        QUEUES.get(mode).add(p.getUUID());
        int need = mode.team() ? mode.perTeam * 2 : Settings.get().ffaMinPlayers;
        p.sendSystemMessage(Component.literal("Queued for " + mode.display + " (" + QUEUES.get(mode).size() + "/" + need
                + "). /leave to leave the queue.").withStyle(ChatFormatting.GREEN));
        Ironsight.LOG.info("[Ironsight] QUEUE {} -> {} ({})", p.getGameProfile().name(), mode.id, QUEUES.get(mode).size());
    }

    public static boolean leaveQueue(ServerPlayer p, boolean announce) {
        boolean removed = false;
        for (List<UUID> q : QUEUES.values()) removed |= q.remove(p.getUUID());
        if (removed && announce) {
            p.sendSystemMessage(Component.literal("Left the queue.").withStyle(ChatFormatting.YELLOW));
        }
        return removed;
    }

    /** "/leave": leave the queue, or leave the match (which counts as a death). */
    public static void leave(ServerPlayer p) {
        Match m = matchOf(p);
        if (m != null) {
            MatchPlayer mp = m.get(p);
            BY_PLAYER.remove(p.getUUID());
            if (mp != null) m.leave(mp);
            Lobby.send(p);
            Net.send(p, S2C.Hud.none());
            p.sendSystemMessage(Component.literal("You left the match.").withStyle(ChatFormatting.YELLOW));
        } else if (!leaveQueue(p, true)) {
            p.sendSystemMessage(Component.literal("You're not in a queue.").withStyle(ChatFormatting.GRAY));
        }
    }

    public static void disconnected(ServerPlayer p) {
        leaveQueue(p, false);
        Match m = BY_PLAYER.remove(p.getUUID());
        if (m != null) {
            MatchPlayer mp = m.get(p);
            if (mp != null) m.leave(mp);
        }
    }

    public static String queueSummary(ServerPlayer viewer) {
        StringBuilder b = new StringBuilder();
        for (Mode m : Mode.values()) {
            if (b.length() > 0) b.append(',');
            int need = m.team() ? m.perTeam * 2 : Settings.get().ffaMaxPlayers;
            b.append(m.id).append(':').append(QUEUES.get(m).size()).append('/').append(need);
        }
        Mode q = queuedFor(viewer);
        if (q != null) b.append(";queued=").append(q.id);
        return b.toString();
    }

    public static void openPlayMenu(ServerPlayer p) {
        Net.send(p, new S2C.Menu(S2C.Menu.PLAY, queueSummary(p), 0));
    }

    // ------------------------------------------------------------------ maps

    private static List<ArenaMap> validMaps(Mode mode, int players) {
        List<ArenaMap> out = new ArrayList<>();
        for (ArenaMap m : MapRegistry.all()) {
            if (m.problemFor(mode.team(), mode.perTeam, players) == null) out.add(m);
        }
        return out;
    }

    private static ArenaMap pickMap(Mode mode, int players) {
        ArenaMap next = NEXT_MAP.remove(mode);
        if (next != null && next.problemFor(mode.team(), mode.perTeam, players) == null) return next;
        List<ArenaMap> maps = validMaps(mode, players);
        if (maps.isEmpty()) return null;
        // Random, but not the same map twice in a row when there is a choice.
        if (maps.size() > 1) maps.removeIf(m -> m.id.equals(LAST_MAP.get(mode)));
        return maps.get(RANDOM.nextInt(maps.size()));
    }

    /** /gun nextmap: force the next map of a mode. */
    public static boolean setNextMap(Mode mode, String id) {
        ArenaMap m = MapRegistry.get(id);
        if (m == null) return false;
        NEXT_MAP.put(mode, m);
        return true;
    }

    /** The MVP's vote (chosen != null) or the timeout (chosen == null: random). */
    public static void chooseNextMap(Mode mode, ArenaMap chosen, Match from) {
        boolean random = chosen == null;
        if (random) {
            List<ArenaMap> maps = validMaps(mode, mode.team() ? mode.perTeam * 2 : 2);
            if (maps.isEmpty()) return;
            chosen = maps.get(RANDOM.nextInt(maps.size()));
        }
        NEXT_MAP.put(mode, chosen);
        Ironsight.LOG.info("[Ironsight] VOTE match={} next={} by={}", from.id, chosen.id, random ? "random" : "mvp");
        Component msg = Component.literal("Next " + mode.display + " map: " + chosen.name + (random ? " (random)" : " (MVP's pick)"))
                .withStyle(ChatFormatting.AQUA);
        for (MatchPlayer mp : from.connected()) mp.player.sendSystemMessage(msg);
    }

    // ------------------------------------------------------------------ ticking

    public static void tickServer(MinecraftServer s) {
        tick++;
        tryStartMatches();
        for (int i = 0; i < MATCHES.size(); i++) {
            Match m = MATCHES.get(i);
            if (!m.isFinished()) m.tick(tick);
        }
        MATCHES.removeIf(Match::isFinished);
    }

    private static void tryStartMatches() {
        for (Mode mode : Mode.values()) {
            List<UUID> q = QUEUES.get(mode);
            q.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            if (mode.team()) {
                int need = mode.perTeam * 2;
                if (q.size() >= need) startMatch(mode, new ArrayList<>(q.subList(0, need)));
            } else {
                Settings cfg = Settings.get();
                if (q.size() < cfg.ffaMinPlayers) {
                    ffaCountdownEnds = -1;
                    continue;
                }
                if (ffaCountdownEnds < 0) ffaCountdownEnds = tick + cfg.ffaCountdownSeconds * 20L;
                long left = ffaCountdownEnds - tick;
                if (left % 20 == 0 && left > 0) {
                    Component bar = Component.literal("FFA starts in " + left / 20 + "s (" + q.size() + "/" + cfg.ffaMaxPlayers + ")")
                            .withStyle(ChatFormatting.GOLD);
                    for (UUID id : q) {
                        ServerPlayer p = server.getPlayerList().getPlayer(id);
                        if (p != null) p.connection.send(new ClientboundSetActionBarTextPacket(bar));
                    }
                }
                if (left <= 0 || q.size() >= cfg.ffaMaxPlayers) {
                    ffaCountdownEnds = -1;
                    startMatch(mode, new ArrayList<>(q.subList(0, Math.min(q.size(), cfg.ffaMaxPlayers))));
                }
            }
        }
    }

    /** Start a match now with these players (also used by /gun forcestart). */
    public static Match startMatch(Mode mode, List<UUID> ids) {
        List<ServerPlayer> list = new ArrayList<>();
        for (UUID id : ids) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && matchOf(p) == null) list.add(p);
        }
        ArenaMap map = pickMap(mode, list.size());
        int slot = Arenas.claimSlot();
        if (map == null || slot < 0 || Arenas.level(server) == null) {
            Arenas.releaseSlot(slot);
            Ironsight.LOG.error("[Ironsight] cannot start {}: map={} slot={}", mode.id, map, slot);
            return null;
        }
        for (ServerPlayer p : list) leaveQueue(p, false);
        Match match = mode.team() ? new TeamMatch(mode, map, server, slot) : new FfaMatch(map, server, slot);
        LAST_MAP.put(mode, map.id);
        MATCHES.add(match);
        for (ServerPlayer p : list) BY_PLAYER.put(p.getUUID(), match);
        match.start(list, tick);
        return match;
    }

    /** Called by a match once everyone is back in the lobby. */
    static void finished(Match match, List<ServerPlayer> players) {
        for (MatchPlayer mp : match.players.values()) BY_PLAYER.remove(mp.id, match);
        if (Settings.get().autoRequeue) {
            for (ServerPlayer p : players) {
                if (!p.hasDisconnected()) join(p, match.mode);
            }
        }
    }

    public static void stopAll() {
        for (Match m : new ArrayList<>(MATCHES)) m.finish();
        MATCHES.clear();
        BY_PLAYER.clear();
    }
}
