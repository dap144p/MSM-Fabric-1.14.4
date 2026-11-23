package dev.cylox.msm.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public abstract class MsmScreen extends Screen {
    protected final Screen parent;

    protected MsmScreen(Text title, Screen parent) {
        super(title);
        this.parent = parent;
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

    protected void renderMsmBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, this.width, this.height, 0x28070B12);
    }

    protected void drawGlassPanel(DrawContext context, int left, int top, int right, int bottom) {
        if (right - left < 2 || bottom - top < 2) return;
        context.fill(left, top, right, bottom, 0xFF303A49);
        context.fill(left + 1, top + 1, right - 1, bottom - 1, 0xF018202C);
        context.fill(left + 1, top + 1, left + 3, bottom - 1, 0xFF3C91AF);
    }

    protected void drawGlassDivider(DrawContext context, int left, int top, int right) {
        if (right <= left) return;
        context.fill(left, top, right, top + 1, 0xFF303A49);
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
