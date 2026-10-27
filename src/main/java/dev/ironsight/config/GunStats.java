package dev.ironsight.config;

/**
 * Every number that defines one gun. Loaded from config/ironsight/guns.json with Gson,
 * so field names are the JSON keys. Times are in server ticks (20 per second), angles in
 * degrees, distances in blocks, damage in half-hearts (a player has 20).
 */
public class GunStats {
    public String name = "Gun";
    /** "primary" or "secondary" - which loadout slot it can go in. */
    public String slot = "primary";

    public float damage = 4f;
    public float headMult = 2f;
    /** Ticks between shots (for burst guns: between the end of one burst and the next). */
    public int fireTicks = 3;
    public boolean auto = true;
    /** Rounds per trigger pull; 1 = normal. */
    public int burst = 1;
    public int burstTicks = 2;
    /** Rays per shot; > 1 makes it a shotgun. */
    public int pellets = 1;

    public int mag = 30;
    public int reserve = 120;
    public int reloadTicks = 40;
    /** Reload one round at a time (shotgun); reloadTicks is then per shell. */
    public boolean shellReload = false;
    public int drawTicks = 10;

    public float range = 100f;
    public float falloffStart = 25f;
    public float falloffEnd = 60f;
    public float minDamageMult = 0.6f;

    public float hipSpread = 2f;
    public float adsSpread = 0.4f;
    /** Extra spread while moving faster than a walk. */
    public float moveSpread = 1.2f;
    public float airSpread = 3f;
    public float bloomPerShot = 0.15f;
    public float maxBloom = 1.5f;
    /** Bloom removed per tick when not firing. */
    public float bloomDecay = 0.15f;

    public float recoilPitch = 0.8f;
    public float recoilYaw = 0.3f;

    public float moveMult = 1f;
    public float adsMoveMult = 0.7f;
    public int adsTicks = 5;
    /** FOV multiplier while fully aimed in (0.5 = 2x zoom). */
    public float adsZoom = 0.8f;
    /** Draws a scope overlay when aimed in. */
    public boolean scope = false;
    /** Enemies see a glint on you while you are scoped in. */
    public boolean glint = false;

    /** Tracer colour, 0xRRGGBB. */
    public int tracerColor = 0xFFE7A0;

    /** Damage of one ray at distance d, before the headshot multiplier. */
    public float damageAt(double d) {
        if (d <= falloffStart) return damage;
        if (d >= falloffEnd) return damage * minDamageMult;
        double t = (d - falloffStart) / (falloffEnd - falloffStart);
        return (float) (damage * (1 - t * (1 - minDamageMult)));
    }

    /** Ticks from the first shot to shot number k (0-based), burst cadence included. */
    public int ticksToShot(int k) {
        if (burst <= 1) return k * fireTicks;
        int cycle = (burst - 1) * burstTicks + fireTicks;
        return (k / burst) * cycle + (k % burst) * burstTicks;
    }

    public boolean isPrimary() {
        return !"secondary".equals(slot);
    }
}
