package dev.ironsight.client.hud;

import dev.ironsight.Ironsight;
import dev.ironsight.client.ClientConfig;
import dev.ironsight.client.ClientState;
import dev.ironsight.client.GunInput;
import dev.ironsight.config.GunConfig;
import dev.ironsight.config.GunStats;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The match HUD: timer and score (top), kill feed (top right), health (bottom left),
 * ammo (bottom right), crosshair with live spread gap, hit markers, damage numbers, scope
 * overlay and the Tab scoreboard. Only fills and text - no textures except the vanilla
 * spyglass scope - and no allocation per frame: every string comes from ClientState's
 * caches, which change only when a packet arrives.
 */
@Environment(EnvType.CLIENT)
public final class Hud implements HudElement {

    private static final Identifier SCOPE = Identifier.withDefaultNamespace("textures/misc/spyglass_scope.png");

    /** Vanilla elements that make no sense over a gunfight. They still draw outside matches. */
    private static final Identifier[] HIDDEN = {
            VanillaHudElements.CROSSHAIR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
            VanillaHudElements.ARMOR_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
            VanillaHudElements.INFO_BAR, VanillaHudElements.SCOREBOARD, VanillaHudElements.PLAYER_LIST,
            VanillaHudElements.MOUNT_HEALTH,
    };

    private static final int PANEL = 0x99101418;
    private static final int RED = 0xFFFF5555, BLUE = 0xFF5599FF, GOLD = 0xFFFFD24A, WHITE = 0xFFFFFFFF, DIM = 0xFFAAB2BD;

    private static final String[] KILL_ICON = {" » ", " ✖ "};
    private static final String HEALTH_LABEL = "HP";
    private static final String RELOADING = "RELOADING";
    private static final String[] HP_TEXT = new String[41];

    static {
        for (int i = 0; i < HP_TEXT.length; i++) HP_TEXT[i] = String.valueOf(i);
    }

    private Hud() {
    }

    public static void register() {
        for (Identifier id : HIDDEN) {
            HudElementRegistry.replaceElement(id, original -> (g, delta) -> {
                if (!ClientState.inMatch()) original.render(g, delta);
            });
        }
        HudElementRegistry.addLast(Ironsight.id("hud"), new Hud());
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        if (!dev.ironsight.client.Perf.ON) {
            draw(g);
            return;
        }
        long t = System.nanoTime();
        draw(g);
        dev.ironsight.client.Perf.hudNanos += System.nanoTime() - t;
        dev.ironsight.client.Perf.frames++;
    }

    private void draw(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        GunInput.frame();
        if (mc.player == null || mc.options.hideGui || !ClientState.inMatch()) return;
        LocalPlayer p = mc.player;
        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        String gun = GunInput.activeGun();
        GunStats stats = gun == null ? null : GunConfig.get(gun);

        boolean scoped = stats != null && stats.scope && GunInput.adsProgress > 0.85f;
        if (scoped) scope(g, w, h);
        else if (!p.isSpectator()) crosshair(g, w, h, stats);

        hitMarker(g, w, h);
        if (ClientConfig.get().damageNumbers) damageNumbers(g, font, w, h);
        top(g, font, w);
        killFeed(g, font, w);
        if (!p.isSpectator()) {
            health(g, font, p, h);
            if (stats != null) ammo(g, font, w, h, stats, GunConfig.index(gun));
        } else {
            g.drawCenteredString(font, "SPECTATING  ·  left click: next player", w / 2, h - 40, DIM);
        }
        if (mc.options.keyPlayerList.isDown() || ClientState.phase() == ClientState.ENDED) board(g, font, w, h);
    }

    // ------------------------------------------------------------------ pieces

    private static void top(GuiGraphics g, Font font, int w) {
        int cx = w / 2;
        g.fill(cx - 70, 4, cx + 70, 30, PANEL);
        g.drawCenteredString(font, ClientState.timer(), cx, 8, ClientState.secondsLeft() <= 10 ? RED : WHITE);
        g.drawCenteredString(font, ClientState.centerSub, cx, 19, DIM);
        if (ClientState.ffa()) {
            g.drawString(font, ClientState.scoreLeft, cx - 66, 8, GOLD);
            g.drawString(font, ClientState.livesText, cx + 66 - font.width(ClientState.livesText), 8, RED);
            g.drawString(font, ClientState.scoreRight, cx + 66 - font.width(ClientState.scoreRight), 19, DIM);
        } else {
            g.fill(cx - 100, 4, cx - 72, 30, 0xCC7A1E1E);
            g.fill(cx + 72, 4, cx + 100, 30, 0xCC1E3F7A);
            g.drawCenteredString(font, ClientState.scoreLeft, cx - 86, 12, WHITE);
            g.drawCenteredString(font, ClientState.scoreRight, cx + 86, 12, WHITE);
            pips(g, cx - 104, 33, ClientState.hud.aliveA(), RED, true);
            pips(g, cx + 104, 33, ClientState.hud.aliveB(), BLUE, false);
            int team = ClientState.hud.team();
            if (team >= 0) {
                int x = team == 0 ? cx - 100 : cx + 72;
                g.fill(x, 30, x + 28, 32, GOLD);
            }
        }
    }

