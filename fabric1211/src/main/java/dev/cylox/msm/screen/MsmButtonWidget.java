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
        String value = message == null ? "" : message;
        super.setMessage(Text.literal(value));
        this.label = value;
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
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        boolean hovered = mouseX >= this.getX() && mouseY >= this.getY()
                && mouseX < this.getX() + this.width && mouseY < this.getY() + this.height;

        int background;
        int accent;
        int textColor;
        if (!this.active) {
            background = 0xFF202833;
            accent = 0xFF303A47;
            textColor = 0xFF7F8B98;
        } else if (this.danger) {
            background = hovered ? 0xFF663941 : 0xFF472B32;
            accent = 0xFFCC6874;
            textColor = 0xFFFFE8EA;
        } else if (this.selected) {
            background = hovered ? 0xFF24566D : 0xFF1D465B;
            accent = 0xFF55B8D5;
            textColor = 0xFFFFFFFF;
        } else {
            background = hovered ? 0xFF354355 : 0xFF293545;
            accent = hovered ? 0xFF6BBBD2 : 0xFF3B4B5E;
            textColor = 0xFFE8EDF3;
        }

        int x = this.getX();
        int y = this.getY();
        context.fill(x, y, x + this.width, y + this.height, background);
        context.fill(x, y, x + this.width, y + 1, accent);
        context.fill(x, y + this.height - 1, x + this.width, y + this.height, 0x553B4655);

        int maxCharacters = Math.max(1, (this.width - 10) / 6);
        String visibleLabel = this.label;
        if (visibleLabel.length() > maxCharacters) {
            visibleLabel = visibleLabel.substring(0, Math.max(1, maxCharacters - 3)) + "...";
        }

        context.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer,
                visibleLabel, x + this.width / 2,
                y + (this.height - 8) / 2, textColor);
    }
}
