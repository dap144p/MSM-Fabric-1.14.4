package dev.cylox.msm.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;

public final class MsmButtonWidget extends ButtonWidget {
    private String label;
    private boolean selected;
    private boolean danger;
    private boolean trashIcon;
    private boolean closeIcon;
    private String trailingLabel = "";
    private boolean leftAligned;
    private boolean flat;

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

    public void setCloseIcon(boolean closeIcon) {
        this.closeIcon = closeIcon;
    }

    public void setTrailingLabel(String trailingLabel) {
        this.trailingLabel = trailingLabel == null ? "" : trailingLabel;
    }

    public void setLeftAligned(boolean leftAligned) {
        this.leftAligned = leftAligned;
    }

    public void setFlat(boolean flat) {
        this.flat = flat;
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
        } else if (this.closeIcon) {
            background = hovered ? 0x80505D70 : 0x302A3545;
            accent = hovered ? 0xFFB8C5D8 : 0x70435369;
            textColor = 0xFFE5ECF5;
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

        if (this.flat) {
            if (!this.active) {
                textColor = 0xFF7F8B98;
            } else if (this.selected) {
                textColor = 0xFF7AD0E8;
            } else if (hovered) {
                textColor = 0xFFFFFFFF;
            }
            if (this.selected) {
                fill(this.x, this.y + 3, this.x + 2,
                        this.y + this.height - 3, 0xFF55B8D5);
            }
        } else {
            fill(this.x, this.y, this.x + this.width, this.y + this.height, background);
            fill(this.x, this.y, this.x + this.width, this.y + 1, accent);
            fill(this.x, this.y + this.height - 1,
                    this.x + this.width, this.y + this.height, 0x553B4655);
        }

        if (this.closeIcon) {
            drawCloseX(hovered);
            return;
        }
        if (this.trashIcon) {
            drawTrashCan(hovered);
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (!this.trailingLabel.isEmpty()) {
            int trailingWidth = client.textRenderer.getStringWidth(this.trailingLabel);
            int primaryWidth = Math.max(1, this.width - trailingWidth - 24);
            String primary = this.label;
            if (client.textRenderer.getStringWidth(primary) > primaryWidth) {
                primary = client.textRenderer.trimToWidth(primary, Math.max(1, primaryWidth - 3)) + "...";
            }
            int textY = this.y + (this.height - 8) / 2;
            drawString(client.textRenderer, primary, this.x + 8, textY, textColor);
            drawString(client.textRenderer, this.trailingLabel,
                    this.x + this.width - trailingWidth - 8, textY, 0xFF7FA9F0);
            return;
        }

        if (this.leftAligned) {
            int availableWidth = Math.max(1, this.width - 14);
            String visibleLabel = this.label;
            if (client.textRenderer.getStringWidth(visibleLabel) > availableWidth) {
                visibleLabel = client.textRenderer.trimToWidth(visibleLabel,
                        Math.max(1, availableWidth - client.textRenderer.getStringWidth("..."))) + "...";
            }
            drawString(client.textRenderer, visibleLabel, this.x + 8,
                    this.y + (this.height - 8) / 2, textColor);
            return;
        }

        int availableWidth = Math.max(1, this.width - 10);
        String visibleLabel = this.label;
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
    private void drawCloseX(boolean hovered) {
        int color = hovered ? 0xFFFFFFFF : 0xFFD0D9E8;
        int center = this.x + this.width / 2;
        int top = this.y + 5;
        for (int offset = 0; offset < 8; offset++) {
            fill(center - 3 + offset, top + offset,
                    center - 1 + offset, top + offset + 2, color);
            fill(center + 1 - offset, top + offset,
                    center + 3 - offset, top + offset + 2, color);
        }
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
        for (int offset = 0; offset < 7; offset++) {
            fill(center - 3 + offset, top + 4 + offset,
                    center - 1 + offset, top + 6 + offset, color);
            fill(center + 1 - offset, top + 4 + offset,
                    center + 3 - offset, top + 6 + offset, color);
        }
    }
}
