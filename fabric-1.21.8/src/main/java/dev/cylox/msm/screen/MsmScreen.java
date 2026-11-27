package dev.cylox.msm.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public abstract class MsmScreen extends Screen {
    protected final Screen parent;
    private final Text msmTitle;

    protected MsmScreen(Text title, Screen parent) {
        super(title);
        this.parent = parent;
        this.msmTitle = title;
    }

    protected String getMsmPageTitle() {
        String value = this.msmTitle.getString();
        int separator = value.indexOf(" - ");
        return (separator >= 0 ? value.substring(0, separator) : value).toUpperCase();
    }

    protected int getMsmHeaderLeft() {
        return 0;
    }

    protected int getMsmHeaderRight() {
        return this.width;
    }

    protected int getMsmHeaderTop() {
        return 0;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int left = getMsmHeaderLeft();
        int right = getMsmHeaderRight();
        int top = getMsmHeaderTop();
        context.fill(left, top, right, top + 27, 0xB0101822);
        context.fill(left, top + 26, right, top + 27, 0x803A4655);
        String label = "MULTI SESSIONS MANAGER  /  " + getMsmPageTitle();
        String fitted = this.textRenderer.trimToWidth(label, Math.max(1, right - left - 52));
        context.drawTextWithShadow(this.textRenderer, Text.literal(fitted),
                left + 14, top + 9, 0xFFE0E8F0);
        int x = right - 19;
        boolean closeHovered = mouseX >= right - 28 && mouseX <= right
                && mouseX >= left && mouseY >= top && mouseY <= top + 27;
        int icon = closeHovered ? 0xFFFF5555 : 0xFFAAAAAA;
        for (int offset = 0; offset < 8; offset++) {
            context.fill(x - 4 + offset, top + 9 + offset,
                    x - 2 + offset, top + 11 + offset, icon);
            context.fill(x + 2 - offset, top + 9 + offset,
                    x + 4 - offset, top + 11 + offset, icon);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = getMsmHeaderLeft();
        int right = getMsmHeaderRight();
        int top = getMsmHeaderTop();
        if (button == 0 && mouseX >= right - 28 && mouseX <= right
                && mouseX >= left && mouseY >= top && mouseY <= top + 27) {
            this.client.setScreen(null);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    protected <T extends ClickableWidget> T addButton(T widget) {
        return this.addDrawableChild(widget);
    }

    protected MsmButtonWidget addAction(int x, int y, int width, int height,
                                        String label, ButtonWidget.PressAction action) {
        return this.addDrawableChild(new MsmButtonWidget(
                x, y, Math.max(1, width), Math.max(1, height), label, action));
    }

    protected TextFieldWidget addField(int x, int y, int width, int height, String placeholder) {
        TextFieldWidget field = new TextFieldWidget(
                this.textRenderer, x, y, Math.max(1, width), Math.max(1, height),
                Text.literal(placeholder == null ? "" : placeholder));
        field.setPlaceholder(Text.literal(placeholder == null ? "" : placeholder));
        return this.addDrawableChild(field);
    }

    protected void renderMsmFooter(DrawContext context, String status, int rightReserved) {
        renderMsmFooter(context, status, rightReserved, 0xFF9EADBD);
    }

    protected void renderMsmFooter(DrawContext context, String status, int rightReserved, int color) {
        context.fill(0, this.height - 36, this.width, this.height, 0x68101822);
        int availableWidth = Math.max(1, this.width - 28 - Math.max(0, rightReserved));
        String visible = this.textRenderer.trimToWidth(status == null ? "" : status, availableWidth);
        context.drawTextWithShadow(this.textRenderer, Text.literal(visible), 14, this.height - 22, color);
    }

    protected void renderMsmBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, this.width, this.height, 0x28070B12);
    }

    protected void drawGlassPanel(DrawContext context, int left, int top, int right, int bottom) {
        if (right - left < 2 || bottom - top < 2) return;
        context.fill(left, top, right, bottom, 0x702B3543);
        context.fill(left + 1, top + 1, right - 1, bottom - 1, 0x9817202B);
        context.fill(left + 1, top + 1, Math.min(left + 2, right - 1), bottom - 1, 0x704F91A5);
        context.fill(left + 1, top + 1, right - 1, top + 2, 0x604F6478);
    }

    protected void drawGlassDivider(DrawContext context, int left, int top, int right) {
        if (right <= left) return;
        context.fill(left, top, right, top + 1, 0xA8303A49);
    }

    protected void drawSectionTitle(DrawContext context, String label, int x, int y) {
        context.drawText(this.textRenderer, Text.literal(label), x, y, 0xFF9EADBD, false);
    }

    protected void panel(DrawContext context, int left, int top, int right, int bottom) {
        drawGlassPanel(context, left, top, right, bottom);
    }

    protected void text(DrawContext context, String value, int x, int y, int color) {
        context.drawTextWithShadow(this.textRenderer, Text.literal(value == null ? "" : value), x, y, color);
    }

    protected void centered(DrawContext context, String value, int x, int y, int color) {
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(value == null ? "" : value), x, y, color);
    }

    protected String fit(String value, int pixels) {
        if (value == null || pixels <= 0) return "";
        if (this.textRenderer.getWidth(value) <= pixels) return value;

        String suffix = "...";
        int suffixWidth = this.textRenderer.getWidth(suffix);
        if (suffixWidth >= pixels) return this.textRenderer.trimToWidth(value, pixels);
        return this.textRenderer.trimToWidth(value, pixels - suffixWidth) + suffix;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }
}
