package dev.ironsight.net;

import dev.ironsight.Ironsight;
import dev.ironsight.config.GunConfig;
import dev.ironsight.guns.ServerGuns;
import dev.ironsight.modes.Match;
import dev.ironsight.modes.MatchManager;
import dev.ironsight.modes.MatchPlayer;
import dev.ironsight.modes.Mode;
import dev.ironsight.modes.TeamMatch;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Channel registration, the "install the mod" gate, and the server side of every
 * client message. Handlers hop onto the server thread before touching match state.
 */
public final class Net {

    private Net() {
    }

    public static void register() {
        PayloadTypeRegistry.configurationS2C().register(S2C.Hello.TYPE, S2C.Hello.CODEC);

        PayloadTypeRegistry.playS2C().register(S2C.GunTable.TYPE, S2C.GunTable.CODEC);
        PayloadTypeRegistry.playS2C().register(S2C.Hud.TYPE, S2C.Hud.CODEC);
        PayloadTypeRegistry.playS2C().register(S2C.Ammo.TYPE, S2C.Ammo.CODEC);
        PayloadTypeRegistry.playS2C().register(S2C.Shot.TYPE, S2C.Shot.CODEC);
        PayloadTypeRegistry.playS2C().register(S2C.Hit.TYPE, S2C.Hit.CODEC);
        PayloadTypeRegistry.playS2C().register(S2C.Kill.TYPE, S2C.Kill.CODEC);
        PayloadTypeRegistry.playS2C().register(S2C.Menu.TYPE, S2C.Menu.CODEC);
        PayloadTypeRegistry.playS2C().register(S2C.Board.TYPE, S2C.Board.CODEC);
        PayloadTypeRegistry.playS2C().register(S2C.Glint.TYPE, S2C.Glint.CODEC);

        PayloadTypeRegistry.playC2S().register(C2S.Shoot.TYPE, C2S.Shoot.CODEC);
        PayloadTypeRegistry.playC2S().register(C2S.Action.TYPE, C2S.Action.CODEC);
        PayloadTypeRegistry.playC2S().register(C2S.Menu.TYPE, C2S.Menu.CODEC);

        // The gate: Fabric knows which channels the client registered by now. A vanilla
        // client, or a Fabric client without Ironsight, cannot receive Hello and is turned
        // away with instructions instead of a generic protocol error.
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            if (ServerConfigurationNetworking.canSend(handler, S2C.Hello.TYPE)) {
                ServerConfigurationNetworking.send(handler, new S2C.Hello(Ironsight.version()));
            } else {
                handler.disconnect(kickMessage());
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(C2S.Shoot.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            MatchPlayer mp = MatchManager.participant(ctx.player());
            if (mp != null) ServerGuns.shoot(MatchManager.matchOf(ctx.player()), mp, payload.yaw(), payload.pitch());
        }));

        ServerPlayNetworking.registerGlobalReceiver(C2S.Action.TYPE, (payload, ctx) -> ctx.server().execute(() ->
                action(ctx.player(), payload.action())));

        ServerPlayNetworking.registerGlobalReceiver(C2S.Menu.TYPE, (payload, ctx) -> ctx.server().execute(() ->
                menu(ctx.player(), payload.kind(), payload.value())));
    }

    public static Component kickMessage() {
        return Component.literal("Ironsight required\n\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("This server runs the Ironsight gun mod.\nInstall ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("Ironsight " + Ironsight.version()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" + Fabric API on Fabric Loader for Minecraft 1.21.11,\nthen join again.")
                        .withStyle(ChatFormatting.WHITE));
    }

    private static void action(ServerPlayer p, byte action) {
        Match m = MatchManager.matchOf(p);
        MatchPlayer mp = m == null ? null : m.get(p);
        switch (action) {
            case C2S.Action.RELOAD -> {
                if (mp != null) ServerGuns.reload(m, mp);
            }
            case C2S.Action.ADS_ON, C2S.Action.ADS_OFF -> {
                if (mp != null) ServerGuns.setAds(m, mp, action == C2S.Action.ADS_ON);
            }
            case C2S.Action.SPECTATE_NEXT -> {
                if (mp != null) m.spectateNext(mp);
            }
            case C2S.Action.OPEN_PLAY -> {
                if (m == null) MatchManager.openPlayMenu(p);
            }
            case C2S.Action.LEAVE -> MatchManager.leave(p);
            default -> { }
        }
    }

    private static void menu(ServerPlayer p, byte kind, String value) {
        Match m = MatchManager.matchOf(p);
        MatchPlayer mp = m == null ? null : m.get(p);
        switch (kind) {
            case C2S.Menu.QUEUE -> {
                Mode mode = Mode.byId(value);
                if (mode != null) MatchManager.join(p, mode);
                else if ("leave".equals(value)) MatchManager.leaveQueue(p, true);
            }
            case C2S.Menu.TEAM -> {
                if (m instanceof TeamMatch tm && mp != null) tm.pickTeam(mp, value);
            }
            case C2S.Menu.LOADOUT -> {
                String[] parts = value.split(",");
                if (m instanceof TeamMatch tm && mp != null && parts.length == 2) tm.pickLoadout(mp, parts[0], parts[1]);
            }
            case C2S.Menu.VOTE -> {
                if (m != null && mp != null) m.vote(mp, value);
            }
            default -> { }
        }
    }

    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.type())) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public static void sendGunTable(ServerPlayer player) {
        send(player, new S2C.GunTable(GunConfig.json()));
    }
}
