package dev.ironsight.client.screen;

import dev.ironsight.net.C2S;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** The MVP picks the next map. Data "id=Name;id=Name". No pick in time = random. */
@Environment(EnvType.CLIENT)
public final class VoteScreen extends MenuScreen {
    private final String[] ids, names;

    public VoteScreen(String data, int seconds) {
        super("YOU'RE THE MVP - PICK THE NEXT MAP", seconds);
        String[] parts = data.isEmpty() ? new String[0] : data.split(";");
        ids = new String[parts.length];
        names = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String[] kv = parts[i].split("=", 2);
            ids[i] = kv[0];
            names[i] = kv.length > 1 ? kv[1] : kv[0];
        }
    }

    @Override
    protected void init() {
        int bw = 200, bh = 24, x = (width - bw) / 2, y = height / 2 - ids.length * (bh + 6) / 2;
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            addRenderableWidget(Button.builder(Component.literal(names[i]), b -> {
                ClientPlayNetworking.send(new C2S.Menu(C2S.Menu.VOTE, id));
                onClose();
            }).bounds(x, y, bw, bh).build());
            y += bh + 6;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(font, "No pick in time = random map", width / 2, height - 30, 0xFFAAB2BD);
    }
}
