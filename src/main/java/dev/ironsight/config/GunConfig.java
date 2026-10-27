package dev.ironsight.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.ironsight.Ironsight;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The gun table. The server reads config/ironsight/guns.json (written with the defaults
 * below on first start) and sends the same JSON to every client on join, so both sides
 * always simulate with identical numbers. Edit the file and run /gun reload to rebalance
 * without restarting.
 */
public final class GunConfig {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Gun ids in display order. The texture/model/sound names are derived from these. */
    public static final List<String> IDS = List.of(
            "pistol", "revolver", "smg", "vector", "rifle", "burst", "shotgun", "lmg", "dmr", "sniper");

    private static volatile Map<String, GunStats> guns = defaults();
    private static volatile String json = GSON.toJson(guns);

    private GunConfig() {
    }

    public static GunStats get(String id) {
        return guns.get(id);
    }

    /** Index of a gun id in {@link #IDS}, or -1. Used to keep packets small. */
    public static int index(String id) {
        return IDS.indexOf(id);
    }

    public static String idAt(int index) {
        return index >= 0 && index < IDS.size() ? IDS.get(index) : null;
    }

    public static Map<String, GunStats> all() {
        return guns;
    }

    public static String json() {
        return json;
    }

    public static List<String> ofSlot(boolean primary) {
        List<String> out = new ArrayList<>();
        for (String id : IDS) {
            GunStats s = guns.get(id);
            if (s != null && s.isPrimary() == primary) out.add(id);
        }
        return Collections.unmodifiableList(out);
    }

