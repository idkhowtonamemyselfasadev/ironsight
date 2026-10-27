package dev.ironsight.modes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ironsight.Ironsight;
import dev.ironsight.config.GunConfig;
import dev.ironsight.config.Settings;
import dev.ironsight.maps.ArenaMap;
import dev.ironsight.maps.Arenas;
import dev.ironsight.maps.MapRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * The lobby: where everybody spawns, queues and returns after a match. It is the
 * overworld, never an arena, so a match can never damage it. Players here are in
 * adventure mode, take no damage and hold only the Play compass.
 */
public final class Lobby {

    public static final String NPC_TAG = "ironsight_npc";
    private static final String MENU_ITEM_KEY = "ironsight_menu";

    private static double spawnX, spawnY, spawnZ;
    private static float spawnYaw;
    private static ServerLevel level;
    private static AABB area;

    private Lobby() {
    }

    public static void init(MinecraftServer server) {
        level = server.overworld();
        Settings cfg = Settings.get();
        ArenaMap lobby = MapRegistry.lobby();
        JsonObject json = MapRegistry.lobbyJson();
        BlockPos origin = new BlockPos(cfg.lobbyX, cfg.lobbyY, cfg.lobbyZ);
        if (lobby != null && cfg.buildLobby) {
            Path marker = FabricLoader.getInstance().getConfigDir().resolve("ironsight").resolve("lobby-built.txt");
            if (!Files.exists(marker)) {
                long ms = Arenas.place(level, lobby, origin);
                Arenas.forceChunks(level, lobby, origin, false);
                try {
                    Files.writeString(marker, "Lobby placed at " + origin.toShortString() + ". Delete this file to rebuild it on next start.\n");
                } catch (Exception e) {
                    Ironsight.LOG.warn("Could not write {}", marker, e);
                }
                Ironsight.LOG.info("[Ironsight] lobby built at {} in {} ms", origin.toShortString(), ms);
            }
            JsonArray s = json.getAsJsonArray("spawn");
            spawnX = origin.getX() + s.get(0).getAsDouble() + 0.5;
            spawnY = origin.getY() + s.get(1).getAsDouble();
            spawnZ = origin.getZ() + s.get(2).getAsDouble() + 0.5;
            spawnYaw = s.size() > 3 ? s.get(3).getAsFloat() : 0f;
            area = new AABB(origin.getX() - 4, origin.getY() - 40, origin.getZ() - 4,
                    origin.getX() + lobby.sizeX + 4, origin.getY() + lobby.sizeY + 20, origin.getZ() + lobby.sizeZ + 4);
            // Keep the lobby loaded so the NPCs are always there and always found.
            Arenas.forceChunks(level, lobby, origin, true);
            spawnNpcs(origin, json.getAsJsonObject("npcs"));
            level.setRespawnData(net.minecraft.world.level.storage.LevelData.RespawnData.of(level.dimension(),
                    BlockPos.containing(spawnX, spawnY, spawnZ), spawnYaw, 0f));
        } else {
            BlockPos sp = level.getRespawnData().pos();
            spawnX = sp.getX() + 0.5;
            spawnY = sp.getY();
            spawnZ = sp.getZ() + 0.5;
            area = null;
        }
    }

    private static void spawnNpcs(BlockPos origin, JsonObject npcs) {
        if (npcs == null) return;
        for (Entity e : level.getEntities((Entity) null, area, e -> e.getTags().contains(NPC_TAG))) e.discard();
        for (Mode mode : Mode.values()) {
            if (!npcs.has(mode.id)) continue;
            JsonArray a = npcs.getAsJsonArray(mode.id);
            Mannequin npc = EntityType.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
            if (npc == null) continue;
            float yaw = a.size() > 3 ? a.get(3).getAsFloat() : 0f;
            npc.snapTo(origin.getX() + a.get(0).getAsDouble() + 0.5, origin.getY() + a.get(1).getAsDouble(),
                    origin.getZ() + a.get(2).getAsDouble() + 0.5, yaw, 0f);
            npc.setYHeadRot(yaw);
            npc.setYBodyRot(yaw);
            npc.setCustomName(Component.literal(mode.display + (mode.team() ? "" : " (2-12)")).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            npc.setCustomNameVisible(true);
            npc.setDescription(Component.literal("Click to queue").withStyle(ChatFormatting.GRAY));
            npc.setInvulnerable(true);
            npc.setNoGravity(true);
            npc.setSilent(true);
            npc.setImmovable(true);
            npc.addTag(NPC_TAG);
            npc.addTag(NPC_TAG + ":" + mode.id);
            level.addFreshEntity(npc);
        }
    }

    public static Mode npcMode(Entity e) {
        if (!e.getTags().contains(NPC_TAG)) return null;
        for (String tag : e.getTags()) {
            if (tag.startsWith(NPC_TAG + ":")) return Mode.byId(tag.substring(NPC_TAG.length() + 1));
        }
        return null;
    }

    /** Back to the lobby: reset everything a match may have changed. */
    public static void send(ServerPlayer p) {
        boolean keepCreative = p.gameMode() == GameType.CREATIVE && p.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        p.setCamera(p);
        if (!keepCreative) p.setGameMode(GameType.ADVENTURE);
        clearModifiers(p);
        clearInventory(p);
        p.getInventory().setItem(4, menuItem());
        p.getInventory().setSelectedSlot(4);
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(4));
        p.removeAllEffects();
        p.setRemainingFireTicks(0);
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5f);
        p.fallDistance = 0;
        p.teleportTo(level, spawnX, spawnY, spawnZ, Set.of(), spawnYaw, 0f, false);
    }

    public static void clearModifiers(ServerPlayer p) {
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance jump = p.getAttribute(Attributes.JUMP_STRENGTH);
        if (speed != null) {
            speed.removeModifier(Match.SPEED_MOD);
            speed.removeModifier(Match.FREEZE_MOD);
        }
        if (jump != null) jump.removeModifier(Match.FREEZE_MOD);
    }

    public static void clearInventory(ServerPlayer p) {
        p.getInventory().clearContent();
        p.containerMenu.setCarried(ItemStack.EMPTY);
        p.inventoryMenu.broadcastChanges();
    }

    public static ItemStack menuItem() {
        ItemStack stack = new ItemStack(Items.COMPASS);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Play  (right click)")
                .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putBoolean(MENU_ITEM_KEY, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        return stack;
    }

    public static boolean isMenuItem(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(MENU_ITEM_KEY, false);
    }

    /** Lobby safety net: anyone who falls off is put back. */
    public static void tick(MinecraftServer server, long tick) {
        if (tick % 10 != 0 || area == null) return;
        for (ServerPlayer p : level.players()) {
            if (MatchManager.matchOf(p) == null && p.getY() < area.minY + 20 && !p.isCreative() && !p.isSpectator()) {
                send(p);
            }
        }
    }

    public static boolean inLobbyArea(ServerPlayer p) {
        return area != null && p.level() == level && area.contains(p.position());
    }

    static {
        // Make sure GunConfig is initialised before any loadout logic runs.
        GunConfig.IDS.size();
    }
}
