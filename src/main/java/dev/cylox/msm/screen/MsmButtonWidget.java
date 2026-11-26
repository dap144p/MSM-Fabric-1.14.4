package dev.cylox.msm.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;

public final class MsmButtonWidget extends ButtonWidget {
    private String label;
    private boolean selected;
    private boolean danger;
    private boolean trashIcon;

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

    public void setTrashIcon(boolean trashIcon) {
        this.trashIcon = trashIcon;
        this.danger = trashIcon;
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
        } else if (this.trashIcon) {
            background = hovered ? 0xB84E2831 : 0x782A2028;
            accent = hovered ? 0xFFE76A77 : 0xB9C95361;
            textColor = 0xFFFFE8EA;
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

        if (this.trashIcon) {
            drawTrashCan(hovered);
            return;
        }

        int availableWidth = Math.max(1, this.width - 10);
        String visibleLabel = this.label;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.textRenderer.getStringWidth(visibleLabel) > availableWidth) {
            String suffix = "...";
            int suffixWidth = client.textRenderer.getStringWidth(suffix);
            visibleLabel = suffixWidth >= availableWidth
                    ? client.textRenderer.trimToWidth(visibleLabel, availableWidth)
                    : client.textRenderer.trimToWidth(visibleLabel, availableWidth - suffixWidth) + suffix;
        }

        drawCenteredString(client.textRenderer, visibleLabel,
                this.x + this.width / 2, this.y + (this.height - 8) / 2, textColor);
    }
    private void drawTrashCan(boolean hovered) {
        int color = hovered ? 0xFFFF8792 : 0xFFD85A68;
        int center = this.x + this.width / 2;
        int top = this.y + 3;
        fill(center - 2, top, center + 2, top + 2, color);
        fill(center - 5, top + 2, center + 5, top + 3, color);
        fill(center - 4, top + 4, center - 3, top + 12, color);
        fill(center + 3, top + 4, center + 4, top + 12, color);
        fill(center - 3, top + 12, center + 3, top + 13, color);
        for (int offset = 0; offset < 5; offset++) {
            fill(center - 2 + offset, top + 5 + offset,
                    center - 1 + offset, top + 6 + offset, color);
            fill(center + 1 - offset, top + 5 + offset,
                    center + 2 - offset, top + 6 + offset, color);
        }
    }
}
