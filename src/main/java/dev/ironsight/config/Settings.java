package dev.ironsight.config;

import dev.ironsight.Ironsight;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Match rules and timings: config/ironsight/settings.json (server only). */
public class Settings {

    // Team modes
    public int teamPickSeconds = 10;
    public int freezeSeconds = 10;
    public int roundSeconds = 120;
    public int roundEndSeconds = 4;
    public int roundsToWin = 7;
    /** Swap sides after this many rounds (CS style: half of the maximum). */
    public int halftimeAfter = 6;

    // FFA
    public int ffaMinPlayers = 2;
    public int ffaMaxPlayers = 12;
    public int ffaCountdownSeconds = 15;
    public int ffaLives = 3;
    public int ffaRespawnSeconds = 3;
    public int ffaTimeLimitSeconds = 600;
    public int spawnProtectionSeconds = 3;

    // End of match
    public int voteSeconds = 15;
    public int celebrateSeconds = 5;
    /** Put everybody from a finished match straight back into the same queue. */
    public boolean autoRequeue = true;

    // Netcode
    /** Maximum lag compensation; shots are checked against where targets were this long ago at most. */
    public int lagCompMaxMs = 200;

    /**
     * Set server gamerules for a minigame server on start: pvp and fall damage on, no
     * natural regen, no death messages, no mobs, fixed noon, no weather.
     */
    public boolean manageGamerules = true;

    // Lobby
    /** Build the lobby structure into the overworld on first start (once; remembered in state.json). */
    public boolean buildLobby = true;
    public int lobbyX = 0;
    public int lobbyY = 120;
    public int lobbyZ = 0;

    private static Settings current = new Settings();

    public static Settings get() {
        return current;
    }

    public static String load(Path dir) {
        Path file = dir.resolve("settings.json");
        try {
            Files.createDirectories(dir);
            Settings loaded = Files.exists(file)
                    ? GunConfig.GSON.fromJson(Files.readString(file), Settings.class)
                    : new Settings();
            if (loaded == null) loaded = new Settings();
            current = loaded;
            // Write back so new keys added by an update show up in the file.
            Files.writeString(file, GunConfig.GSON.toJson(loaded));
            return null;
        } catch (IOException | RuntimeException e) {
            Ironsight.LOG.error("Bad settings.json", e);
            return "settings.json: " + e.getMessage();
        }
    }
}
