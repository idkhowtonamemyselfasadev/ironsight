package dev.ironsight.modes;

import dev.ironsight.Ironsight;
import dev.ironsight.config.GunConfig;
import dev.ironsight.config.GunStats;
import dev.ironsight.config.Settings;
import dev.ironsight.guns.GunState;
import dev.ironsight.guns.Guns;
import dev.ironsight.guns.ServerGuns;
import dev.ironsight.maps.ArenaMap;
import dev.ironsight.maps.Arenas;
import dev.ironsight.maps.MapRegistry;
import dev.ironsight.net.Net;
import dev.ironsight.net.S2C;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.GameType;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared machinery of every mode: the arena instance, spawning, loadouts, damage, deaths,
 * spectating, HUD sync and the end-of-match vote. {@link TeamMatch} and {@link FfaMatch}
 * add the rules.
 */
public abstract class Match {

    public static final Identifier SPEED_MOD = Ironsight.id("gun_speed");
    public static final Identifier FREEZE_MOD = Ironsight.id("freeze");

    private static int nextId = 1;

    public final int id = nextId++;
    public final Mode mode;
    public final ArenaMap map;
    public final MinecraftServer server;
    public final ServerLevel level;
    public final int slot;
    public final BlockPos origin;
    public final Map<UUID, MatchPlayer> players = new LinkedHashMap<>();

    public Phase phase;
    public long phaseEndsAt;
    public long now;
    protected boolean hudDirty = true;
    protected boolean boardDirty = true;
    private long lastBoardSent;

    // End of match
    protected MatchPlayer mvp;
    protected String winnerText = "";
    protected boolean voteDone;
    protected long voteDeadline;
    protected long finishAt;
    private boolean finished;

    protected Match(Mode mode, ArenaMap map, MinecraftServer server, int slot) {
        this.mode = mode;
        this.map = map;
        this.server = server;
        this.level = Arenas.level(server);
        this.slot = slot;
        this.origin = Arenas.origin(slot);
    }

    // ------------------------------------------------------------------ lifecycle

    public void start(List<ServerPlayer> joining, long tick) {
        now = tick;
        long ms = Arenas.place(level, map, origin);
        Ironsight.LOG.info("[Ironsight] MATCH START id={} mode={} map={} players={} placeMs={}",
                id, mode.id, map.id, joining.size(), ms);
        for (ServerPlayer p : joining) {
            MatchPlayer mp = new MatchPlayer(p);
            players.put(mp.id, mp);
            Lobby.clearInventory(p);
            p.sendSystemMessage(Component.literal("» " + mode.display + " on " + map.name
                    + (map.description.isEmpty() ? "" : " - " + map.description)).withStyle(ChatFormatting.GOLD));
        }
        begin();
    }

    /** Mode-specific start (called once all players are registered). */
    protected abstract void begin();

    protected abstract void tickRules();

    /** A player lost a life / was eliminated. killer may be null (fall, void, left). */
    protected abstract void onDeath(MatchPlayer victim, MatchPlayer killer);

    protected abstract int teamOf(MatchPlayer mp);

    public final void tick(long tick) {
        now = tick;
        for (MatchPlayer mp : players.values()) {
            if (!mp.connected) continue;
            ServerPlayer p = mp.player;
            if (mp.alive) {
                mp.history.record(now, p.getX(), p.getY(), p.getZ(), p.getBbHeight());
                ServerGuns.tick(this, mp);
                // No hunger, no natural regeneration: food sits at 17 (below the regen
                // threshold of 18, above the sprint threshold of 6) with no saturation.
                if ((now + mp.id.hashCode()) % 20 == 0) {
                    p.getFoodData().setFoodLevel(17);
                    p.getFoodData().setSaturation(0f);
                }
                if (p.getY() < origin.getY() + map.killY || !Arenas.inside(map, origin, p.getX(), p.getZ())) {
                    if (phase == Phase.LIVE) {
                        kill(mp, recentAttacker(mp), mp.lastAttackerGun, false);
                    } else {
                        spawn(mp, spawnFor(mp));
                    }
                    continue;
                }
            } else if (p.isSpectator()) {
                keepSpectating(mp);
            }
        }
        if (phase == Phase.ENDED) {
            tickEnd();
        } else {
            tickRules();
        }
        if (hudDirty) {
            hudDirty = false;
            for (MatchPlayer mp : players.values()) {
                if (mp.connected) Net.send(mp.player, hud(mp));
            }
        }
        if (boardDirty && now - lastBoardSent >= 10) {
            boardDirty = false;
            lastBoardSent = now;
            broadcast(board());
        }
    }

