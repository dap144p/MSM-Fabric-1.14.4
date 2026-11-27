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

    protected boolean useLineAccountManagerHeader() {
        return false;
    }

    protected void onLineAccountManagerBack() {
    }

    private void drawLineHeaderButton(int left, int top, int right, int bottom, boolean hovered) {
        int background = hovered ? 0x665F6874 : 0x1A000000;
        int border = hovered ? 0xCC9AA5B3 : 0x805B6572;
        fill(left, top, right, bottom, background);
        fill(left, top, right, top + 1, border);
        fill(left, bottom - 1, right, bottom, border);
        fill(left, top, left + 1, bottom, border);
        fill(right - 1, top, right, bottom, border);
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        super.render(mouseX, mouseY, delta);
        if (useLineAccountManagerHeader()) {
            fill(0, 0, this.width, 27, 0xB0101822);
            fill(0, 26, this.width, 27, 0x803A4655);
            boolean backHovered = mouseX >= 6 && mouseX < 28 && mouseY >= 4 && mouseY < 22;
            boolean closeHovered = mouseX >= this.width - 28 && mouseX < this.width - 6
                    && mouseY >= 4 && mouseY < 22;
            drawLineHeaderButton(6, 4, 28, 22, backHovered);
            drawLineHeaderButton(this.width - 28, 4, this.width - 6, 22, closeHovered);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    "<", 17, 9, backHovered ? 0xFFFFFFFF : 0xFFD0D9E8);
            int x = this.width - 17;
            int icon = closeHovered ? 0xFFFFFFFF : 0xFFD0D9E8;
            for (int offset = 0; offset < 7; offset++) {
                fill(x - 3 + offset, 8 + offset, x - 1 + offset, 10 + offset, icon);
                fill(x + 1 - offset, 8 + offset, x + 3 - offset, 10 + offset, icon);
            }
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    getMsmPageTitle(), this.width / 2, 9, 0xFFE0E8F0);
            return;
        }
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
        if (button == 0 && useLineAccountManagerHeader()
                && mouseX >= 6 && mouseX < 30 && mouseY >= 0 && mouseY <= 27) {
            onLineAccountManagerBack();
            return true;
        }
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
