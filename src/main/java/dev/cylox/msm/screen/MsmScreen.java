package dev.cylox.msm.screen;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

abstract class MsmScreen extends Screen {
    protected MsmScreen(Text title) {
        super(title);
    }

    protected void renderMsmBackground() {
        renderBackground();
        fill(0, 0, this.width, this.height, 0x28070B12);
    }

    protected void drawGlassPanel(int left, int top, int right, int bottom) {
        if (right - left < 2 || bottom - top < 2) return;

        fill(left, top, right, bottom, 0xFF303A49);
        fill(left + 1, top + 1, right - 1, bottom - 1, 0xF018202C);
        fill(left + 1, top + 1, left + 3, bottom - 1, 0xFF3C91AF);
    }

    protected void drawGlassDivider(int left, int top, int right) {
        if (right <= left) return;
        fill(left, top, right, top + 1, 0xFF303A49);
    }

    protected void drawSectionTitle(String label, int x, int y) {
        drawString(net.minecraft.client.MinecraftClient.getInstance().textRenderer,
                label, x, y, 0xFF9EADBD);
    }
}
