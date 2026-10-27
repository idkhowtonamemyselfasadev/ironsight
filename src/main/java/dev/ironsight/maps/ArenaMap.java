package dev.ironsight.maps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;

/** One arena: the block template plus its spawn metadata (all positions relative to the template origin). */
public final class ArenaMap {

    /** x, y, z, yaw. */
    public record Spawn(double x, double y, double z, float yaw) { }

    public final String id;
    public final String name;
    public final String description;
    public final int sizeX, sizeY, sizeZ;
    public final List<Spawn> teamA;
    public final List<Spawn> teamB;
    public final List<Spawn> ffa;
    public final Spawn spectator;
    public final int killY;
    public final StructureTemplate template;

    public ArenaMap(JsonObject json, StructureTemplate template) {
        this.id = json.get("id").getAsString();
        this.name = json.has("name") ? json.get("name").getAsString() : id;
        this.description = json.has("description") ? json.get("description").getAsString() : "";
        JsonArray size = json.getAsJsonArray("size");
        this.sizeX = size.get(0).getAsInt();
        this.sizeY = size.get(1).getAsInt();
        this.sizeZ = size.get(2).getAsInt();
        this.teamA = spawns(json, "teamA");
        this.teamB = spawns(json, "teamB");
        this.ffa = spawns(json, "ffa");
        this.spectator = json.has("spectator") ? spawn(json.getAsJsonArray("spectator"))
                : new Spawn(sizeX / 2.0, sizeY - 2, sizeZ / 2.0, 0);
        this.killY = json.has("killY") ? json.get("killY").getAsInt() : -1;
        this.template = template;
    }

    private static List<Spawn> spawns(JsonObject json, String key) {
        List<Spawn> out = new ArrayList<>();
        if (!json.has(key)) return out;
        for (JsonElement e : json.getAsJsonArray(key)) out.add(spawn(e.getAsJsonArray()));
        return out;
    }

    private static Spawn spawn(JsonArray a) {
        // Spawns name the block the feet stand in; stand in its centre.
        return new Spawn(a.get(0).getAsDouble() + 0.5, a.get(1).getAsDouble(), a.get(2).getAsDouble() + 0.5,
                a.size() > 3 ? a.get(3).getAsFloat() : 0f);
    }

    /** Problems that make this map unusable for a mode, or null. */
    public String problemFor(boolean team, int perTeam, int ffaPlayers) {
        if (team && (teamA.size() < perTeam || teamB.size() < perTeam)) {
            return name + " needs " + perTeam + " spawns per team";
        }
        if (!team && ffa.size() < Math.min(ffaPlayers, 4)) {
            return name + " has too few FFA spawns";
        }
        return null;
    }
}
