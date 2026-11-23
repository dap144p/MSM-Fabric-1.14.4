package dev.cylox.msm.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class ConfirmRemoveAccountScreen extends MsmScreen {
    private final Screen parent;
    private final String username;
    private final String serverAddress;
    private final Runnable onConfirm;
    private MsmButtonWidget confirmButton;
    private boolean removing;

    public ConfirmRemoveAccountScreen(Screen parent, String username,
                                      String serverAddress, Runnable onConfirm) {
        super(Text.literal("Remove Account"));
        if (parent == null || onConfirm == null) {
            throw new IllegalArgumentException("Parent screen and removal action are required");
        }
        this.parent = parent;
        this.username = username == null ? "" : username;
        this.serverAddress = serverAddress == null ? "" : serverAddress;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(360, Math.max(220, this.width - 32));
        int left = (this.width - panelWidth) / 2;
        int buttonGap = 8;
        int buttonWidth = Math.max(1, (panelWidth - buttonGap - 24) / 2);
        int buttonLeft = left + 12;

        this.confirmButton = this.addDrawableChild(new MsmButtonWidget(
                buttonLeft, this.height / 2 + 28, buttonWidth, 20,
                "REMOVE", button -> confirmRemoval()));
        ((MsmButtonWidget) this.confirmButton).setDanger(true);

        this.addDrawableChild(new MsmButtonWidget(
                buttonLeft + buttonWidth + buttonGap, this.height / 2 + 28,
                buttonWidth, 20, "CANCEL", button -> close()));
    }

    private void confirmRemoval() {
        if (this.removing) return;
        this.removing = true;
        if (this.confirmButton != null) {
            this.confirmButton.active = false;
            this.confirmButton.setMessage("REMOVING...");
        }
        this.onConfirm.run();
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderMsmBackground(context, mouseX, mouseY, delta);
        int panelWidth = Math.min(360, Math.max(220, this.width - 32));
        int left = (this.width - panelWidth) / 2;
        int top = this.height / 2 - 62;
        drawGlassPanel(left, top, left + panelWidth, this.height / 2 + 62);

        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                "REMOVE ACCOUNT?", this.width / 2, top + 16, 0xFFF1F5FA);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                this.username, this.width / 2, top + 37, 0xFF6FC4DE);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                fitText(this.serverAddress, panelWidth - 28),
                this.width / 2, top + 52, 0xFFB3BFCD);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                "AutoChat and AutoReconnect settings",
                this.width / 2, top + 70, 0xFF9EADBD);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                "for this account will also be deleted.",
                this.width / 2, top + 82, 0xFF9EADBD);

        super.render(context, mouseX, mouseY, delta);
    }

    private String fitText(String value, int pixelWidth) {
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }
}
