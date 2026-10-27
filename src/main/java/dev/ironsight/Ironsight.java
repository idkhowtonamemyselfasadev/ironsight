package dev.ironsight;

import dev.ironsight.config.GunConfig;
import dev.ironsight.config.Settings;
import dev.ironsight.guns.Guns;
import dev.ironsight.maps.Arenas;
import dev.ironsight.maps.MapRegistry;
import dev.ironsight.modes.Lobby;
import dev.ironsight.modes.Match;
import dev.ironsight.modes.MatchManager;
import dev.ironsight.modes.MatchPlayer;
import dev.ironsight.modes.Mode;
import dev.ironsight.net.Net;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/** Server/common entry point: config, networking, rules enforcement and the tick. */
public class Ironsight implements ModInitializer {

    public static final String MOD_ID = "ironsight";
    public static final Logger LOG = LoggerFactory.getLogger("Ironsight");

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
    }

    /** Our sound events are only declared in sounds.json (client side); the server refers to them by id. */
    public static SoundEvent sound(String name) {
        return SoundEvent.createVariableRangeEvent(id(name));
    }

    /** A sound only this player hears, at their own position. */
    public static void playTo(ServerPlayer p, String name, float volume, float pitch) {
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                net.minecraft.core.Holder.direct(sound(name)), net.minecraft.sounds.SoundSource.MASTER,
                p.getX(), p.getEyeY(), p.getZ(), volume, pitch, p.getRandom().nextLong()));
    }

    @Override
    public void onInitialize() {
        Net.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> Commands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> loadConfig());
        ServerLifecycleEvents.SERVER_STARTED.register(this::started);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MatchManager.stopAll());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            MatchManager.tickServer(server);
            Lobby.tick(server, MatchManager.tick());
            restoreItems(server);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer p = handler.getPlayer();
            Net.sendGunTable(p);
            // Reconnects always land in the lobby; a match never waits for anyone.
            Lobby.send(p);
            p.sendSystemMessage(Component.literal("Ironsight " + version() + " - right click the compass, talk to an NPC or type /play.")
                    .withStyle(ChatFormatting.GOLD));
        });
        // The client's play-phase channel list can arrive after JOIN; send the table as
        // soon as it says it can take it (a second copy is harmless).
        net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents.REGISTER.register((handler, sender, server, ids) -> {
            if (ids.contains(dev.ironsight.net.S2C.GunTable.TYPE.id())) server.execute(() -> Net.sendGunTable(handler.getPlayer()));
        });
        // Fabric fires this on a netty thread for timeouts; match state is only ever touched on the server thread.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer p = handler.getPlayer();
            if (server.isSameThread()) MatchManager.disconnected(p);
            else server.execute(() -> MatchManager.disconnected(p));
        });

        registerRules();
        LOG.info("Ironsight {} initialised", version());
    }

    public static String loadConfig() {
        String a = GunConfig.load(configDir());
        String b = Settings.load(configDir());
        if (a != null) LOG.error(a);
        if (b != null) LOG.error(b);
        return a != null ? a : b;
    }

    private void started(MinecraftServer server) {
        MatchManager.init(server);
        for (String line : MapRegistry.load(server)) LOG.info("[Ironsight] {}", line);
        if (Arenas.level(server) == null) LOG.error("[Ironsight] arena dimension missing - the mod's data pack did not load");
        if (Settings.get().manageGamerules) applyGamerules(server);
        Lobby.init(server);
    }

    private static void applyGamerules(MinecraftServer server) {
        ServerLevel ow = server.overworld();
        GameRules rules = ow.getGameRules();
        rules.set(GameRules.PVP, true, server);
        rules.set(GameRules.FALL_DAMAGE, true, server);
        rules.set(GameRules.NATURAL_HEALTH_REGENERATION, false, server);
        rules.set(GameRules.SHOW_DEATH_MESSAGES, false, server);
        rules.set(GameRules.IMMEDIATE_RESPAWN, true, server);
        rules.set(GameRules.KEEP_INVENTORY, true, server);
        rules.set(GameRules.SPAWN_MOBS, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(GameRules.ADVANCE_TIME, false, server);
        ow.setDayTime(6000);
        ow.setWeatherParameters(1_000_000, 0, false, false);
    }

    // ------------------------------------------------------------------ rules

    private void registerRules() {
        // Damage: in the lobby nothing hurts. In a match, vanilla damage is limited to
        // falling and the void; guns bypass this entirely (they set health directly).
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer p)) return !entity.getTags().contains(Lobby.NPC_TAG);
            MatchPlayer mp = MatchManager.participant(p);
            if (mp == null) return p.isCreative() && source.is(DamageTypes.FELL_OUT_OF_WORLD);
            if (!mp.alive) return false;
            return source.is(DamageTypes.FALL) || source.is(DamageTypes.FELL_OUT_OF_WORLD) || source.is(DamageTypes.GENERIC_KILL);
        });

        // A vanilla death in a match (fall damage) becomes a match death: no death screen, no drops.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer p)) return true;
            Match m = MatchManager.matchOf(p);
            MatchPlayer mp = m == null ? null : m.get(p);
            if (mp != null) {
                p.setHealth(p.getMaxHealth());
                m.environmentDeath(mp);
                return false;
            }
            p.setHealth(p.getMaxHealth());
            Lobby.send(p);
            return false;
        });

        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) ->
                player.isCreative() && level.dimension() != Arenas.DIMENSION && MatchManager.matchOf((ServerPlayer) player) == null);

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || player.isCreative()) return InteractionResult.PASS;
            return InteractionResult.FAIL;
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            if (Lobby.isMenuItem(player.getItemInHand(hand))) {
                MatchManager.openPlayMenu(p);
                return InteractionResult.SUCCESS;
            }
            if (Guns.isGun(player.getItemInHand(hand))) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            Mode mode = Lobby.npcMode(entity);
            if (mode == null) return InteractionResult.PASS;
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) MatchManager.join(p, mode);
            return InteractionResult.SUCCESS;
        });

        // No melee anywhere: guns are the only weapon, and NPCs can't be punched.
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                player.isCreative() && Lobby.npcMode(entity) == null ? InteractionResult.PASS : InteractionResult.FAIL);

        // Nothing lies around in an arena.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ItemEntity && level.dimension() == Arenas.DIMENSION) entity.discard();
        });
    }

    /** Once a second, put back anything a player managed to drop or move away. */
    private static void restoreItems(MinecraftServer server) {
        if (MatchManager.tick() % 20 != 7) return;
        for (Match m : MatchManager.matches()) {
            for (MatchPlayer mp : m.players.values()) {
                if (!mp.connected || !mp.alive) continue;
                for (int i = 0; i < 2; i++) {
                    if (mp.guns[i] == null) continue;
                    String want = mp.guns[i].gun;
                    if (!want.equals(Guns.idOf(mp.player.getInventory().getItem(i)))) {
                        mp.player.getInventory().setItem(i, Guns.create(want));
                    }
                }
            }
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (MatchManager.matchOf(p) == null && !p.isCreative() && Lobby.inLobbyArea(p)
                    && !Lobby.isMenuItem(p.getInventory().getItem(4))) {
                p.getInventory().setItem(4, Lobby.menuItem());
            }
        }
    }

    static {
        // Referenced so the registry class is initialised before any gun stack is built.
        BuiltInRegistries.ITEM.size();
    }
}
