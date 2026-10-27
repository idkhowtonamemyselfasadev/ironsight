package dev.ironsight.client;

import dev.ironsight.config.GunConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** config/ironsight-client.json: per-player HUD preferences. */
@Environment(EnvType.CLIENT)
public final class ClientConfig {
    public boolean damageNumbers = true;
    public boolean hitSounds = true;

    private static ClientConfig current = new ClientConfig();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("ironsight-client.json");
    }

    public static ClientConfig get() {
        return current;
    }

    public static void load() {
        try {
            if (Files.exists(file())) {
                ClientConfig c = GunConfig.GSON.fromJson(Files.readString(file()), ClientConfig.class);
                if (c != null) current = c;
            }
        } catch (Exception ignored) {
            // A broken file just means defaults.
        }
        save();
    }

    public static void save() {
        try {
            Files.writeString(file(), GunConfig.GSON.toJson(current));
        } catch (Exception ignored) {
        }
    }
}
