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

        fill(left, top, right, bottom, 0xB02B3543);
        fill(left + 1, top + 1, right - 1, bottom - 1, 0xC817202B);
        fill(left + 1, top + 1, Math.min(left + 3, right - 1), bottom - 1, 0xD13F9CB8);
        fill(left + 1, top + 1, right - 1, top + 2, 0x904F6478);
    }

    protected void drawGlassDivider(int left, int top, int right) {
        if (right <= left) return;
        fill(left, top, right, top + 1, 0xA8303A49);
    }

    protected void drawSectionTitle(String label, int x, int y) {
        drawString(net.minecraft.client.MinecraftClient.getInstance().textRenderer,
                label, x, y, 0xFF9EADBD);
    }
}
