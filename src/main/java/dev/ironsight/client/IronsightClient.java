package dev.ironsight.client;

import dev.ironsight.Ironsight;
import dev.ironsight.client.hud.Hud;
import dev.ironsight.client.render.Effects;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Client entry point: input, effects, HUD, menus. A dedicated server never loads this. */
@Environment(EnvType.CLIENT)
public class IronsightClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        GunInput.register();
        ClientNet.register();
        Effects.register();
        Hud.register();
        ClientTickEvents.END_CLIENT_TICK.register(GunInput::tick);
        ClientTickEvents.END_CLIENT_TICK.register(Perf::tick);
        Ironsight.LOG.info("Ironsight client ready");
    }
}
