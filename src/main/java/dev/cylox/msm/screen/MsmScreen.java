package dev.cylox.msm.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

abstract class MsmScreen extends Screen {
    private final Text msmTitle;

    protected MsmScreen(Text title) {
        super(title);
        this.msmTitle = title;
    }

    protected String getMsmPageTitle() {
        String value = this.msmTitle.getString();
        int separator = value.indexOf(" - ");
        return (separator >= 0 ? value.substring(0, separator) : value).toUpperCase();
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        super.render(mouseX, mouseY, delta);
        int color = 0xFFE0E8F0;
        fill(0, 0, this.width, 27, 0xB0101822);
        fill(0, 26, this.width, 27, 0x803A4655);
        String label = "MULTI SESSIONS MANAGER  /  " + getMsmPageTitle();
        String fitted = MinecraftClient.getInstance().textRenderer.trimToWidth(
                label, Math.max(1, this.width - 52));
        drawString(MinecraftClient.getInstance().textRenderer, fitted, 14, 9, color);
        int x = this.width - 19;
        int icon = 0xFFE0E8F0;
        fill(x - 4, 8, x - 2, 10, icon);
        fill(x + 2, 8, x + 4, 10, icon);
        fill(x - 2, 10, x, 12, icon);
        fill(x, 12, x + 2, 14, icon);
        fill(x - 2, 14, x, 16, icon);
        fill(x, 16, x + 2, 18, icon);
        fill(x - 4, 18, x - 2, 20, icon);
        fill(x + 2, 18, x + 4, 20, icon);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= this.width - 28 && mouseY <= 27) {
            MinecraftClient.getInstance().openScreen(null);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    protected void renderMsmFooter(String status, int rightReserved) {
        renderMsmFooter(status, rightReserved, 0xFF9EADBD);
    }

    protected void renderMsmFooter(String status, int rightReserved, int color) {
        fill(0, this.height - 36, this.width, this.height, 0x68101822);
        int availableWidth = Math.max(1, this.width - 28 - Math.max(0, rightReserved));
        String visible = MinecraftClient.getInstance().textRenderer.trimToWidth(
                status == null ? "" : status, availableWidth);
        drawString(MinecraftClient.getInstance().textRenderer, visible, 14, this.height - 22, color);
    }

    protected void renderMsmBackground() {
        renderBackground();
        fill(0, 0, this.width, this.height, 0x28070B12);
    }

    protected void drawGlassPanel(int left, int top, int right, int bottom) {
        if (right - left < 2 || bottom - top < 2) return;

        fill(left, top, right, bottom, 0x702B3543);
        fill(left + 1, top + 1, right - 1, bottom - 1, 0x9817202B);
        fill(left + 1, top + 1, Math.min(left + 2, right - 1), bottom - 1, 0x704F91A5);
        fill(left + 1, top + 1, right - 1, top + 2, 0x604F6478);
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