    // ------------------------------------------------------------------ players

    public MatchPlayer get(ServerPlayer p) {
        return players.get(p.getUUID());
    }

    public Collection<MatchPlayer> connected() {
        List<MatchPlayer> out = new ArrayList<>();
        for (MatchPlayer mp : players.values()) if (mp.connected) out.add(mp);
        return out;
    }

    public boolean enemies(MatchPlayer a, MatchPlayer b) {
        if (a == b) return false;
        int ta = teamOf(a), tb = teamOf(b);
        return ta < 0 || tb < 0 || ta != tb;
    }

    protected abstract ArenaMap.Spawn spawnFor(MatchPlayer mp);

    /** Put a player into the arena alive at a spawn, full health, guns refilled. */
    protected void spawn(MatchPlayer mp, ArenaMap.Spawn s) {
        ServerPlayer p = mp.player;
        p.setGameMode(GameType.ADVENTURE);
        p.setCamera(p);
        mp.spectating = null;
        p.teleportTo(level, origin.getX() + s.x(), origin.getY() + s.y(), origin.getZ() + s.z(), Set.of(), s.yaw(), 0f, false);
        p.setHealth(p.getMaxHealth());
        p.removeAllEffects();
        p.setRemainingFireTicks(0);
        p.fallDistance = 0;
        p.getFoodData().setFoodLevel(17);
        p.getFoodData().setSaturation(0f);
        mp.alive = true;
        mp.history.clear();
        mp.lastAttacker = null;
        mp.ads = false;
        giveLoadout(mp);
        for (GunState g : mp.guns) if (g != null) g.refill();
    }

    protected void giveLoadout(MatchPlayer mp) {
        ServerPlayer p = mp.player;
        Lobby.clearInventory(p);
        if (mp.guns[0] == null || !mp.guns[0].gun.equals(mp.primary)) mp.guns[0] = mp.primary == null ? null : new GunState(mp.primary);
        if (mp.guns[1] == null || !mp.guns[1].gun.equals(mp.secondary)) mp.guns[1] = mp.secondary == null ? null : new GunState(mp.secondary);
        if (mp.guns[0] != null) p.getInventory().setItem(0, Guns.create(mp.guns[0].gun));
        if (mp.guns[1] != null) p.getInventory().setItem(1, Guns.create(mp.guns[1].gun));
        p.getInventory().setSelectedSlot(0);
        p.connection.send(new ClientboundSetHeldSlotPacket(0));
        p.inventoryMenu.broadcastChanges();
        mp.heldSlot = -1;
        for (GunState g : mp.guns) if (g != null) g.dirty = true;
    }

    /** Set the loadout from a "primary,secondary" pick, validated against the gun table. */
    public boolean setLoadout(MatchPlayer mp, String primary, String secondary) {
        GunStats a = GunConfig.get(primary), b = GunConfig.get(secondary);
        if (a == null || b == null || !a.isPrimary() || b.isPrimary()) return false;
        mp.primary = primary;
        mp.secondary = secondary;
        return true;
    }

