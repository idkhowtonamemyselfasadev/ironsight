package dev.ironsight.client;

import dev.ironsight.Ironsight;
import dev.ironsight.client.render.Effects;
import dev.ironsight.client.screen.LoadoutScreen;
import dev.ironsight.client.screen.PlayScreen;
import dev.ironsight.client.screen.TeamScreen;
import dev.ironsight.client.screen.VoteScreen;
import dev.ironsight.config.GunConfig;
import dev.ironsight.net.S2C;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client receivers. Fabric runs play receivers on the client thread. */
@Environment(EnvType.CLIENT)
public final class ClientNet {

    private ClientNet() {
    }

    public static void register() {
        ClientConfigurationNetworking.registerGlobalReceiver(S2C.Hello.TYPE, (payload, ctx) -> {
            String mine = Ironsight.version();
            if (!mine.equals(payload.version())) {
                ctx.responseSender().disconnect(Component.literal("Ironsight version mismatch: the server runs "
                        + payload.version() + ", you have " + mine + ". Install Ironsight " + payload.version() + "."));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.GunTable.TYPE, (p, ctx) -> {
            String err = GunConfig.apply(p.json());
            if (err != null) Ironsight.LOG.error("Server gun table rejected: {}", err);
        });
        ClientPlayNetworking.registerGlobalReceiver(S2C.Hud.TYPE, (p, ctx) -> ClientState.applyHud(p));
        ClientPlayNetworking.registerGlobalReceiver(S2C.Ammo.TYPE, (p, ctx) -> ClientState.applyAmmo(p));
        ClientPlayNetworking.registerGlobalReceiver(S2C.Board.TYPE, (p, ctx) -> ClientState.applyBoard(p.rows()));
        ClientPlayNetworking.registerGlobalReceiver(S2C.Kill.TYPE, (p, ctx) -> {
            Minecraft mc = ctx.client();
            String me = mc.player != null ? mc.player.getGameProfile().name() : "";
            ClientState.addKill(p, me);
        });
        ClientPlayNetworking.registerGlobalReceiver(S2C.Hit.TYPE, (p, ctx) -> {
            ClientState.applyHit(p);
            if (ClientConfig.get().hitSounds && ctx.client().player != null) {
                boolean kill = (p.flags() & 2) != 0, head = (p.flags() & 1) != 0;
                Effects.playLocal(ctx.client().player, kill ? "kill" : head ? "headshot" : "hit", kill ? 1f : 0.8f);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(S2C.Shot.TYPE, (p, ctx) -> {
            if (ctx.client().level != null) Effects.remoteShot(ctx.client().level, p.gun(), p.sx(), p.sy(), p.sz(), p.ends());
        });
        ClientPlayNetworking.registerGlobalReceiver(S2C.Glint.TYPE, (p, ctx) -> {
            if (p.on()) ClientState.glints.add(p.entity());
            else ClientState.glints.remove(p.entity());
        });
        ClientPlayNetworking.registerGlobalReceiver(S2C.Menu.TYPE, (p, ctx) -> openMenu(ctx.client(), p));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientState.reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.reset());
    }

    private static void openMenu(Minecraft mc, S2C.Menu m) {
        switch (m.kind()) {
            case S2C.Menu.CLOSE -> {
                if (mc.screen instanceof TeamScreen || mc.screen instanceof LoadoutScreen
                        || mc.screen instanceof VoteScreen || mc.screen instanceof PlayScreen) {
                    mc.setScreen(null);
                }
            }
            case S2C.Menu.PLAY -> mc.setScreen(new PlayScreen(m.data()));
            case S2C.Menu.TEAM -> mc.setScreen(new TeamScreen(m.data(), m.seconds()));
            case S2C.Menu.LOADOUT -> mc.setScreen(new LoadoutScreen(m.data(), m.seconds()));
            case S2C.Menu.VOTE -> mc.setScreen(new VoteScreen(m.data(), m.seconds()));
            default -> { }
        }
    }
}
