package dev.cylox.msm.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class MsmButtonWidget extends ButtonWidget {
    private String label;
    private boolean selected;
    private boolean danger;
    private boolean trashIcon;
    private boolean closeIcon;
    private boolean lineStyle;
    private boolean addUserIcon;
    private String trailingLabel = "";
    private boolean leftAligned;
    private boolean flat;
    private boolean cardBackground;
    private boolean accountCardStyle;
    private String subtitle = "";
    private String statusLabel = "";
    private int statusColor = 0xFF9EADBD;

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

    public void setTrashIcon(boolean trashIcon) {
        this.trashIcon = trashIcon;
        this.danger = trashIcon;
    }

    public void setCloseIcon(boolean closeIcon) {
        this.closeIcon = closeIcon;
    }

    public void setLineStyle(boolean lineStyle) {
        this.lineStyle = lineStyle;
    }

    public void setAddUserIcon(boolean addUserIcon) {
        this.addUserIcon = addUserIcon;
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

    public void setCardBackground(boolean cardBackground) {
        this.cardBackground = cardBackground;
    }

    public void setAccountCardStyle(boolean accountCardStyle) {
        this.accountCardStyle = accountCardStyle;
    }

    public void setCardDetails(String subtitle, String statusLabel, int statusColor) {
        this.subtitle = subtitle == null ? "" : subtitle;
        this.statusLabel = statusLabel == null ? "" : statusLabel;
        this.statusColor = statusColor;
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

        int left = this.getX();
        int top = this.getY();
        int right = left + this.getWidth();
        int bottom = top + this.getHeight();

        if (this.flat) {
            if (this.cardBackground) {
                int cardFill;
                int cardBorder;
                if (this.accountCardStyle) {
                    cardFill = !this.active ? 0x1A000000
                            : this.selected ? 0x800066CC
                            : hovered ? 0x665F6874 : 0x33000000;
                    cardBorder = !this.active ? 0x55303A47
                            : this.selected ? 0x8055AAEE
                            : hovered ? 0x887D8795 : 0x554B5563;
                } else {
                    cardFill = !this.active ? 0x4A202833
                            : this.selected ? (hovered ? 0xB52B566B : 0x8A1D4053)
                            : hovered ? 0xA0303D4D : 0x55212B38;
                    cardBorder = !this.active ? 0x6A303A47
                            : this.selected ? 0xFF55B8D5
                            : hovered ? 0xCC6BBBD2 : 0x80435369;
                }
                context.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), cardFill);
                context.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + 1, cardBorder);
                context.fill(this.getX(), this.getY() + this.getHeight() - 1, this.getX() + this.getWidth(), this.getY() + this.getHeight(), cardBorder);
                context.fill(this.getX(), this.getY(), this.getX() + 1, this.getY() + this.getHeight(), cardBorder);
                context.fill(this.getX() + this.getWidth() - 1, this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), cardBorder);
            }
            if (!this.active) {
                textColor = 0xFF7F8B98;
            } else if (this.accountCardStyle) {
                textColor = this.selected || hovered ? 0xFFFFFFFF : 0xFFE8EDF3;
            } else if (this.selected) {
                textColor = 0xFF7AD0E8;
            } else if (hovered) {
                textColor = 0xFFFFFFFF;
            }
            if (this.selected && !this.cardBackground) {
                context.fill(left, top + 3, left + 2, bottom - 3, 0xFF55B8D5);
            }
        } else if (this.lineStyle) {
            int lineBackground = !this.active ? 0x12000000
                    : this.danger ? (hovered ? 0x4D7A2833 : 0x18000000)
                    : this.selected ? 0x800066CC
                    : hovered ? 0x665F6874 : 0x33000000;
            int lineBorder = !this.active ? 0x55303A47
                    : this.danger ? (hovered ? 0xFFE76A77 : 0xB9C95361)
                    : this.selected ? 0xFF55B8D5
                    : hovered ? 0xCC9AA5B3 : 0x805B6572;
            textColor = !this.active ? 0xFF7F8B98
                    : this.danger ? 0xFFFF8792
                    : this.selected || hovered ? 0xFFFFFFFF : 0xFFE8EDF3;
            context.fill(left, top, right, bottom, lineBackground);
            context.fill(left, top, right, top + 1, lineBorder);
            context.fill(left, bottom - 1, right, bottom, lineBorder);
            context.fill(left, top, left + 1, bottom, lineBorder);
            context.fill(right - 1, top, right, bottom, lineBorder);
        } else {
            context.fill(left, top, right, bottom, background);
            context.fill(left, top, right, top + 1, accent);
            context.fill(left, bottom - 1, right, bottom, 0x553B4655);
        }

        if (this.closeIcon) {
            drawCloseX(context, hovered);
            return;
        }
        if (this.trashIcon) {
            drawTrashCan(context, hovered);
            return;
        }
        if (this.addUserIcon) {
            drawAddUserIcon(context, hovered);
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (!this.subtitle.isEmpty()) {
            int statusWidth = this.statusLabel.isEmpty() ? 0
                    : client.textRenderer.getWidth(this.statusLabel);
            int statusLeft = right - statusWidth - 20;
            if (!this.statusLabel.isEmpty()) {
                int badgeLeft = statusLeft - 8;
                int badgeBackground = (this.statusColor & 0x00FFFFFF) | 0x26000000;
                context.fill(badgeLeft, top + 5, badgeLeft + statusWidth + 16, top + 20, badgeBackground);
                context.fill(badgeLeft + 4, top + 10, badgeLeft + 8,
                        top + 14, this.statusColor);
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal(this.statusLabel), badgeLeft + 11,
                        top + 8, this.statusColor);
            }
            int availableWidth = Math.max(1,
                    (this.statusLabel.isEmpty() ? right - 8 : statusLeft - 8)
                            - left - 18);
            String primary = this.label;
            if (client.textRenderer.getWidth(primary) > availableWidth) {
                String suffix = "...";
                int suffixWidth = client.textRenderer.getWidth(suffix);
                primary = suffixWidth >= availableWidth
                        ? client.textRenderer.trimToWidth(primary, availableWidth)
                        : client.textRenderer.trimToWidth(primary, availableWidth - suffixWidth) + suffix;
            }
            context.drawTextWithShadow(client.textRenderer, Text.literal(primary),
                    left + 10, top + 6, textColor);
            int subtitleColor = this.accountCardStyle
                    ? (this.selected || hovered ? 0xFFE4ECF7 : 0xFF9BA6B3)
                    : (this.active ? 0xFF8997A8 : 0xFF687585);
            context.drawTextWithShadow(client.textRenderer, Text.literal(this.subtitle),
                    left + 10, top + 18, subtitleColor);
            return;
        }

        if (!this.trailingLabel.isEmpty()) {
            int trailingWidth = client.textRenderer.getWidth(this.trailingLabel);
            int gapWidth = this.flat
                    ? client.textRenderer.getWidth("  ") : 8;
            int primaryWidth = Math.max(1, this.getWidth() - trailingWidth - gapWidth - 16);
            String primary = this.label;
            if (client.textRenderer.getWidth(primary) > primaryWidth) {
                primary = client.textRenderer.trimToWidth(primary, Math.max(1, primaryWidth - 3)) + "...";
            }
            int textY = top + (this.getHeight() - 8) / 2;
            context.drawTextWithShadow(client.textRenderer, Text.literal(primary), left + 8, textY, textColor);
            int trailingX = this.flat
                    ? left + 8 + client.textRenderer.getWidth(primary) + gapWidth
                    : right - trailingWidth - 8;
            context.drawTextWithShadow(client.textRenderer, Text.literal(this.trailingLabel),
                    trailingX, textY, 0xFF7FA9F0);
            return;
        }

        if (this.leftAligned) {
            int availableWidth = Math.max(1, this.getWidth() - 14);
            String visibleLabel = this.label;
            if (client.textRenderer.getWidth(visibleLabel) > availableWidth) {
                visibleLabel = client.textRenderer.trimToWidth(visibleLabel,
                        Math.max(1, availableWidth - client.textRenderer.getWidth("..."))) + "...";
            }
            int textY = top + (this.getHeight() - 8) / 2;
            context.drawTextWithShadow(client.textRenderer, Text.literal(visibleLabel),
                    left + 8, textY, textColor);
            return;
        }

        int availableWidth = Math.max(1, this.getWidth() - 10);
        String visibleLabel = this.label;
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
    private void drawCloseX(DrawContext context, boolean hovered) {
        int color = hovered ? 0xFFFFFFFF : 0xFFD0D9E8;
        int center = this.getX() + this.getWidth() / 2;
        int top = this.getY() + 5;
        for (int offset = 0; offset < 8; offset++) {
            context.fill(center - 3 + offset, top + offset,
                    center - 1 + offset, top + offset + 2, color);
            context.fill(center + 1 - offset, top + offset,
                    center + 3 - offset, top + offset + 2, color);
        }
    }

    private void drawAddUserIcon(DrawContext context, boolean hovered) {
        int color = hovered ? 0xFFF3F6FB : 0xFFD0D9E8;
        int center = this.getX() + this.getWidth() / 2;
        int top = this.getY() + 3;
        context.fill(center - 2, top, center + 2, top + 4, color);
        context.fill(center - 5, top + 6, center + 3, top + 12, color);
        context.fill(center + 5, top + 3, center + 7, top + 9, color);
        context.fill(center + 3, top + 5, center + 9, top + 7, color);
    }

    private void drawTrashCan(DrawContext context, boolean hovered) {
        int color = hovered ? 0xFFFF8792 : 0xFFD85A68;
        int center = this.getX() + this.getWidth() / 2;
        int top = this.getY() + 3;
        context.fill(center - 2, top, center + 2, top + 2, color);
        context.fill(center - 5, top + 2, center + 5, top + 3, color);
        context.fill(center - 4, top + 4, center - 3, top + 12, color);
        context.fill(center + 3, top + 4, center + 4, top + 12, color);
        context.fill(center - 3, top + 12, center + 3, top + 13, color);
    }
}
