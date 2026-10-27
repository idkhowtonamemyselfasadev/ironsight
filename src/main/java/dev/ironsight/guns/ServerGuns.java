package dev.ironsight.guns;

import dev.ironsight.Ironsight;
import dev.ironsight.config.GunConfig;
import dev.ironsight.config.GunStats;
import dev.ironsight.config.Settings;
import dev.ironsight.modes.Match;
import dev.ironsight.modes.MatchPlayer;
import dev.ironsight.modes.Phase;
import dev.ironsight.net.Net;
import dev.ironsight.net.S2C;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server side of shooting. Every rule lives here: fire rate, ammo, reload, draw time,
 * spread, burst cadence. The client's shot packet says only "I pulled the trigger, looking
 * this way"; the server decides whether a bullet left the barrel and where it went.
 */
public final class ServerGuns {

    /** Allowed difference between the aim in a shot packet and the server's view of the player's rotation. */
    private static final float MAX_AIM_DRIFT = 25f;
    private static final int MAX_TRACERS = 4;

    private static final RandomSource RANDOM = RandomSource.create();

    private ServerGuns() {
    }

    // ------------------------------------------------------------------ per tick

    public static void tick(Match match, MatchPlayer mp) {
        ServerPlayer p = mp.player;
        long now = match.now;
        int sel = p.getInventory().getSelectedSlot();
        int slot = sel <= 1 && mp.guns[sel] != null && Guns.isGun(p.getInventory().getItem(sel)) ? sel : -1;
        if (slot != mp.heldSlot) {
            GunState old = mp.held();
            if (old != null && old.reloading()) {
                // Swapping away cancels the reload, CS style.
                old.reloadDoneAt = 0;
                old.dirty = true;
            }
            if (old != null) old.burstLeft = 0;
            mp.heldSlot = slot;
            GunState g = mp.held();
            if (g != null) {
                g.drawDoneAt = now + g.stats().drawTicks;
                g.dirty = true;
            }
            if (mp.ads) setAds(match, mp, false);
        }

        for (GunState g : mp.guns) {
            if (g == null) continue;
            GunStats s = g.stats();
            if (g.bloom > 0 && now - g.nextFireAt > 0) g.bloom = Math.max(0, g.bloom - s.bloomDecay);
            if (g.reloadDoneAt > 0 && now >= g.reloadDoneAt) finishReloadStep(g, now);
            if (g.dirty) {
                g.dirty = false;
                int left = g.reloadDoneAt > 0 ? (int) (g.reloadDoneAt - now) : 0;
                int total = g.reloadDoneAt > 0 ? s.reloadTicks : 0;
                Net.send(p, new S2C.Ammo((byte) GunConfig.index(g.gun), (short) g.mag, (short) g.reserve,
                        (short) left, (short) total));
            }
        }

        GunState held = mp.held();
        if (held != null && held.burstLeft > 0 && now >= held.burstNextAt) {
            fireOne(match, mp, held, p.getYRot(), p.getXRot());
            held.burstLeft = held.mag > 0 ? held.burstLeft - 1 : 0;
            held.burstNextAt = now + held.stats().burstTicks;
            if (held.burstLeft == 0) held.nextFireAt = now + held.stats().fireTicks;
        }

        double mult = 1.0;
        if (held != null) {
            GunStats s = held.stats();
            mult = mp.ads ? s.moveMult * s.adsMoveMult : s.moveMult;
        }
        if (Math.abs(mult - mp.appliedSpeedMult) > 1e-4) {
            mp.appliedSpeedMult = mult;
            AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                speed.removeModifier(Match.SPEED_MOD);
                if (Math.abs(mult - 1.0) > 1e-4) {
                    speed.addOrReplacePermanentModifier(new AttributeModifier(Match.SPEED_MOD, mult - 1.0,
                            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                }
            }
        }
    }

    private static void finishReloadStep(GunState g, long now) {
        GunStats s = g.stats();
        if (s.shellReload) {
            if (g.reserve > 0 && g.mag < s.mag) {
                g.mag++;
                g.reserve--;
            }
            g.reloadDoneAt = g.mag < s.mag && g.reserve > 0 ? now + s.reloadTicks : 0;
        } else {
            int take = Math.min(s.mag - g.mag, g.reserve);
            g.mag += take;
            g.reserve -= take;
            g.reloadDoneAt = 0;
        }
        g.dirty = true;
    }

    // ------------------------------------------------------------------ actions

    public static void shoot(Match match, MatchPlayer mp, float yaw, float pitch) {
        if (!mp.alive || !mp.connected || match.phase != Phase.LIVE) return;
        GunState g = mp.held();
        if (g == null) return;
        GunStats s = g.stats();
        long now = match.now;
        if (now < g.drawDoneAt || g.burstLeft > 0) return;
        // One tick of slack for network jitter, but no sustained rate above the gun's.
        if (now + 1 < g.nextFireAt) return;
        if (g.reloading()) {
            if (s.shellReload && g.mag > 0) {
                g.reloadDoneAt = 0; // a pump gun can fire out of a shell reload
            } else {
                return;
            }
        }
        if (g.mag <= 0) {
            Ironsight.playTo(mp.player, "empty", 0.8f, 1f);
            startReload(mp, g, now);
            return;
        }
        g.nextFireAt = Math.max(g.nextFireAt, now - 1) + s.fireTicks;

        // Trust the packet's aim only while it agrees with what the server has seen.
        ServerPlayer p = mp.player;
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)
                || Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) > MAX_AIM_DRIFT
                || Math.abs(pitch - p.getXRot()) > MAX_AIM_DRIFT) {
            yaw = p.getYRot();
            pitch = p.getXRot();
        }
        pitch = Mth.clamp(pitch, -90f, 90f);

        fireOne(match, mp, g, yaw, pitch);
        if (s.burst > 1) {
            g.burstLeft = Math.min(s.burst - 1, g.mag);
            g.burstNextAt = now + s.burstTicks;
            if (g.burstLeft == 0) g.nextFireAt = now + s.fireTicks;
        }
        // Firing gives up spawn protection.
        mp.protectedUntil = 0;
    }

    private static void fireOne(Match match, MatchPlayer mp, GunState g, float yaw, float pitch) {
        if (g.mag <= 0) return;
        GunStats s = g.stats();
        ServerPlayer p = mp.player;
        g.mag--;
        g.dirty = true;

        double spread = currentSpread(match, mp, g);
        g.bloom = Math.min(s.maxBloom, g.bloom + s.bloomPerShot);

        Vec3 eye = p.getEyePosition();
        int rewind = rewindTicks(p);
        List<Hitscan.Target> targets = new ArrayList<>(8);
        double[] sample = new double[4];
        for (MatchPlayer o : match.players.values()) {
            if (o == mp || !o.alive || !o.connected || !match.enemies(mp, o)) continue;
            if (o.history.sample(match.now, rewind, sample)) {
                targets.add(new Hitscan.Target(o, sample[0], sample[1], sample[2], sample[3]));
            } else {
                targets.add(new Hitscan.Target(o, o.player.getX(), o.player.getY(), o.player.getZ(), o.player.getBbHeight()));
            }
        }

        Map<MatchPlayer, float[]> tally = new IdentityHashMap<>(4); // victim -> {damage, headshot?1:0}
        int rays = Math.max(1, s.pellets);
        int drawn = Math.min(rays, MAX_TRACERS);
        float[] ends = new float[drawn * 3];
        for (int i = 0; i < rays; i++) {
            Vec3 dir = Hitscan.direction(yaw, pitch, spread, RANDOM);
            Hitscan.Result r = Hitscan.cast(match.level, eye, dir, s.range, targets, p);
            if (i < drawn) {
                ends[i * 3] = (float) r.end().x;
                ends[i * 3 + 1] = (float) r.end().y;
                ends[i * 3 + 2] = (float) r.end().z;
            }
            if (r.target() instanceof MatchPlayer victim) {
                float dmg = s.damageAt(r.distance()) * (r.head() ? s.headMult : 1f);
                float[] t = tally.computeIfAbsent(victim, k -> new float[2]);
                t[0] += dmg;
                if (r.head()) t[1] = 1;
            }
        }

        // Tracer + sound for everybody else; the shooter predicted their own.
        Vec3 muzzle = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(0.6)).add(0, -0.15, 0);
        match.broadcastExcept(new S2C.Shot(p.getId(), (byte) GunConfig.index(g.gun), (float) muzzle.x, (float) muzzle.y,
                (float) muzzle.z, ends), mp);

        // One damage application per victim per shot (all pellets summed).
        for (Map.Entry<MatchPlayer, float[]> e : tally.entrySet()) {
            float amount = e.getValue()[0];
            boolean head = e.getValue()[1] > 0;
            boolean killed = match.damage(mp, e.getKey(), amount, head, g.gun);
            Net.send(p, new S2C.Hit(amount, (byte) ((head ? 1 : 0) | (killed ? 2 : 0))));
        }
        if (g.mag == 0 && g.reserve > 0) startReload(mp, g, match.now + Math.max(4, s.fireTicks));
    }

    private static int rewindTicks(ServerPlayer p) {
        int ms = Math.min(p.connection.latency(), Settings.get().lagCompMaxMs);
        // Half the round trip is how stale the shooter's picture of the world is, plus the
        // client's interpolation of other players (about one tick).
        return Math.max(0, Math.round(ms / 2f / 50f) + 1);
    }

    public static double currentSpread(Match match, MatchPlayer mp, GunState g) {
        GunStats s = g.stats();
        ServerPlayer p = mp.player;
        double adsT = mp.ads ? Math.min(1.0, (match.now - mp.adsSince) / (double) Math.max(1, s.adsTicks)) : 0.0;
        double base = s.hipSpread + (s.adsSpread - s.hipSpread) * adsT;
        double[] a = new double[4], b = new double[4];
        double speed = 0;
        if (mp.history.sample(match.now, 0, a) && mp.history.sample(match.now, 2, b)) {
            double dx = a[0] - b[0], dz = a[2] - b[2];
            speed = Math.sqrt(dx * dx + dz * dz) / 2.0; // blocks per tick
        }
        double spread = base + g.bloom;
        // Walking is ~0.22 b/t, sprinting ~0.28; only faster-than-careful movement costs accuracy.
        if (speed > 0.12) spread += s.moveSpread * Math.min(1.0, (speed - 0.12) / 0.16);
        if (!p.onGround()) spread += s.airSpread;
        if (p.isCrouching() && p.onGround()) spread *= 0.8;
        return spread;
    }

    public static void reload(Match match, MatchPlayer mp) {
        GunState g = mp.held();
        if (g == null || !mp.alive) return;
        startReload(mp, g, match.now);
    }

    private static void startReload(MatchPlayer mp, GunState g, long at) {
        GunStats s = g.stats();
        if (g.reloading() || g.mag >= s.mag || g.reserve <= 0) return;
        g.reloadDoneAt = at + s.reloadTicks;
        g.burstLeft = 0;
        g.dirty = true;
        mp.player.level().playSound(null, mp.player.getX(), mp.player.getY(), mp.player.getZ(),
                Ironsight.sound(s.shellReload ? "pump" : "reload"), SoundSource.PLAYERS, 0.7f, 1f);
        if (mp.ads) mp.ads = false;
    }

    public static void setAds(Match match, MatchPlayer mp, boolean on) {
        if (on && (!mp.alive || mp.held() == null)) on = false;
        if (mp.ads == on) return;
        mp.ads = on;
        mp.adsSince = match.now;
        GunState g = mp.held();
        boolean glint = on && g != null && g.stats().glint;
        if (glint != mp.glintSent) {
            mp.glintSent = glint;
            S2C.Glint packet = new S2C.Glint(mp.player.getId(), glint);
            for (MatchPlayer o : match.players.values()) {
                if (o.connected && o != mp && match.enemies(mp, o)) Net.send(o.player, packet);
            }
        }
    }

    public static void clearAds(Match match, MatchPlayer mp) {
        if (mp.ads || mp.glintSent) {
            mp.ads = true;
            setAds(match, mp, false);
        }
        mp.appliedSpeedMult = 1.0;
        AttributeInstance speed = mp.player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(Match.SPEED_MOD);
    }
}