    /** Server: load (or create) the file. Returns an error message, or null on success. */
    public static String load(Path dir) {
        Path file = dir.resolve("guns.json");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dir);
                Files.writeString(file, GSON.toJson(defaults()));
            }
            return apply(Files.readString(file));
        } catch (IOException e) {
            return "Could not read " + file + ": " + e.getMessage();
        }
    }

    /** Parse a gun table (from the file or from the server's sync packet). */
    public static String apply(String text) {
        try {
            Map<String, GunStats> parsed = GSON.fromJson(text, new TypeToken<LinkedHashMap<String, GunStats>>() { }.getType());
            if (parsed == null) return "guns.json is empty";
            Map<String, GunStats> merged = new LinkedHashMap<>();
            Map<String, GunStats> def = defaults();
            for (String id : IDS) {
                // A gun missing from an edited file falls back to its default, so a typo
                // never deletes a weapon from the game.
                merged.put(id, parsed.getOrDefault(id, def.get(id)));
            }
            guns = merged;
            json = GSON.toJson(merged);
            return null;
        } catch (RuntimeException e) {
            Ironsight.LOG.error("Bad gun table", e);
            return "guns.json is not valid: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ defaults

    /**
     * The shipped balance. Rules it is built around (20 HP players):
     * nothing kills a full-health body in one shot except the shotgun at point-blank;
     * the sniper is a 2-shot body / 1-shot head gun with a 1.5 s bolt, so an AR player who
     * lands their shots wins at 25-50 blocks. Run /gun balance for the TTK table.
     */
    public static Map<String, GunStats> defaults() {
        Map<String, GunStats> m = new LinkedHashMap<>();

        GunStats g = new GunStats();
        g.name = "Pistol"; g.slot = "secondary";
        g.damage = 4f; g.headMult = 2f; g.fireTicks = 4; g.auto = false;
        g.mag = 12; g.reserve = 48; g.reloadTicks = 28; g.drawTicks = 5;
        g.range = 60; g.falloffStart = 15; g.falloffEnd = 40; g.minDamageMult = 0.6f;
        g.hipSpread = 1.4f; g.adsSpread = 0.5f; g.moveSpread = 0.6f; g.bloomPerShot = 0.25f; g.maxBloom = 1.2f; g.bloomDecay = 0.2f;
        g.recoilPitch = 1.4f; g.recoilYaw = 0.4f; g.moveMult = 1.0f; g.adsMoveMult = 0.8f; g.adsTicks = 3; g.adsZoom = 0.85f;
        g.tracerColor = 0xFFE7A0;
        m.put("pistol", g);

        g = new GunStats();
        g.name = "Revolver"; g.slot = "secondary";
        g.damage = 8f; g.headMult = 2f; g.fireTicks = 14; g.auto = false;
        g.mag = 6; g.reserve = 24; g.reloadTicks = 50; g.drawTicks = 9;
        g.range = 70; g.falloffStart = 20; g.falloffEnd = 50; g.minDamageMult = 0.7f;
        g.hipSpread = 2.0f; g.adsSpread = 0.3f; g.moveSpread = 1.2f; g.bloomPerShot = 0.8f; g.maxBloom = 2f; g.bloomDecay = 0.15f;
        g.recoilPitch = 4.5f; g.recoilYaw = 1.0f; g.moveMult = 0.97f; g.adsMoveMult = 0.75f; g.adsTicks = 4; g.adsZoom = 0.8f;
        g.tracerColor = 0xFFC870;
        m.put("revolver", g);

        g = new GunStats();
        g.name = "SMG";
        g.damage = 4.5f; g.headMult = 1.5f; g.fireTicks = 2; g.auto = true;
        g.mag = 30; g.reserve = 120; g.reloadTicks = 36; g.drawTicks = 8;
        g.range = 40; g.falloffStart = 8; g.falloffEnd = 25; g.minDamageMult = 0.4f;
        g.hipSpread = 2.2f; g.adsSpread = 1.4f; g.moveSpread = 0.4f; g.bloomPerShot = 0.1f; g.maxBloom = 1.2f; g.bloomDecay = 0.2f;
        g.recoilPitch = 0.6f; g.recoilYaw = 0.45f; g.moveMult = 1.05f; g.adsMoveMult = 0.85f; g.adsTicks = 3; g.adsZoom = 0.9f;
        g.tracerColor = 0xA0F0FF;
        m.put("smg", g);

        g = new GunStats();
        g.name = "Vector";
        g.damage = 3f; g.headMult = 1.5f; g.fireTicks = 1; g.auto = true;
        g.mag = 20; g.reserve = 100; g.reloadTicks = 30; g.drawTicks = 8;
        g.range = 35; g.falloffStart = 6; g.falloffEnd = 20; g.minDamageMult = 0.4f;
        g.hipSpread = 2.6f; g.adsSpread = 1.8f; g.moveSpread = 0.4f; g.bloomPerShot = 0.08f; g.maxBloom = 1.4f; g.bloomDecay = 0.25f;
        g.recoilPitch = 0.45f; g.recoilYaw = 0.5f; g.moveMult = 1.05f; g.adsMoveMult = 0.85f; g.adsTicks = 3; g.adsZoom = 0.9f;
        g.tracerColor = 0xC8FF8A;
        m.put("vector", g);

        g = new GunStats();
        g.name = "Assault Rifle";
        g.damage = 4f; g.headMult = 2f; g.fireTicks = 3; g.auto = true;
        g.mag = 30; g.reserve = 120; g.reloadTicks = 44; g.drawTicks = 12;
        g.range = 110; g.falloffStart = 25; g.falloffEnd = 60; g.minDamageMult = 0.7f;
        g.hipSpread = 2.0f; g.adsSpread = 0.3f; g.moveSpread = 1.4f; g.bloomPerShot = 0.12f; g.maxBloom = 1.0f; g.bloomDecay = 0.15f;
        g.recoilPitch = 0.75f; g.recoilYaw = 0.35f; g.moveMult = 0.95f; g.adsMoveMult = 0.65f; g.adsTicks = 5; g.adsZoom = 0.75f;
        g.tracerColor = 0xFFD080;
        m.put("rifle", g);

        g = new GunStats();
        g.name = "Burst Rifle";
        g.damage = 5f; g.headMult = 2f; g.fireTicks = 8; g.auto = false; g.burst = 3; g.burstTicks = 2;
        g.mag = 24; g.reserve = 96; g.reloadTicks = 46; g.drawTicks = 12;
        g.range = 110; g.falloffStart = 30; g.falloffEnd = 70; g.minDamageMult = 0.7f;
        g.hipSpread = 1.8f; g.adsSpread = 0.25f; g.moveSpread = 1.4f; g.bloomPerShot = 0.1f; g.maxBloom = 0.8f; g.bloomDecay = 0.15f;
        g.recoilPitch = 0.9f; g.recoilYaw = 0.25f; g.moveMult = 0.95f; g.adsMoveMult = 0.65f; g.adsTicks = 5; g.adsZoom = 0.72f;
        g.tracerColor = 0xE0B0FF;
        m.put("burst", g);

        g = new GunStats();
        g.name = "Shotgun";
        g.damage = 3f; g.headMult = 1.5f; g.pellets = 8; g.fireTicks = 18; g.auto = false;
        g.mag = 6; g.reserve = 24; g.reloadTicks = 11; g.shellReload = true; g.drawTicks = 10;
        g.range = 30; g.falloffStart = 4; g.falloffEnd = 14; g.minDamageMult = 0.15f;
        g.hipSpread = 6f; g.adsSpread = 5f; g.moveSpread = 0.5f; g.bloomPerShot = 0f; g.maxBloom = 0f; g.bloomDecay = 0.2f;
        g.recoilPitch = 5f; g.recoilYaw = 1.2f; g.moveMult = 1.0f; g.adsMoveMult = 0.8f; g.adsTicks = 4; g.adsZoom = 0.9f;
        g.tracerColor = 0xFFB070;
        m.put("shotgun", g);

        g = new GunStats();
        g.name = "LMG";
        g.damage = 4.5f; g.headMult = 1.75f; g.fireTicks = 3; g.auto = true;
        g.mag = 100; g.reserve = 200; g.reloadTicks = 100; g.drawTicks = 18;
        g.range = 100; g.falloffStart = 25; g.falloffEnd = 60; g.minDamageMult = 0.65f;
        g.hipSpread = 3.0f; g.adsSpread = 0.7f; g.moveSpread = 2.0f; g.bloomPerShot = 0.1f; g.maxBloom = 1.6f; g.bloomDecay = 0.1f;
        g.recoilPitch = 0.65f; g.recoilYaw = 0.55f; g.moveMult = 0.8f; g.adsMoveMult = 0.5f; g.adsTicks = 8; g.adsZoom = 0.78f;
        g.tracerColor = 0xFFE060;
        m.put("lmg", g);

        g = new GunStats();
        g.name = "DMR";
        g.damage = 7f; g.headMult = 2f; g.fireTicks = 7; g.auto = false;
        g.mag = 10; g.reserve = 40; g.reloadTicks = 50; g.drawTicks = 14;
        g.range = 160; g.falloffStart = 50; g.falloffEnd = 100; g.minDamageMult = 0.8f;
        g.hipSpread = 2.5f; g.adsSpread = 0.1f; g.moveSpread = 2.0f; g.bloomPerShot = 0.5f; g.maxBloom = 1.5f; g.bloomDecay = 0.2f;
        g.recoilPitch = 2.2f; g.recoilYaw = 0.4f; g.moveMult = 0.92f; g.adsMoveMult = 0.55f; g.adsTicks = 7; g.adsZoom = 0.5f;
        g.tracerColor = 0x90FFF0;
        m.put("dmr", g);

        g = new GunStats();
        g.name = "Sniper";
        g.damage = 12f; g.headMult = 2f; g.fireTicks = 30; g.auto = false;
        g.mag = 5; g.reserve = 20; g.reloadTicks = 60; g.drawTicks = 20;
        g.range = 220; g.falloffStart = 220; g.falloffEnd = 221; g.minDamageMult = 1f;
        g.hipSpread = 8f; g.adsSpread = 0f; g.moveSpread = 3f; g.airSpread = 8f; g.bloomPerShot = 0f; g.maxBloom = 0f; g.bloomDecay = 0.2f;
        g.recoilPitch = 6f; g.recoilYaw = 0.8f; g.moveMult = 0.9f; g.adsMoveMult = 0.35f; g.adsTicks = 9; g.adsZoom = 0.22f;
        g.scope = true; g.glint = true;
        g.tracerColor = 0xFFFFFF;
        m.put("sniper", g);

        return m;
    }

    /** Quick sanity view used by /gun reload. */
    public static JsonObject summary() {
        JsonObject o = new JsonObject();
        guns.forEach((k, v) -> o.addProperty(k, v.name));
        return o;
    }
}
