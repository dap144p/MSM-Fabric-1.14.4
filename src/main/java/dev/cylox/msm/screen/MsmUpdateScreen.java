package dev.cylox.msm.screen;

import dev.cylox.msm.update.MsmUpdateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.LiteralText;

public final class MsmUpdateScreen extends MsmScreen {
    private final Screen parent;
    private final MsmUpdateManager updateManager;

    private MsmButtonWidget channelButton;
    private MsmButtonWidget checkButton;
    private MsmButtonWidget downloadButton;
    private MsmButtonWidget backButton;
    private MsmUpdateManager.UpdateInfo availableUpdate;
    private String status = "Checking the selected release channel...";

    public MsmUpdateScreen(Screen parent, MsmUpdateManager updateManager) {
        super(new LiteralText("MSM Updates"));
        this.parent = parent;
        this.updateManager = updateManager;
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(280, Math.max(1, this.width - 24));
        int left = (this.width - buttonWidth) / 2;
        int centerY = this.height / 2;

        this.channelButton = this.addButton(new MsmButtonWidget(
                left, centerY - 28, buttonWidth, 20, getChannelLabel(),
                button -> cycleChannel()));
        this.checkButton = this.addButton(new MsmButtonWidget(
                left, centerY - 2, buttonWidth, 20, "CHECK AGAIN",
                button -> checkForUpdates()));
        this.downloadButton = this.addButton(new MsmButtonWidget(
                left, centerY + 24, buttonWidth, 20, "DOWNLOAD JAR",
                button -> downloadUpdate()));
        this.backButton = this.addButton(new MsmButtonWidget(
                this.width - 96, this.height - 28, 84, 20, "BACK",
                button -> onClose()));

        this.downloadButton.active = false;
        checkForUpdates();
    }

    private String getChannelLabel() {
        return "CHANNEL: " + this.updateManager.getChannel().getLabel() + "  (CHANGE)";
    }

    private void cycleChannel() {
        try {
            this.updateManager.cycleChannel();
            this.channelButton.setMessage(getChannelLabel());
            checkForUpdates();
        } catch (Exception exception) {
            this.status = "Could not save update channel: " + safeMessage(exception);
        }
    }

    private void checkForUpdates() {
        this.availableUpdate = null;
        this.status = "Checking " + this.updateManager.getChannel().getLabel() + " releases...";
        this.checkButton.active = false;
        this.channelButton.active = false;
        this.downloadButton.active = false;
        this.downloadButton.setMessage("DOWNLOAD JAR");

        this.updateManager.checkForUpdates(result ->
                MinecraftClient.getInstance().execute(() -> {
                    this.availableUpdate = result.update;
                    this.status = result.message;
                    this.checkButton.active = true;
                    this.channelButton.active = true;
                    this.downloadButton.active = result.isUpdateAvailable();
                }));
    }

    private void downloadUpdate() {
        if (this.availableUpdate == null) return;
        this.status = "Downloading and verifying SHA-256...";
        this.downloadButton.active = false;

        this.updateManager.download(this.availableUpdate, result ->
                MinecraftClient.getInstance().execute(() -> {
                    if (MinecraftClient.getInstance().currentScreen != this) return;
                    this.status = result.message;
                    this.downloadButton.setMessage(
                            result.isSuccessful() ? "DOWNLOADED" : "RETRY DOWNLOAD");
                    this.downloadButton.active = !result.isSuccessful();
                }));
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName() : message;
    }

    @Override
    public void onClose() {
        MinecraftClient.getInstance().openScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderMsmBackground();
        this.drawGlassDivider(12, 28, this.width - 12);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                "MSM UPDATER", this.width / 2, 11, 0xFFF1F5FA);

        int centerY = this.height / 2;
        int panelWidth = Math.min(300, Math.max(1, this.width - 16));
        int panelLeft = (this.width - panelWidth) / 2;
        this.drawGlassPanel(panelLeft, centerY - 76,
                panelLeft + panelWidth, centerY + 54);

        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                "INSTALLED  " + this.updateManager.getInstalledVersion(),
                this.width / 2, centerY - 67, 0xFFE7EDF5);
        String target = this.availableUpdate == null
                ? "MINECRAFT  " + this.updateManager.getMinecraftVersion()
                : "AVAILABLE  " + this.availableUpdate.version;
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                target, this.width / 2, centerY - 52, 0xFF9EADBD);

        renderMsmFooter(this.status, 108);
        super.render(mouseX, mouseY, delta);
    }
}
