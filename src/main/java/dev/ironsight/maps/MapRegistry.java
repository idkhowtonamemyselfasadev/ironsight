package dev.ironsight.maps;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ironsight.Ironsight;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * All arena maps, read from config/ironsight/maps/&lt;id&gt;.nbt + &lt;id&gt;.json.
 * The built-in maps ship inside the jar and are copied there on first start (an existing
 * file is never overwritten, so edits survive; delete a file to get the default back).
 */
public final class MapRegistry {

    private static final Map<String, ArenaMap> MAPS = new LinkedHashMap<>();
    private static ArenaMap lobby;
    private static JsonObject lobbyJson;

    private MapRegistry() {
    }

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("ironsight").resolve("maps");
    }

    public static synchronized List<String> load(MinecraftServer server) {
        List<String> log = new ArrayList<>();
        Path dir = dir();
        try {
            Files.createDirectories(dir);
            copyBuiltins(dir, log);
        } catch (IOException e) {
            log.add("Cannot create " + dir + ": " + e.getMessage());
        }
        MAPS.clear();
        lobby = null;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path json : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String id = json.getFileName().toString().replace(".json", "");
                Path nbt = dir.resolve(id + ".nbt");
                if (!Files.exists(nbt)) {
                    log.add(id + ": missing " + nbt.getFileName());
                    continue;
                }
                try {
                    JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                    if (!meta.has("id")) meta.addProperty("id", id);
                    CompoundTag tag = NbtIo.readCompressed(nbt, NbtAccounter.unlimitedHeap());
                    StructureTemplate template = server.getStructureManager().readStructure(tag);
                    ArenaMap map = new ArenaMap(meta, template);
                    if ("lobby".equals(id)) {
                        lobby = map;
                        lobbyJson = meta;
                    } else {
                        MAPS.put(id, map);
                        log.add("map " + id + " (" + map.name + ") " + map.sizeX + "x" + map.sizeY + "x" + map.sizeZ
                                + " A" + map.teamA.size() + " B" + map.teamB.size() + " ffa" + map.ffa.size());
                    }
                } catch (Exception e) {
                    log.add(id + ": failed to load: " + e);
                    Ironsight.LOG.error("Map {} failed to load", id, e);
                }
            }
        } catch (IOException e) {
            log.add("Cannot list " + dir + ": " + e.getMessage());
        }
        return log;
    }

    private static void copyBuiltins(Path dir, List<String> log) throws IOException {
        Path root = FabricLoader.getInstance().getModContainer(Ironsight.MOD_ID).orElseThrow()
                .findPath("data/ironsight/arenas").orElse(null);
        if (root == null) return;
        try (Stream<Path> files = Files.list(root)) {
            for (Path p : files.toList()) {
                Path target = dir.resolve(p.getFileName().toString());
                if (!Files.exists(target)) {
                    try (InputStream in = Files.newInputStream(p)) {
                        Files.copy(in, target);
                    }
                    log.add("installed built-in " + target.getFileName());
                }
            }
        }
    }

    public static synchronized List<ArenaMap> all() {
        return Collections.unmodifiableList(new ArrayList<>(MAPS.values()));
    }

    public static synchronized ArenaMap get(String id) {
        return MAPS.get(id);
    }

    public static synchronized ArenaMap lobby() {
        return lobby;
    }

    public static synchronized JsonObject lobbyJson() {
        return lobbyJson;
    }
}
