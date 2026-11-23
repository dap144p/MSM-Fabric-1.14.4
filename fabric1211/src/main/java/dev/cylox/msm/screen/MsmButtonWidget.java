package dev.cylox.msm.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * MSM-styled text button using the 1.21.11 button implementation.
 */
public final class MsmButtonWidget extends ButtonWidget.Text {
    private String label;
    private boolean selected;
    private boolean danger;

    public MsmButtonWidget(int x, int y, int width, int height,
                           String message, PressAction onPress) {
        super(x, y, width, height,
                net.minecraft.text.Text.literal(message == null ? "" : message),
                onPress, DEFAULT_NARRATION_SUPPLIER);
        this.label = message == null ? "" : message;
        updateMessage();
    }

    public void setMessage(String message) {
        this.label = message == null ? "" : message;
        updateMessage();
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        updateMessage();
    }

    public void setDanger(boolean danger) {
        this.danger = danger;
        updateMessage();
    }

    private void updateMessage() {
        int maxCharacters = Math.max(1, (this.width - 10) / 6);
        String visibleLabel = this.label;
        if (visibleLabel.length() > maxCharacters) {
            visibleLabel = visibleLabel.substring(0, Math.max(1, maxCharacters - 3)) + "...";
        }

        net.minecraft.text.MutableText text = net.minecraft.text.Text.literal(visibleLabel);
        if (this.danger) {
            text.styled(style -> style.withColor(0xFFFF9AA4));
        } else if (this.selected) {
            text.styled(style -> style.withColor(0xFF7BD5F0));
        }
        super.setMessage(text);
    }

    @Override
    protected void drawIcon(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        if (!this.active || (!this.selected && !this.danger)) return;
        int accent = this.danger ? 0xFFCC6874 : 0xFF55B8D5;
        context.fill(this.getX(), this.getY(), this.getX() + 2,
                this.getY() + this.height, accent);
    }
}
