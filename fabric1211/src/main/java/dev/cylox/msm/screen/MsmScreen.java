package dev.cylox.msm.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

abstract class MsmScreen extends Screen {
    private DrawContext drawContext;

    protected MsmScreen(Text title) {
        super(title);
    }

    protected void renderMsmBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        this.drawContext = context;
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, this.width, this.height, 0x28070B12);
    }

    protected void drawGlassPanel(int left, int top, int right, int bottom) {
        if (this.drawContext == null || right - left < 2 || bottom - top < 2) return;
        this.drawContext.fill(left, top, right, bottom, 0xFF303A49);
        this.drawContext.fill(left + 1, top + 1, right - 1, bottom - 1, 0xF018202C);
        this.drawContext.fill(left + 1, top + 1, left + 3, bottom - 1, 0xFF3C91AF);
    }

    protected void drawGlassDivider(int left, int top, int right) {
        if (this.drawContext == null || right <= left) return;
        this.drawContext.fill(left, top, right, top + 1, 0xFF303A49);
    }

    protected void drawSectionTitle(String label, int x, int y) {
        drawString(MinecraftClient.getInstance().textRenderer, label, x, y, 0xFF9EADBD);
    }

    protected void drawString(TextRenderer renderer, String value, int x, int y, int color) {
        if (this.drawContext != null) {
            this.drawContext.drawTextWithShadow(renderer, value, x, y, color);
        }
    }

    protected void drawCenteredString(TextRenderer renderer, String value,
                                      int centerX, int y, int color) {
        if (this.drawContext != null) {
            this.drawContext.drawCenteredTextWithShadow(renderer, value, centerX, y, color);
        }
    }
}