    public void freeze(MatchPlayer mp, boolean frozen) {
        if (mp.frozenApplied == frozen || !mp.connected) return;
        mp.frozenApplied = frozen;
        AttributeInstance speed = mp.player.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance jump = mp.player.getAttribute(Attributes.JUMP_STRENGTH);
        if (frozen) {
            AttributeModifier stop = new AttributeModifier(FREEZE_MOD, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            if (speed != null) speed.addOrReplacePermanentModifier(stop);
            if (jump != null) jump.addOrReplacePermanentModifier(stop);
        } else {
            if (speed != null) speed.removeModifier(FREEZE_MOD);
            if (jump != null) jump.removeModifier(FREEZE_MOD);
        }
    }

    // ------------------------------------------------------------------ damage & death

    /**
     * Apply one shot's tallied damage. Health is set directly rather than through
     * hurtServer: no difficulty scaling, armour, i-frames or knockback can interfere, and
     * the death is ours to handle (no vanilla death screen, no drops).
     * Returns true when this killed the victim.
     */
    public boolean damage(MatchPlayer shooter, MatchPlayer victim, float amount, boolean head, String gun) {
        if (phase != Phase.LIVE || !victim.alive || !victim.connected) return false;
        if (!enemies(shooter, victim) || now < victim.protectedUntil) return false;
        ServerPlayer v = victim.player;
        float hp = v.getHealth();
        float dealt = Math.min(hp, amount);
        shooter.damage += dealt;
        victim.lastAttacker = shooter.id;
        victim.lastAttackerGun = gun;
        victim.lastAttackTick = now;
        boardDirty = true;
        // Red flash + hurt sound on every client, one small vanilla packet.
        level.broadcastDamageEvent(v, level.damageSources().playerAttack(shooter.player));
        if (hp - amount <= 0.0001f) {
            kill(victim, shooter, gun, head);
            return true;
        }
        v.setHealth(hp - amount);
        return false;
    }

    public MatchPlayer recentAttacker(MatchPlayer victim) {
        if (victim.lastAttacker == null || now - victim.lastAttackTick > 200) return null;
        MatchPlayer k = players.get(victim.lastAttacker);
        return k != null && k != victim ? k : null;
    }

    /** Fall damage / void / leaving: vanilla said this player died; make it a match death. */
    public void environmentDeath(MatchPlayer victim) {
        if (phase != Phase.LIVE) {
            victim.player.setHealth(victim.player.getMaxHealth());
            return;
        }
        kill(victim, recentAttacker(victim), victim.lastAttackerGun, false);
    }

    public void kill(MatchPlayer victim, MatchPlayer killer, String gun, boolean head) {
        if (!victim.alive) return;
        victim.alive = false;
        victim.deaths++;
        if (killer != null && killer != victim) killer.kills++;
        int gunIndex = killer != null && gun != null ? GunConfig.index(gun) : -1;
        broadcast(new S2C.Kill(killer != null ? killer.name : "", victim.name, (byte) gunIndex, head,
                (byte) teamOf(killer != null ? killer : victim), (byte) teamOf(victim)));
        Ironsight.LOG.info("[Ironsight] KILL match={} killer={} victim={} gun={} head={}",
                id, killer == null ? "-" : killer.name, victim.name, gun == null ? "-" : gun, head);
        ServerGuns.clearAds(this, victim);
        if (victim.connected) {
            ServerPlayer v = victim.player;
            v.setHealth(v.getMaxHealth());
            Lobby.clearInventory(v);
            v.setGameMode(GameType.SPECTATOR);
            if (killer != null && killer.connected && killer.alive) {
                spectate(victim, killer);
            }
        }
        hudDirty = true;
        boardDirty = true;
        onDeath(victim, killer);
    }

    /** Disconnect or /leave: counts as a death, and the player is out for the rest of the match. */
    public void leave(MatchPlayer mp) {
        if (mp.alive) {
            if (phase == Phase.LIVE) {
                mp.lives = 0;
                kill(mp, recentAttacker(mp), mp.lastAttackerGun, false);
            } else {
                mp.alive = false;
            }
        }
        mp.lives = 0;
        mp.connected = false;
        ServerGuns.clearAds(this, mp);
        freeze(mp, false);
        hudDirty = true;
        boardDirty = true;
        Ironsight.LOG.info("[Ironsight] LEAVE match={} player={}", id, mp.name);
        afterLeave(mp);
    }

    protected void afterLeave(MatchPlayer mp) {
    }

    // ------------------------------------------------------------------ spectating

    /** Who a dead player may watch: teammates in team modes, anyone alive in FFA. */
    protected boolean canWatch(MatchPlayer viewer, MatchPlayer target) {
        if (target == viewer || !target.alive || !target.connected) return false;
        int t = teamOf(viewer);
        return t < 0 || teamOf(target) == t || noTeammatesAlive(viewer);
    }

    private boolean noTeammatesAlive(MatchPlayer viewer) {
        for (MatchPlayer o : players.values()) {
            if (o != viewer && o.alive && o.connected && teamOf(o) == teamOf(viewer)) return false;
        }
        return true;
    }

    public void spectate(MatchPlayer viewer, MatchPlayer target) {
        viewer.spectating = target.id;
        viewer.player.setCamera(target.player);
    }

    public void spectateNext(MatchPlayer viewer) {
        if (viewer.alive) return;
        List<MatchPlayer> options = new ArrayList<>();
        for (MatchPlayer o : players.values()) if (canWatch(viewer, o)) options.add(o);
        if (options.isEmpty()) return;
        int idx = 0;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id.equals(viewer.spectating)) idx = i + 1;
        }
        spectate(viewer, options.get(idx % options.size()));
    }

    /** Keep the camera locked onto a valid target (sneaking out of it just snaps back). */
    private void keepSpectating(MatchPlayer mp) {
        if (now % 5 != 0) return;
        MatchPlayer target = mp.spectating == null ? null : players.get(mp.spectating);
        boolean valid = target != null && canWatch(mp, target) && mp.player.getCamera() == target.player;
        if (!valid) {
            mp.spectating = null;
            spectateNext(mp);
            if (mp.spectating == null && mp.player.getCamera() == mp.player) {
                ArenaMap.Spawn s = map.spectator;
                double x = origin.getX() + s.x(), y = origin.getY() + s.y(), z = origin.getZ() + s.z();
                if (mp.player.distanceToSqr(x, y, z) > 40 * 40) {
                    mp.player.teleportTo(level, x, y, z, Set.of(), s.yaw(), 30f, false);
                }
            }
        }
    }

    // ------------------------------------------------------------------ end of match

    /** Winner decided: celebrate, let the MVP vote, then everybody goes home. */
    protected void end(String winnerText, MatchPlayer winnerFirework, MatchPlayer mvp) {
        if (phase == Phase.ENDED) return;
        phase = Phase.ENDED;
        this.winnerText = winnerText;
        this.mvp = mvp != null && mvp.connected ? mvp : null;
        Settings cfg = Settings.get();
        voteDeadline = now + cfg.voteSeconds * 20L;
        finishAt = now + cfg.celebrateSeconds * 20L;
        phaseEndsAt = voteDeadline;
        hudDirty = true;
        boardDirty = true;
        for (MatchPlayer mp : connected()) {
            freeze(mp, false);
            ServerGuns.clearAds(this, mp);
            title(mp.player, Component.literal(winnerText).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    Component.literal(this.mvp != null ? "MVP: " + this.mvp.name : ""), 10, 70, 20);
            Net.send(mp.player, new S2C.Menu(S2C.Menu.CLOSE, "", 0));
            Ironsight.playTo(mp.player, "win", 1f, 1f);
        }
        if (winnerFirework != null && winnerFirework.connected) {
            fireworks(winnerFirework.player);
        }
        Ironsight.LOG.info("[Ironsight] MATCH END id={} mode={} winner=\"{}\" mvp={}", id, mode.id, winnerText,
                this.mvp == null ? "-" : this.mvp.name);
        if (this.mvp != null) {
            StringBuilder data = new StringBuilder();
            for (ArenaMap m : MapRegistry.all()) {
                if (m.problemFor(mode.team(), mode.perTeam, players.size()) != null) continue;
                if (data.length() > 0) data.append(';');
                data.append(m.id).append('=').append(m.name);
            }
            Net.send(this.mvp.player, new S2C.Menu(S2C.Menu.VOTE, data.toString(), cfg.voteSeconds));
            this.mvp.player.sendSystemMessage(Component.literal("You're the MVP - pick the next " + mode.display + " map!")
                    .withStyle(ChatFormatting.AQUA));
        } else {
            voteDone = true;
            MatchManager.chooseNextMap(mode, null, this);
        }
    }

    public void vote(MatchPlayer voter, String mapId) {
        if (phase != Phase.ENDED || voteDone || voter != mvp) return;
        ArenaMap chosen = MapRegistry.get(mapId);
        if (chosen == null || chosen.problemFor(mode.team(), mode.perTeam, players.size()) != null) return;
        voteDone = true;
        MatchManager.chooseNextMap(mode, chosen, this);
        Net.send(voter.player, new S2C.Menu(S2C.Menu.CLOSE, "", 0));
    }

    private void tickEnd() {
        if (!voteDone && (now >= voteDeadline || mvp == null || !mvp.connected)) {
            voteDone = true;
            MatchManager.chooseNextMap(mode, null, this);
            if (mvp != null && mvp.connected) Net.send(mvp.player, new S2C.Menu(S2C.Menu.CLOSE, "", 0));
        }
        if (voteDone && now >= finishAt) finish();
    }

    /** Everyone back to the lobby, arena slot freed. */
    public void finish() {
        if (finished) return;
        finished = true;
        List<ServerPlayer> requeue = new ArrayList<>();
        for (MatchPlayer mp : players.values()) {
            if (!mp.connected) continue;
            ServerGuns.clearAds(this, mp);
            freeze(mp, false);
            Lobby.send(mp.player);
            Net.send(mp.player, S2C.Hud.none());
            requeue.add(mp.player);
        }
        Arenas.forceChunks(level, map, origin, false);
        Arenas.releaseSlot(slot);
        MatchManager.finished(this, requeue);
    }

    public boolean isFinished() {
        return finished;
    }

    // ------------------------------------------------------------------ sync

    protected abstract S2C.Hud hud(MatchPlayer viewer);

    protected int ticksLeft() {
        return (int) Math.max(0, phaseEndsAt - now);
    }

    protected S2C.Board board() {
        StringBuilder b = new StringBuilder();
        for (MatchPlayer mp : players.values()) {
            if (b.length() > 0) b.append(';');
            b.append(mp.name).append('|').append(teamOf(mp)).append('|').append(mp.kills).append('|')
                    .append(mp.deaths).append('|').append(Math.round(mp.damage)).append('|')
                    .append(mp.alive ? 1 : 0).append('|').append(mode.team() ? -1 : mp.lives)
                    .append(mp.connected ? "" : "|x");
        }
        return new S2C.Board(b.toString());
    }

    public void broadcast(CustomPacketPayload payload) {
        for (MatchPlayer mp : players.values()) {
            if (mp.connected) Net.send(mp.player, payload);
        }
    }

    public void broadcastExcept(CustomPacketPayload payload, MatchPlayer except) {
        for (MatchPlayer mp : players.values()) {
            if (mp.connected && mp != except) Net.send(mp.player, payload);
        }
    }

    public void titleAll(Component title, Component sub, int in, int stay, int out) {
        for (MatchPlayer mp : connected()) title(mp.player, title, sub, in, stay, out);
    }

    public static void title(ServerPlayer p, Component title, Component sub, int in, int stay, int out) {
        send(p, new ClientboundSetTitlesAnimationPacket(in, stay, out));
        send(p, new ClientboundSetSubtitleTextPacket(sub));
        send(p, new ClientboundSetTitleTextPacket(title));
    }

    private static void send(ServerPlayer p, Packet<?> packet) {
        p.connection.send(packet);
    }

    public void soundAll(String name) {
        for (MatchPlayer mp : connected()) Ironsight.playTo(mp.player, name, 1f, 1f);
    }

    private void fireworks(ServerPlayer winner) {
        int[][] colours = {{0xFFD700, 0xFF8C00}, {0x00E5FF, 0xFFFFFF}, {0xFF3B3B, 0xFFD700}};
        for (int i = 0; i < 3; i++) {
            ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
            rocket.set(DataComponents.FIREWORKS, new Fireworks(1 + i % 2, List.of(new FireworkExplosion(
                    i == 1 ? FireworkExplosion.Shape.STAR : FireworkExplosion.Shape.LARGE_BALL,
                    IntList.of(colours[i]), IntList.of(0xFFFFFF), true, true))));
            double a = i * Math.PI * 2 / 3;
            level.addFreshEntity(new FireworkRocketEntity(level, winner.getX() + Math.cos(a) * 1.5, winner.getY() + 0.5,
                    winner.getZ() + Math.sin(a) * 1.5, rocket));
        }
    }

    // ------------------------------------------------------------------ helpers

    protected static String randomOf(List<String> list, java.util.Random random) {
        return list.get(random.nextInt(list.size()));
    }

    public String describe() {
        StringBuilder b = new StringBuilder();
        b.append("#").append(id).append(' ').append(mode.display).append(" on ").append(map.name)
                .append(" phase=").append(phase).append(" players=");
        for (MatchPlayer mp : players.values()) {
            b.append(mp.name).append(mp.alive ? "" : "(dead)").append(mp.connected ? "" : "(left)").append(' ');
        }
        return b.toString().trim();
    }
}
