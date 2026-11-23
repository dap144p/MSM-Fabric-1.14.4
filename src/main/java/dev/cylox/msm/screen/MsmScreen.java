package dev.cylox.msm.screen;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

abstract class MsmScreen extends Screen {
    protected MsmScreen(Text title) {
        super(title);
    }

    protected void renderMsmBackground() {
        renderBackground();
        fill(0, 0, this.width, this.height, 0x18040A12);
    }

    protected void drawGlassPanel(int left, int top, int right, int bottom) {
        if (right - left < 2 || bottom - top < 2) return;

        fill(left, top, right, bottom, 0xB5101722);
        fill(left, top, right, top + 1, 0xB04BC7DE);
        fill(left, top + 1, left + 1, bottom - 1, 0x604BC7DE);
        fill(left, bottom - 1, right, bottom, 0x405A91A8);
    }

    protected void drawGlassDivider(int left, int top, int right) {
        if (right <= left) return;
        fill(left, top, right, top + 1, 0x5559AFC5);
    }
}
