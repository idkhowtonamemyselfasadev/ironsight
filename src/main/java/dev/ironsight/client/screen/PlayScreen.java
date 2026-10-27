package dev.ironsight.client.screen;

import dev.ironsight.net.C2S;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** /play: pick a mode to queue for. Data: "duel:1/2,team2:0/4,team4:0/8,ffa:3/12;queued=ffa". */
@Environment(EnvType.CLIENT)
public final class PlayScreen extends MenuScreen {

    private static final String[][] MODES = {
            {"duel", "1v1 Duel", "Rounds, one life, first to 7"},
            {"team2", "2v2", "Rounds, one life, first to 7"},
            {"team4", "4v4", "Rounds, one life, first to 7"},
            {"ffa", "Free For All", "2-12 players, 3 lives, random guns"},
    };

    private final Map<String, String> counts = new LinkedHashMap<>();
    private String queued = "";

    public PlayScreen(String data) {
        super("PLAY", 0);
        String[] halves = data.split(";");
        for (String part : halves[0].split(",")) {
            String[] kv = part.split(":");
            if (kv.length == 2) counts.put(kv[0], kv[1]);
        }
        for (int i = 1; i < halves.length; i++) {
            if (halves[i].startsWith("queued=")) queued = halves[i].substring(7);
        }
    }

    @Override
    protected void init() {
        int bw = 220, bh = 24, x = (width - bw) / 2, y = height / 2 - 62;
        for (String[] m : MODES) {
            String label = m[1] + "   " + counts.getOrDefault(m[0], "") + (m[0].equals(queued) ? "   ✔ queued" : "");
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                ClientPlayNetworking.send(new C2S.Menu(C2S.Menu.QUEUE, m[0]));
                onClose();
            }).bounds(x, y, bw, bh).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(m[2]))).build());
            y += bh + 6;
        }
        if (!queued.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("Leave queue"), b -> {
                ClientPlayNetworking.send(new C2S.Menu(C2S.Menu.QUEUE, "leave"));
                onClose();
            }).bounds(x + 40, y + 6, bw - 80, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(font, "Pick a mode - the match starts when the queue is full", width / 2, height / 2 - 80, 0xFFAAB2BD);
    }
}
