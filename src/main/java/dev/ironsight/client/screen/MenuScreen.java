package dev.ironsight.client.screen;

import dev.ironsight.Ironsight;
import dev.ironsight.client.ClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Shared look for the mod's menus: dark backdrop, title, optional countdown. Never pauses the game. */
@Environment(EnvType.CLIENT)
public abstract class MenuScreen extends Screen {

    private static final Identifier BG = Ironsight.id("textures/gui/menu_bg.png");
    private final long closesAt;
    private String countdown = "";
    private int lastSecs = -1;

    protected MenuScreen(String title, int seconds) {
        super(Component.literal(title));
        this.closesAt = seconds > 0 ? ClientState.clientTick + seconds * 20L : -1;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.blit(RenderPipelines.GUI_TEXTURED, BG, 0, 0, 0f, 0f, width, height, width, height);
        g.fill(0, 0, width, height, 0x80000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(font, title, width / 2, 16, 0xFFFFD24A);
        if (closesAt > 0) {
            int secs = (int) Math.max(0, (closesAt - ClientState.clientTick + 19) / 20);
            if (secs != lastSecs) {
                lastSecs = secs;
                countdown = secs + "s";
            }
            g.drawCenteredString(font, countdown, width / 2, 28, secs <= 3 ? 0xFFFF5555 : 0xFFAAB2BD);
        }
    }

    @Override
    public void tick() {
        if (closesAt > 0 && ClientState.clientTick > closesAt + 10) onClose();
    }
}