    /** One small square per living player of a team. */
    private static void pips(GuiGraphics g, int x, int y, int n, int colour, boolean leftSide) {
        for (int i = 0; i < n; i++) {
            int px = leftSide ? x + 4 + i * 7 : x - 9 - i * 7;
            g.fill(px, y, px + 5, y + 5, colour);
        }
    }

    private static void killFeed(GuiGraphics g, Font font, int w) {
        long now = ClientState.clientTick;
        int y = 6;
        for (int k = 0; k < ClientState.FEED; k++) {
            int i = Math.floorMod(ClientState.feedHead - k, ClientState.FEED);
            long age = now - ClientState.FEED_AT[i];
            if (ClientState.FEED_VICTIM[i] == null || age > 120) continue;
            String killer = ClientState.FEED_KILLER[i], victim = ClientState.FEED_VICTIM[i], gun = ClientState.FEED_GUN[i];
            String icon = KILL_ICON[ClientState.FEED_HEAD[i] ? 1 : 0];
            int width = font.width(killer) + font.width(icon) + font.width(victim) + font.width(gun) + 16;
            int x = w - 6 - width;
            g.fill(x, y, w - 6, y + 12, ClientState.FEED_MINE[i] ? 0xB0503A10 : PANEL);
            int cx = x + 4;
            g.drawString(font, killer, cx, y + 2, ClientState.FEED_KCOL[i]);
            cx += font.width(killer);
            g.drawString(font, icon, cx, y + 2, ClientState.FEED_HEAD[i] ? GOLD : DIM);
            cx += font.width(icon);
            g.drawString(font, victim, cx, y + 2, ClientState.FEED_VCOL[i]);
            cx += font.width(victim) + 8;
            g.drawString(font, gun, cx, y + 2, DIM);
            y += 14;
        }
    }

    private static void health(GuiGraphics g, Font font, LocalPlayer p, int h) {
        int hp = Math.max(0, Math.min(40, (int) Math.ceil(p.getHealth())));
        int x = 8, y = h - 26;
        g.fill(x, y, x + 104, y + 18, PANEL);
        g.drawString(font, HEALTH_LABEL, x + 4, y + 5, DIM);
        int barW = (int) (70 * Math.min(1f, p.getHealth() / p.getMaxHealth()));
        g.fill(x + 20, y + 5, x + 90, y + 13, 0xFF2A2F36);
        g.fill(x + 20, y + 5, x + 20 + barW, y + 13, hp <= 6 ? RED : 0xFF58D68D);
        g.drawString(font, HP_TEXT[hp], x + 93, y + 5, WHITE);
    }

    private static void ammo(GuiGraphics g, Font font, int w, int h, GunStats s, int idx) {
        int x = w - 120, y = h - 40;
        g.fill(x, y, w - 8, h - 8, PANEL);
        g.drawString(font, s.name, x + 6, y + 5, DIM);
        String text = ClientState.ammoText(idx);
        boolean low = ClientState.AMMO_MAG[idx] <= Math.max(1, s.mag / 5);
        g.pose().pushMatrix();
        g.pose().translate(x + 6, y + 16);
        g.pose().scale(1.5f, 1.5f);
        g.drawString(font, text, 0, 0, low ? RED : WHITE);
        g.pose().popMatrix();
        if (ClientState.reloading(idx)) {
            int total = Math.max(1, ClientState.RELOAD_TOTAL[idx]);
            float left = (ClientState.RELOAD_END[idx] - ClientState.clientTick) / (float) total;
            int bw = (int) ((w - 8 - x - 12) * (1f - Math.max(0, Math.min(1, left))));
            g.fill(x + 6, h - 12, x + 6 + bw, h - 10, GOLD);
            g.drawCenteredString(font, RELOADING, w / 2, h / 2 + 20, GOLD);
        }
    }

