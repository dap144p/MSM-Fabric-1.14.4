package dev.cylox.msm.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;

public final class MsmButtonWidget extends ButtonWidget {
    private String label;

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

    @Override
    public void renderButton(int mouseX, int mouseY, float delta) {
        boolean hovered = mouseX >= this.x && mouseY >= this.y
                && mouseX < this.x + this.width && mouseY < this.y + this.height;
        int background = !this.active ? 0x66505A66
                : hovered ? 0xD52B7188 : 0xB51A3445;
        int accent = !this.active ? 0x554B7580
                : hovered ? 0xD05ADCF0 : 0x8053B9D0;
        int textColor = this.active ? 0xFFFFFFFF : 0xFF9AA7B2;

        fill(this.x, this.y, this.x + this.width, this.y + this.height, background);
        fill(this.x, this.y, this.x + this.width, this.y + 1, accent);
        fill(this.x, this.y + 1, this.x + 2, this.y + this.height - 1, accent);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                this.label, this.x + this.width / 2,
                this.y + (this.height - 8) / 2, textColor);
    }
}
