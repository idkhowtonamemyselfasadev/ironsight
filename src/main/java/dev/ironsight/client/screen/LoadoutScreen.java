package dev.ironsight.client.screen;

import dev.ironsight.config.GunConfig;
import dev.ironsight.config.GunStats;
import dev.ironsight.guns.Guns;
import dev.ironsight.net.C2S;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Buy-time loadout: one primary, one secondary. Every click is sent at once, so a pick
 * counts even if the round starts before you press Ready.
 */
@Environment(EnvType.CLIENT)
public final class LoadoutScreen extends MenuScreen {

    private static final int BW = 150, BH = 22;

    private String primary, secondary;
    private String hovered;
    private final List<String> primaries = GunConfig.ofSlot(true);
    private final List<String> secondaries = GunConfig.ofSlot(false);
    private final List<Entry> entries = new ArrayList<>();

    private record Entry(String gun, Button button, ItemStack icon) { }

    public LoadoutScreen(String data, int seconds) {
        super("LOADOUT", seconds);
        String[] f = data.split(",");
        primary = f.length > 0 && GunConfig.get(f[0]) != null ? f[0] : primaries.get(0);
        secondary = f.length > 1 && GunConfig.get(f[1]) != null ? f[1] : secondaries.get(0);
    }

    @Override
    protected void init() {
        entries.clear();
        int left = width / 2 - BW - 12, right = width / 2 + 12, top = 50;
        int y = top;
        for (String id : primaries) {
            add(id, left, y, true);
            y += BH + 3;
        }
        y = top;
        for (String id : secondaries) {
            add(id, right, y, false);
            y += BH + 3;
        }
        addRenderableWidget(Button.builder(Component.literal("READY"), b -> {
            send();
            onClose();
        }).bounds(right, top + 3 * (BH + 3) + 10, BW, 24).build());
    }

    private void add(String id, int x, int y, boolean isPrimary) {
        Button b = Button.builder(Component.literal("      " + GunConfig.get(id).name), btn -> {
            if (isPrimary) primary = id;
            else secondary = id;
            send();
        }).bounds(x, y, BW, BH).build();
        addRenderableWidget(b);
        entries.add(new Entry(id, b, Guns.create(id)));
    }

    private void send() {
        ClientPlayNetworking.send(new C2S.Menu(C2S.Menu.LOADOUT, primary + "," + secondary));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawString(font, "PRIMARY", width / 2 - BW - 12, 40, 0xFFAAB2BD);
        g.drawString(font, "SECONDARY", width / 2 + 12, 40, 0xFFAAB2BD);
        hovered = null;
        for (Entry e : entries) {
            Button b = e.button();
            boolean picked = e.gun().equals(primary) || e.gun().equals(secondary);
            if (picked) {
                g.fill(b.getX() - 2, b.getY() - 1, b.getX(), b.getY() + b.getHeight() + 1, 0xFFFFD24A);
            }
            g.renderItem(e.icon(), b.getX() + 4, b.getY() + 3);
            if (b.isHovered()) hovered = e.gun();
        }
        String show = hovered != null ? hovered : primary;
        GunStats s = GunConfig.get(show);
        if (s != null) {
            int x = width / 2 + 12, y = height - 60;
            g.drawString(font, s.name, x, y, 0xFFFFFFFF);
            double rpm = s.burst > 1 ? 1200.0 * s.burst / s.ticksToShot(s.burst) : 1200.0 / s.fireTicks;
            String line1 = String.format(Locale.ROOT, "DMG %.1f%s  x%.1f head  %.0f RPM%s", s.damage,
                    s.pellets > 1 ? "x" + s.pellets : "", s.headMult, rpm, s.burst > 1 ? " (burst)" : "");
            String line2 = String.format(Locale.ROOT, "MAG %d/%d  RANGE %.0f  MOVE %.0f%%", s.mag, s.reserve, s.falloffEnd,
                    s.moveMult * 100);
            g.drawString(font, line1, x, y + 12, 0xFFAAB2BD);
            g.drawString(font, line2, x, y + 24, 0xFFAAB2BD);
        }
    }
}