    private static void crosshair(GuiGraphics g, int w, int h, GunStats s) {
        int cx = w / 2, cy = h / 2;
        int gap = 2;
        if (s != null) {
            // The gap tracks the gun's spread (hip -> ADS), so it tells the truth about accuracy.
            float spread = s.hipSpread + (s.adsSpread - s.hipSpread) * GunInput.adsProgress;
            gap = 2 + Math.round(spread * 3.5f);
        }
        int len = 4, col = 0xE6FFFFFF;
        g.fill(cx - gap - len, cy, cx - gap, cy + 1, col);
        g.fill(cx + gap + 1, cy, cx + gap + 1 + len, cy + 1, col);
        g.fill(cx, cy - gap - len, cx + 1, cy - gap, col);
        g.fill(cx, cy + gap + 1, cx + 1, cy + gap + 1 + len, col);
        g.fill(cx, cy, cx + 1, cy + 1, col);
    }

    private static void hitMarker(GuiGraphics g, int w, int h) {
        long age = ClientState.clientTick - ClientState.hitAt;
        if (age > 6) return;
        int alpha = (int) (255 * (1f - age / 7f));
        int rgb = ClientState.hitKill ? 0xFF3030 : ClientState.hitHead ? 0xFFD24A : 0xFFFFFF;
        int col = (alpha << 24) | rgb;
        int size = ClientState.hitHead || ClientState.hitKill ? 7 : 5;
        int cx = w / 2, cy = h / 2;
        g.pose().pushMatrix();
        g.pose().translate(cx + 0.5f, cy + 0.5f);
        g.pose().rotate((float) Math.toRadians(45));
        g.fill(-size - 4, 0, -4, 1, col);
        g.fill(4, 0, size + 4, 1, col);
        g.fill(0, -size - 4, 1, -4, col);
        g.fill(0, 4, 1, size + 4, col);
        g.pose().popMatrix();
    }

    private static void damageNumbers(GuiGraphics g, Font font, int w, int h) {
        long now = ClientState.clientTick;
        for (int i = 0; i < ClientState.NUMBERS; i++) {
            long age = now - ClientState.NUM_AT[i];
            if (ClientState.NUM_TEXT[i] == null || age > 16) continue;
            int alpha = (int) (255 * (1f - age / 17f));
            int col = (alpha << 24) | (ClientState.NUM_COL[i] & 0xFFFFFF);
            g.drawString(font, ClientState.NUM_TEXT[i], (int) (w / 2 + 12 + ClientState.NUM_X[i]), (int) (h / 2 - 14 - age * 1.5f), col);
        }
    }

    private static void scope(GuiGraphics g, int w, int h) {
        int size = Math.min(w, h);
        int x = (w - size) / 2, y = (h - size) / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, SCOPE, x, y, 0f, 0f, size, size, size, size);
        g.fill(0, 0, w, y, 0xFF000000);
        g.fill(0, y + size, w, h, 0xFF000000);
        g.fill(0, y, x, y + size, 0xFF000000);
        g.fill(x + size, y, w, y + size, 0xFF000000);
        g.fill(w / 2, y + size / 6, w / 2 + 1, y + size * 5 / 6, 0xCC000000);
        g.fill(x + size / 6, h / 2, x + size * 5 / 6, h / 2 + 1, 0xCC000000);
    }

    private static final String[] HEAD = {"PLAYER", "K", "D", "DMG"};

    private static void board(GuiGraphics g, Font font, int w, int h) {
        var rows = ClientState.board;
        int bw = 260, rowH = 12;
        int bh = 20 + rows.size() * rowH + 6;
        int x = (w - bw) / 2, y = Math.max(40, (h - bh) / 2);
        g.fill(x, y, x + bw, y + bh, 0xD0101418);
        int colK = x + 150, colD = x + 180, colDmg = x + 215;
        g.drawString(font, HEAD[0], x + 8, y + 6, DIM);
        g.drawString(font, HEAD[1], colK, y + 6, DIM);
        g.drawString(font, HEAD[2], colD, y + 6, DIM);
        g.drawString(font, HEAD[3], colDmg, y + 6, DIM);
        int ry = y + 20;
        for (var r : rows) {
            int col = r.left() ? 0xFF666666 : ClientState.teamColour(r.team(), false);
            if (!r.alive()) col = (col & 0x00FFFFFF) | 0x90000000;
            g.drawString(font, r.name(), x + 8, ry, col);
            g.drawString(font, r.k(), colK, ry, WHITE);
            g.drawString(font, r.d(), colD, ry, WHITE);
            g.drawString(font, r.dmg(), colDmg, ry, WHITE);
            if (r.lives() >= 0) {
                for (int i = 0; i < r.lives(); i++) g.fill(x + 120 + i * 6, ry + 2, x + 124 + i * 6, ry + 6, RED);
            }
            ry += rowH;
        }
    }
}
