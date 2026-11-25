package dev.cylox.msm.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class MsmButtonWidget extends ButtonWidget {
    private String label;
    private boolean selected;
    private boolean danger;

    public MsmButtonWidget(int x, int y, int width, int height,
                           String message, PressAction onPress) {
        super(x, y, width, height, Text.literal(message == null ? "" : message),
                onPress, DEFAULT_NARRATION_SUPPLIER);
        this.label = message == null ? "" : message;
    }

    public void setMessage(String message) {
        this.label = message == null ? "" : message;
        super.setMessage(Text.literal(this.label));
    }

    @Override
    public void setMessage(Text message) {
        super.setMessage(message);
        this.label = message == null ? "" : message.getString();
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void setDanger(boolean danger) {
        this.danger = danger;
    }

    @Override
    public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        boolean hovered = this.isHovered();
        int background;
        int accent;
        int textColor;

        if (!this.active) {
            background = 0xA0202833;
            accent = 0x8A303A47;
            textColor = 0xFF7F8B98;
        } else if (this.danger) {
            background = hovered ? 0xD3663941 : 0xB8472B32;
            accent = 0xFFCC6874;
            textColor = 0xFFFFE8EA;
        } else if (this.selected) {
            background = hovered ? 0xD924566D : 0xC51D465B;
            accent = 0xFF55B8D5;
            textColor = 0xFFFFFFFF;
        } else {
            background = hovered ? 0xD0354355 : 0xB5293545;
            accent = hovered ? 0xFF6BBBD2 : 0xFF3B4B5E;
            textColor = 0xFFE8EDF3;
        }

        int left = this.getX();
        int top = this.getY();
        int right = left + this.getWidth();
        int bottom = top + this.getHeight();

        context.fill(left, top, right, bottom, background);
        context.fill(left, top, right, top + 1, accent);
        context.fill(left, bottom - 1, right, bottom, 0x553B4655);

        int availableWidth = Math.max(1, this.getWidth() - 10);
        String visibleLabel = this.label;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.textRenderer.getWidth(visibleLabel) > availableWidth) {
            String suffix = "...";
            int suffixWidth = client.textRenderer.getWidth(suffix);
            visibleLabel = suffixWidth >= availableWidth
                    ? client.textRenderer.trimToWidth(visibleLabel, availableWidth)
                    : client.textRenderer.trimToWidth(visibleLabel, availableWidth - suffixWidth) + suffix;
        }

        context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(visibleLabel),
                left + this.getWidth() / 2, top + (this.getHeight() - 8) / 2, textColor);
    }
}
