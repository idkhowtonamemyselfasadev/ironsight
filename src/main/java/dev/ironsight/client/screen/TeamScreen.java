package dev.ironsight.client.screen;

import dev.ironsight.net.C2S;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Pick a side. Data "a,b,max". A full team auto-balances you onto the other one. */
@Environment(EnvType.CLIENT)
public final class TeamScreen extends MenuScreen {
    private final int a, b, max;

    public TeamScreen(String data, int seconds) {
        super("PICK YOUR TEAM", seconds);
        String[] f = data.split(",");
        a = Integer.parseInt(f[0]);
        b = Integer.parseInt(f[1]);
        max = Integer.parseInt(f[2]);
    }

    @Override
    protected void init() {
        int bw = 140, bh = 40, y = height / 2 - 20;
        addRenderableWidget(Button.builder(Component.literal("RED   " + a + "/" + max).withColor(0xFF5555), btn -> pick("A"))
                .bounds(width / 2 - bw - 10, y, bw, bh).build()).active = a < max;
        addRenderableWidget(Button.builder(Component.literal("BLUE   " + b + "/" + max).withColor(0x5599FF), btn -> pick("B"))
                .bounds(width / 2 + 10, y, bw, bh).build()).active = b < max;
        addRenderableWidget(Button.builder(Component.literal("Auto"), btn -> pick(a <= b ? "A" : "B"))
                .bounds(width / 2 - 40, y + bh + 12, 80, 20).build());
    }

    private void pick(String team) {
        ClientPlayNetworking.send(new C2S.Menu(C2S.Menu.TEAM, team));
        onClose();
    }
}
