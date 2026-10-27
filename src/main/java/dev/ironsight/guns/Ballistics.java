package dev.ironsight.guns;

import dev.ironsight.config.GunConfig;
import dev.ironsight.config.GunStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Time-to-kill maths for /gun balance. Uses the same damageAt() and burst cadence as the
 * real shooting code, so the table cannot drift from the game.
 *
 * "perfect" assumes every ray hits. "aimed" is what an aimed-in (ADS), standing player
 * should expect against a standing target: each ray lands with the probability that a
 * uniformly spread cone hits a body-sized disc (radius {@link #TARGET_RADIUS}).
 */
public final class Ballistics {

    public static final double HP = 20.0;
    /** A standing player's torso+legs seen face-on is roughly a 0.6 x 1.3 rectangle. */
    public static final double TARGET_RADIUS = 0.5;

    private Ballistics() {
    }

    /** Probability that one ray within a cone of half-angle spreadDeg hits the target disc at distance d. */
    public static double hitChance(double spreadDeg, double d) {
        double coneRadius = d * Math.tan(Math.toRadians(spreadDeg));
        if (coneRadius <= TARGET_RADIUS) return 1.0;
        double r = TARGET_RADIUS / coneRadius;
        return r * r;
    }

    /** Damage of one trigger pull (all pellets) at distance d, every ray hitting. */
    public static double shotDamage(GunStats s, double d, boolean head) {
        return s.damageAt(d) * (head ? s.headMult : 1f) * s.pellets;
    }

    public static int shotsToKill(GunStats s, double d, boolean head) {
        double per = shotDamage(s, d, head);
        if (per <= 0) return Integer.MAX_VALUE;
        return (int) Math.ceil(HP / per - 1e-6);
    }

    /** Seconds from first shot to the killing shot, or +inf when it cannot kill inside one mag... still counted with reloads. */
    public static double ttkSeconds(GunStats s, double shots) {
        if (!Double.isFinite(shots) || shots > 10_000) return Double.POSITIVE_INFINITY;
        int n = (int) Math.ceil(shots - 1e-6);
        int ticks = s.ticksToShot(n - 1);
        // Mags needed beyond the first one each cost a reload.
        int reloads = (n - 1) / Math.max(1, s.mag);
        ticks += reloads * (s.shellReload ? s.reloadTicks * s.mag : s.reloadTicks);
        return ticks / 20.0;
    }

    /** Expected trigger pulls to kill when aimed in, accounting for spread. */
    public static double expectedShots(GunStats s, double d) {
        double p = hitChance(s.adsSpread, d);
        double per = s.damageAt(d) * s.pellets * p;
        if (d > s.range) return Double.POSITIVE_INFINITY;
        if (s.pellets > 1) {
            // Shotguns: expected damage per blast (pellets landing) vs 20 HP.
            return per <= 0 ? Double.POSITIVE_INFINITY : Math.ceil(HP / per - 1e-6);
        }
        int needed = (int) Math.ceil(HP / s.damageAt(d) - 1e-6);
        return needed / p;
    }

    public static List<String> table() {
        double[] dists = {10, 25, 50};
        List<String> out = new ArrayList<>();
        out.add("TTK vs 20 HP  |  body (perfect) / head (perfect) / aimed-in expected  |  seconds");
        for (String id : GunConfig.IDS) {
            GunStats s = GunConfig.get(id);
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.ROOT, "%-13s", s.name));
            for (double d : dists) {
                String body, head, aimed;
                if (d > s.range) {
                    body = head = aimed = "-";
                } else {
                    int sb = shotsToKill(s, d, false);
                    int sh = shotsToKill(s, d, true);
                    body = fmt(ttkSeconds(s, sb)) + "(" + sb + ")";
                    head = fmt(ttkSeconds(s, sh)) + "(" + sh + ")";
                    aimed = fmt(ttkSeconds(s, expectedShots(s, d)));
                }
                b.append(String.format(Locale.ROOT, " | %2.0fm %s/%s/%s", d, body, head, aimed));
            }
            out.add(b.toString());
        }
        out.add("(n) = trigger pulls. A 0.00 body TTK at range would mean a one-shot - only the shotgun up close may do that.");
        return out;
    }

    private static String fmt(double seconds) {
        if (!Double.isFinite(seconds)) return "inf";
        return String.format(Locale.ROOT, "%.2f", seconds);
    }
}
