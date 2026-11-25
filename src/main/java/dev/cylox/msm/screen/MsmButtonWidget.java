package dev.cylox.msm.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;

public final class MsmButtonWidget extends ButtonWidget {
    private String label;
    private boolean selected;
    private boolean danger;

    public MsmButtonWidget(int x, int y, int width, int height,
                           String message, PressAction onPress) {
        super(x, y, width, height, message, onPress);
        this.label = message == null ? "" : message;
    }

    @Override
    public void setMessage(String message) {
        super.setMessage(message);
        this.label = message == null ? "" : message;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void setDanger(boolean danger) {
        this.danger = danger;
    }

    @Override
    public void renderButton(int mouseX, int mouseY, float delta) {
        boolean hovered = mouseX >= this.x && mouseY >= this.y
                && mouseX < this.x + this.width && mouseY < this.y + this.height;

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

        fill(this.x, this.y, this.x + this.width, this.y + this.height, background);
        fill(this.x, this.y, this.x + this.width, this.y + 1, accent);
        fill(this.x, this.y + this.height - 1,
                this.x + this.width, this.y + this.height, 0x553B4655);

        int maxCharacters = Math.max(1, (this.width - 10) / 6);
        String visibleLabel = this.label;
        if (visibleLabel.length() > maxCharacters) {
            visibleLabel = visibleLabel.substring(0, Math.max(1, maxCharacters - 3)) + "...";
        }

        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                visibleLabel, this.x + this.width / 2,
                this.y + (this.height - 8) / 2, textColor);
    }
}
