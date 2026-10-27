package dev.ironsight.modes;

import dev.ironsight.Ironsight;
import dev.ironsight.config.GunConfig;
import dev.ironsight.config.Settings;
import dev.ironsight.maps.ArenaMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Everyone against everyone, 3 lives each, 2 random guns (never the same gun twice).
 * Respawns land on the spawn farthest from living enemies, with 3 s of protection.
 * Last player standing wins; at the time limit the most lives, then kills, wins.
 */
public final class FfaMatch extends Match {

    private final Random random = new Random();

    public FfaMatch(ArenaMap map, MinecraftServer server, int slot) {
        super(Mode.FFA, map, server, slot);
    }

    @Override
    protected int teamOf(MatchPlayer mp) {
        return -1;
    }

    @Override
    protected void begin() {
        Settings cfg = Settings.get();
        List<ArenaMap.Spawn> spawns = new ArrayList<>(map.ffa);
        Collections.shuffle(spawns, random);
        int i = 0;
        for (MatchPlayer mp : players.values()) {
            // Two random, different guns. The first one goes in slot 1, the second in slot 2.
            List<String> pool = new ArrayList<>(GunConfig.IDS);
            Collections.shuffle(pool, random);
            mp.primary = pool.get(0);
            mp.secondary = pool.get(1);
            mp.lives = cfg.ffaLives;
            spawn(mp, spawns.get(i++ % spawns.size()));
            freeze(mp, true);
            mp.player.sendSystemMessage(Component.literal("Your guns: " + GunConfig.get(mp.primary).name + " + "
                    + GunConfig.get(mp.secondary).name).withStyle(ChatFormatting.AQUA));
        }
        phase = Phase.FREEZE;
        phaseEndsAt = now + 3 * 20L;
        titleAll(Component.literal("FREE FOR ALL").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal(cfg.ffaLives + " lives - last one standing wins"), 5, 50, 10);
        hudDirty = true;
    }

    @Override
    protected ArenaMap.Spawn spawnFor(MatchPlayer mp) {
        return farthestSpawn(mp);
    }

    /** The spawn whose nearest living enemy is farthest away (random among the best three). */
    private ArenaMap.Spawn farthestSpawn(MatchPlayer mp) {
        List<ArenaMap.Spawn> spawns = new ArrayList<>(map.ffa);
        List<double[]> enemies = new ArrayList<>();
        for (MatchPlayer o : players.values()) {
            if (o != mp && o.alive && o.connected) enemies.add(new double[]{o.player.getX(), o.player.getY(), o.player.getZ()});
        }
        if (enemies.isEmpty()) return spawns.get(random.nextInt(spawns.size()));
        spawns.sort(Comparator.comparingDouble((ArenaMap.Spawn s) -> {
            double best = Double.MAX_VALUE;
            for (double[] e : enemies) {
                double dx = origin.getX() + s.x() - e[0], dy = origin.getY() + s.y() - e[1], dz = origin.getZ() + s.z() - e[2];
                best = Math.min(best, dx * dx + dy * dy * 4 + dz * dz);
            }
            return best;
        }).reversed());
        return spawns.get(random.nextInt(Math.min(3, spawns.size())));
    }

    @Override
    protected void tickRules() {
        Settings cfg = Settings.get();
        switch (phase) {
            case FREEZE -> {
                if (now >= phaseEndsAt) {
                    phase = Phase.LIVE;
                    phaseEndsAt = now + cfg.ffaTimeLimitSeconds * 20L;
                    for (MatchPlayer mp : connected()) {
                        freeze(mp, false);
                        mp.protectedUntil = now + cfg.spawnProtectionSeconds * 20L;
                    }
                    titleAll(Component.literal("GO").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                            Component.empty(), 0, 15, 5);
                    soundAll("round");
                    hudDirty = true;
                }
            }
            case LIVE -> {
                for (MatchPlayer mp : players.values()) {
                    if (mp.respawnAt > 0 && now >= mp.respawnAt && mp.connected) {
                        mp.respawnAt = 0;
                        spawn(mp, farthestSpawn(mp));
                        mp.protectedUntil = now + cfg.spawnProtectionSeconds * 20L;
                        Match.title(mp.player, Component.empty(),
                                Component.literal(mp.lives + (mp.lives == 1 ? " life" : " lives") + " left")
                                        .withStyle(ChatFormatting.YELLOW), 0, 30, 10);
                        hudDirty = true;
                        boardDirty = true;
                    }
                }
                if (now >= phaseEndsAt) {
                    finishOnTime();
                }
            }
            default -> { }
        }
    }

    @Override
    protected void onDeath(MatchPlayer victim, MatchPlayer killer) {
        victim.lives = Math.max(0, victim.lives - 1);
        if (victim.lives > 0 && victim.connected) {
            victim.respawnAt = now + Settings.get().ffaRespawnSeconds * 20L;
            Match.title(victim.player, Component.literal("ELIMINATED").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    Component.literal("Respawning... " + victim.lives + (victim.lives == 1 ? " life" : " lives") + " left"),
                    0, 50, 10);
        } else if (victim.connected) {
            Match.title(victim.player, Component.literal("OUT").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                    Component.literal("Spectating"), 0, 50, 10);
        }
        checkWinner();
    }

    @Override
    protected void afterLeave(MatchPlayer mp) {
        mp.respawnAt = 0;
        checkWinner();
    }

    private List<MatchPlayer> stillIn() {
        List<MatchPlayer> out = new ArrayList<>();
        for (MatchPlayer mp : players.values()) if (mp.connected && mp.lives > 0) out.add(mp);
        return out;
    }

    private void checkWinner() {
        if (phase == Phase.ENDED) return;
        List<MatchPlayer> in = stillIn();
        if (in.size() <= 1) {
            MatchPlayer w = in.isEmpty() ? null : in.get(0);
            Ironsight.LOG.info("[Ironsight] FFA LAST STANDING match={} winner={}", id, w == null ? "-" : w.name);
            end(w == null ? "Nobody survived" : w.name + " wins!", w, w);
        }
    }

    private void finishOnTime() {
        List<MatchPlayer> in = new ArrayList<>(connected());
        in.sort(Comparator.comparingInt((MatchPlayer m) -> m.lives)
                .thenComparingInt(m -> m.kills)
                .thenComparingDouble(m -> m.damage).reversed());
        MatchPlayer w = in.isEmpty() ? null : in.get(0);
        end(w == null ? "Time!" : w.name + " wins on time!", w, w);
    }

    @Override
    protected dev.ironsight.net.S2C.Hud hud(MatchPlayer viewer) {
        return new dev.ironsight.net.S2C.Hud((byte) mode.ordinal(), (byte) phase.ordinal(), ticksLeft(), (byte) 0,
                (byte) viewer.kills, (byte) 0, (byte) -1, (byte) 0, (byte) 0, (byte) viewer.lives,
                (byte) stillIn().size(), (byte) 0, map.name);
    }
}
