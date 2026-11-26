package dev.cylox.msm.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.LiteralText;

import java.security.SecureRandom;

public final class ConfirmRemoveAccountScreen extends MsmScreen {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] CODE_CHARACTERS =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final Screen parent;
    private final String username;
    private final String serverAddress;
    private final Runnable onConfirm;
    private final boolean accountRemoval;
    private ButtonWidget confirmButton;
    private TextFieldWidget confirmationField;
    private final String confirmationCode;
    private boolean removing;

    public ConfirmRemoveAccountScreen(Screen parent, String username,
                                      String serverAddress, Runnable onConfirm) {
        this(parent, username, serverAddress, onConfirm, false);
    }

    public ConfirmRemoveAccountScreen(Screen parent, String username,
                                      String serverAddress, Runnable onConfirm,
                                      boolean accountRemoval) {
        super(new LiteralText("Remove Account"));
        if (parent == null || onConfirm == null) {
            throw new IllegalArgumentException("Parent screen and removal action are required");
        }
        this.parent = parent;
        this.username = username == null ? "" : username;
        this.serverAddress = serverAddress == null ? "" : serverAddress;
        this.onConfirm = onConfirm;
        this.accountRemoval = accountRemoval;
        this.confirmationCode = accountRemoval ? createConfirmationCode() : "";
    }

    @Override
    protected void init() {
        if (this.accountRemoval) {
            int panelWidth = Math.max(1, Math.min(380, this.width - 24));
            int left = (this.width - panelWidth) / 2;
            int top = this.height / 2 - 125;
            int buttonGap = 8;
            int buttonWidth = Math.max(1, (panelWidth - 24 - buttonGap) / 2);
            this.confirmationField = this.addButton(new TextFieldWidget(
                    MinecraftClient.getInstance().textRenderer,
                    left + 12, top + 177, panelWidth - 24, 20,
                    "Enter confirmation code..."));
            this.confirmationField.setMaxLength(9);
            MsmButtonWidget confirm = new MsmButtonWidget(
                    left + 12, top + 211, buttonWidth, 20,
                    "CONFIRM", button -> confirmRemoval());
            confirm.setDanger(true);
            confirm.active = false;
            this.confirmButton = this.addButton(confirm);
            this.addButton(new MsmButtonWidget(
                    left + 12 + buttonWidth + buttonGap, top + 211,
                    buttonWidth, 20, "CANCEL", button -> onClose()));
            return;
        }
        int panelWidth = Math.min(360, Math.max(220, this.width - 32));
        int left = (this.width - panelWidth) / 2;
        int buttonGap = 8;
        int buttonWidth = Math.max(1, (panelWidth - buttonGap - 24) / 2);
        int buttonLeft = left + 12;

        this.confirmButton = this.addButton(new MsmButtonWidget(
                buttonLeft, this.height / 2 + 28, buttonWidth, 20,
                this.accountRemoval ? "DELETE ACCOUNT" : "REMOVE SERVER",
                button -> confirmRemoval()));
        ((MsmButtonWidget) this.confirmButton).setDanger(true);

        this.addButton(new MsmButtonWidget(
                buttonLeft + buttonWidth + buttonGap, this.height / 2 + 28,
                buttonWidth, 20, "CANCEL", button -> onClose()));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.confirmationField != null) this.confirmationField.tick();
        if (this.accountRemoval && this.confirmButton != null) {
            this.confirmButton.active = !this.removing && isConfirmationCodeValid();
        }
    }

    private boolean isConfirmationCodeValid() {
        return this.confirmationField != null
                && this.confirmationCode.equalsIgnoreCase(this.confirmationField.getText().trim());
    }

    private static String createConfirmationCode() {
        StringBuilder code = new StringBuilder(9);
        for (int index = 0; index < 8; index++) {
            if (index == 4) code.append('-');
            code.append(CODE_CHARACTERS[RANDOM.nextInt(CODE_CHARACTERS.length)]);
        }
        return code.toString();
    }

    private void confirmRemoval() {
        if (this.removing || (this.accountRemoval && !isConfirmationCodeValid())) return;
        this.removing = true;
        if (this.confirmButton != null) {
            this.confirmButton.active = false;
            this.confirmButton.setMessage("REMOVING...");
        }
        this.onConfirm.run();
    }

    @Override
    public void onClose() {
        MinecraftClient.getInstance().openScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void drawTrashCan(int center, int top) {
        int color = 0xFFFF6675;
        fill(center - 3, top, center + 3, top + 2, color);
        fill(center - 8, top + 3, center + 8, top + 5, color);
        fill(center - 6, top + 6, center - 4, top + 19, color);
        fill(center + 4, top + 6, center + 6, top + 19, color);
        fill(center - 4, top + 19, center + 4, top + 21, color);
        for (int offset = 0; offset < 10; offset++) {
            fill(center - 4 + offset, top + 7 + offset,
                    center - 2 + offset, top + 9 + offset, color);
            fill(center + 2 - offset, top + 7 + offset,
                    center + 4 - offset, top + 9 + offset, color);
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        int panelWidth;
        int left;
        int top;
        if (this.accountRemoval) {
            if (this.parent instanceof SessionManagerScreen) {
                ((SessionManagerScreen) this.parent).render(mouseX, mouseY, delta);
            } else {
                this.renderMsmBackground();
            }
            fill(0, 0, this.width, this.height, 0x99070B12);
            panelWidth = Math.max(1, Math.min(380, this.width - 24));
            left = (this.width - panelWidth) / 2;
            top = this.height / 2 - 125;
            drawGlassPanel(left, top, left + panelWidth, top + 250);
            drawTrashCan(this.width / 2, top + 10);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    "DELETE ACCOUNT", this.width / 2, top + 40, 0xFFFF8792);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    this.username, this.width / 2, top + 66, 0xFFEAF0F8);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    "Delete this account and all its", this.width / 2, top + 90, 0xFFE1E7EF);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    "configurations permanently.", this.width / 2, top + 105, 0xFFE1E7EF);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    "To confirm, enter this code:", this.width / 2, top + 132, 0xFF9EADBD);
            drawGlassPanel(left + 12, top + 145, left + panelWidth - 12, top + 170);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    this.confirmationCode, this.width / 2, top + 151, 0xFFFF8792);
            drawGlassPanel(left + 12, top + 177, left + panelWidth - 12, top + 197);
        } else {
            this.renderMsmBackground();
            panelWidth = Math.min(360, Math.max(220, this.width - 32));
            left = (this.width - panelWidth) / 2;
            top = this.height / 2 - 62;
            drawGlassPanel(left, top, left + panelWidth, this.height / 2 + 62);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    "REMOVE SERVER?", this.width / 2, top + 16, 0xFFF1F5FA);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    this.username, this.width / 2, top + 37, 0xFF6FC4DE);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    fitText(this.serverAddress, panelWidth - 28),
                    this.width / 2, top + 52, 0xFFB3BFCD);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    "This server's AutoChat and AutoReconnect",
                    this.width / 2, top + 70, 0xFF9EADBD);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    "settings will be deleted.",
                    this.width / 2, top + 82, 0xFF9EADBD);
        }
        super.render(mouseX, mouseY, delta);
    }

    private String fitText(String value, int pixelWidth) {
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }
}
