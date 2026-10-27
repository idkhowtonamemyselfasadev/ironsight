package dev.ironsight;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.ironsight.config.GunConfig;
import dev.ironsight.guns.Ballistics;
import dev.ironsight.guns.Guns;
import dev.ironsight.maps.ArenaMap;
import dev.ironsight.maps.MapRegistry;
import dev.ironsight.modes.Match;
import dev.ironsight.modes.MatchManager;
import dev.ironsight.modes.Mode;
import dev.ironsight.net.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** /play, /leave and the /gun admin and debug tree. */
public final class Commands {

    private Commands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        java.util.function.Predicate<CommandSourceStack> op = net.minecraft.commands.Commands.<CommandSourceStack>hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS);

        d.register(literal("play")
                .executes(c -> {
                    MatchManager.openPlayMenu(c.getSource().getPlayerOrException());
                    return 1;
                })
                .then(argument("mode", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("duel", "team2", "team4", "ffa"), b))
                        .executes(c -> {
                            Mode mode = Mode.byId(StringArgumentType.getString(c, "mode"));
                            if (mode == null) return fail(c, "Modes: duel, team2, team4, ffa");
                            MatchManager.join(c.getSource().getPlayerOrException(), mode);
                            return 1;
                        })));

        d.register(literal("leave").executes(c -> {
            MatchManager.leave(c.getSource().getPlayerOrException());
            return 1;
        }));

        d.register(literal("gun")
                .then(literal("balance").executes(c -> {
                    for (String line : Ballistics.table()) {
                        c.getSource().sendSuccess(() -> Component.literal(line), false);
                        Ironsight.LOG.info("[Ironsight] BALANCE {}", line);
                    }
                    return 1;
                }))
                .then(literal("reload").requires(op).executes(c -> {
                    String err = Ironsight.loadConfig();
                    List<String> maps = MapRegistry.load(c.getSource().getServer());
                    for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) Net.sendGunTable(p);
                    if (err != null) return fail(c, err);
                    c.getSource().sendSuccess(() -> Component.literal("Reloaded guns, settings and " + MapRegistry.all().size()
                            + " maps; gun table sent to everyone.").withStyle(ChatFormatting.GREEN), true);
                    for (String m : maps) c.getSource().sendSuccess(() -> Component.literal(" " + m).withStyle(ChatFormatting.GRAY), false);
                    return 1;
                }))
                .then(literal("status").requires(op).executes(c -> {
                    if (MatchManager.matches().isEmpty()) c.getSource().sendSuccess(() -> Component.literal("No matches running."), false);
                    for (Match m : MatchManager.matches()) c.getSource().sendSuccess(() -> Component.literal(m.describe()), false);
                    return 1;
                }))
                .then(literal("forcestart").requires(op)
                        .then(argument("mode", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("duel", "team2", "team4", "ffa"), b))
                                .executes(Commands::forceStart)))
                .then(literal("nextmap").requires(op)
                        .then(argument("mode", StringArgumentType.word())
                                .then(argument("map", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(MapRegistry.all().stream().map(m -> m.id), b))
                                        .executes(c -> {
                                            Mode mode = Mode.byId(StringArgumentType.getString(c, "mode"));
                                            String map = StringArgumentType.getString(c, "map");
                                            if (mode == null || !MatchManager.setNextMap(mode, map)) return fail(c, "Unknown mode or map");
                                            c.getSource().sendSuccess(() -> Component.literal("Next " + mode.display + " map: " + map), true);
                                            return 1;
                                        }))))
                .then(literal("give").requires(op)
                        .then(argument("gun", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(GunConfig.IDS, b))
                                .executes(c -> {
                                    String gun = StringArgumentType.getString(c, "gun");
                                    if (GunConfig.get(gun) == null) return fail(c, "Unknown gun");
                                    c.getSource().getPlayerOrException().getInventory().add(Guns.create(gun));
                                    return 1;
                                })))
                .then(literal("map")
                        .then(literal("list").executes(c -> {
                            for (ArenaMap m : MapRegistry.all()) {
                                c.getSource().sendSuccess(() -> Component.literal(m.id + " - " + m.name + " " + m.sizeX + "x" + m.sizeY + "x"
                                        + m.sizeZ + " (A " + m.teamA.size() + ", B " + m.teamB.size() + ", FFA " + m.ffa.size() + ")"), false);
                            }
                            return 1;
                        }))
                        .then(literal("save").requires(op)
                                .then(argument("id", StringArgumentType.word())
                                        .then(argument("from", BlockPosArgument.blockPos())
                                                .then(argument("to", BlockPosArgument.blockPos())
                                                        .executes(Commands::saveMap)))))
                        .then(literal("spawn").requires(op)
                                .then(argument("id", StringArgumentType.word())
                                        .then(argument("kind", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("teamA", "teamB", "ffa", "spectator"), b))
                                                .executes(Commands::addSpawn))))));
    }

    private static int fail(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendFailure(Component.literal(msg));
        return 0;
    }

    private static int forceStart(CommandContext<CommandSourceStack> c) {
        Mode mode = Mode.byId(StringArgumentType.getString(c, "mode"));
        if (mode == null) return fail(c, "Modes: duel, team2, team4, ffa");
        List<UUID> ids = new ArrayList<>();
        for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
            if (mode.equals(MatchManager.queuedFor(p))) ids.add(p.getUUID());
        }
        if (mode.team()) {
            ids = ids.subList(0, Math.min(ids.size(), mode.perTeam * 2));
            if (ids.size() < 2) return fail(c, "Need at least 2 queued players");
        } else if (ids.size() < 2) {
            return fail(c, "Need at least 2 queued players");
        }
        Match m = MatchManager.startMatch(mode, ids);
        if (m == null) return fail(c, "Could not start (no map or no free arena slot)");
        c.getSource().sendSuccess(() -> Component.literal("Started " + m.describe()), true);
        return 1;
    }

    /**
     * /gun map save &lt;id&gt; &lt;from&gt; &lt;to&gt;: save a built region (any size) as a map. Writes
     * config/ironsight/maps/&lt;id&gt;.nbt and a .json skeleton; add spawns with /gun map spawn,
     * then /gun reload.
     */
    private static int saveMap(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String id = StringArgumentType.getString(c, "id").toLowerCase();
        if (!id.matches("[a-z0-9_]+")) return fail(c, "Map ids are lowercase letters, digits and _");
        BlockPos a = BlockPosArgument.getLoadedBlockPos(c, "from");
        BlockPos b = BlockPosArgument.getLoadedBlockPos(c, "to");
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        Vec3i size = new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
        StructureTemplate t = new StructureTemplate();
        // null ignore-list: air is saved too, so placing the map later also clears the space.
        t.fillFromWorld(c.getSource().getLevel(), min, size, false, List.of());
        try {
            Path dir = MapRegistry.dir();
            Files.createDirectories(dir);
            NbtIo.writeCompressed(t.save(new CompoundTag()), dir.resolve(id + ".nbt"));
            Path jsonFile = dir.resolve(id + ".json");
            JsonObject json = Files.exists(jsonFile) ? JsonParser.parseString(Files.readString(jsonFile)).getAsJsonObject() : new JsonObject();
            json.addProperty("id", id);
            if (!json.has("name")) json.addProperty("name", id.substring(0, 1).toUpperCase() + id.substring(1));
            JsonArray s = new JsonArray();
            s.add(size.getX());
            s.add(size.getY());
            s.add(size.getZ());
            json.add("size", s);
            JsonArray o = new JsonArray();
            o.add(min.getX());
            o.add(min.getY());
            o.add(min.getZ());
            json.add("authorOrigin", o);
            for (String k : new String[]{"teamA", "teamB", "ffa"}) if (!json.has(k)) json.add(k, new JsonArray());
            if (!json.has("killY")) json.addProperty("killY", -1);
            Files.writeString(jsonFile, GunConfig.GSON.toJson(json));
        } catch (Exception e) {
            return fail(c, "Save failed: " + e.getMessage());
        }
        c.getSource().sendSuccess(() -> Component.literal("Saved map " + id + " (" + size.getX() + "x" + size.getY() + "x" + size.getZ()
                + "). Stand on each spawn and run /gun map spawn " + id + " teamA|teamB|ffa|spectator, then /gun reload.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int addSpawn(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String id = StringArgumentType.getString(c, "id");
        String kind = StringArgumentType.getString(c, "kind");
        if (!List.of("teamA", "teamB", "ffa", "spectator").contains(kind)) return fail(c, "kind: teamA, teamB, ffa or spectator");
        ServerPlayer p = c.getSource().getPlayerOrException();
        Path jsonFile = MapRegistry.dir().resolve(id + ".json");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(jsonFile)).getAsJsonObject();
            JsonArray o = json.getAsJsonArray("authorOrigin");
            if (o == null) return fail(c, "This map has no authorOrigin - it was not saved with /gun map save");
            JsonArray spawn = new JsonArray();
            spawn.add(p.getBlockX() - o.get(0).getAsInt());
            spawn.add(p.getBlockY() - o.get(1).getAsInt());
            spawn.add(p.getBlockZ() - o.get(2).getAsInt());
            spawn.add(Math.round(p.getYRot()));
            if (kind.equals("spectator")) json.add("spectator", spawn);
            else json.getAsJsonArray(kind).add(spawn);
            Files.writeString(jsonFile, GunConfig.GSON.toJson(json));
            int n = kind.equals("spectator") ? 1 : json.getAsJsonArray(kind).size();
            c.getSource().sendSuccess(() -> Component.literal("Added " + kind + " spawn #" + n + " " + spawn + " to " + id), false);
            return 1;
        } catch (Exception e) {
            return fail(c, "Could not edit " + jsonFile.getFileName() + ": " + e.getMessage());
        }
    }
}
