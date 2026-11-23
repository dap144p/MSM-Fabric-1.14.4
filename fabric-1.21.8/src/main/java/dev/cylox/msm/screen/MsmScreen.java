package dev.cylox.msm.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public abstract class MsmScreen extends Screen {
    protected final Screen parent;

    protected MsmScreen(Text title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    protected ButtonWidget addAction(int x, int y, int width, int height,
                                     String label, ButtonWidget.PressAction action) {
        return this.addDrawableChild(ButtonWidget.builder(Text.literal(label), action)
                .dimensions(x, y, Math.max(1, width), Math.max(1, height))
                .build());
    }

    protected void panel(DrawContext context, int left, int top, int right, int bottom) {
        context.fill(left, top, right, bottom, 0xD91A202B);
        context.fill(left, top, right, top + 1, 0xFF354353);
        context.fill(left, bottom - 1, right, bottom, 0xFF111720);
    }

    protected void text(DrawContext context, String value, int x, int y, int color) {
        context.drawTextWithShadow(this.textRenderer, Text.literal(value), x, y, color);
    }

    protected void centered(DrawContext context, String value, int x, int y, int color) {
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(value), x, y, color);
    }

    protected String fit(String value, int pixels) {
        if (value == null) return "";
        int characters = Math.max(8, pixels / 6);
        if (value.length() <= characters) return value;
        return value.substring(0, characters - 3) + "...";
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
