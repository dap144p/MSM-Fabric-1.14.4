package dev.cylox.msm.screen;

import dev.cylox.msm.config.ProxyProfile;
import dev.cylox.msm.config.SessionNetworkSettings;
import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.profile.SessionAuthMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.LiteralText;

import java.util.ArrayList;
import java.util.List;

public final class SessionNetworkScreen extends MsmScreen {
    private final Screen parent;
    private final String username;
    private final SessionAuthMode authMode;
    private final SecondarySessionController controller;
    private String selectedAddress = "";
    private String selectedProxyProfileId = "";
    private List<ProxyProfile> availableProxies = new ArrayList<>();
    private MsmButtonWidget proxyButton;
    private String status = "Select a proxy profile for this account, or use Direct Connection.";

    public SessionNetworkScreen(Screen parent, String username, SessionAuthMode authMode,
                                SecondarySessionController controller) {
        super(new LiteralText("Proxy Per Account"));
        if (username == null || authMode == null || controller == null) {
            throw new IllegalArgumentException("Account and network controller are required.");
        }
        this.parent = parent;
        this.username = username;
        this.authMode = authMode;
        this.controller = controller;
        try {
            SessionNetworkSettings settings = controller.getNetworkStore().load(username, authMode);
            this.selectedAddress = settings.getOutgoingAddress();
            this.selectedProxyProfileId = settings.getProxyProfileId();
            this.availableProxies.addAll(controller.getProxyProfileStore().load());
        } catch (Exception exception) {
            this.status = "Could not load network settings: " + exception.getMessage();
        }
    }

    @Override
    protected void init() {
        int panelWidth = Math.max(1, Math.min(560, this.width - 24));
        int left = Math.max(12, (this.width - panelWidth) / 2);
        int mainWidth = Math.max(1, panelWidth - 96);
        int mainLeft = left + 12;
        int manageWidth = Math.min(78, Math.max(58, panelWidth - mainWidth - 30));

        this.proxyButton = this.addButton(new MsmButtonWidget(
                mainLeft, 104, mainWidth, 22, getProxyLabel(),
                button -> cycleProxyProfile()));
        this.addButton(new MsmButtonWidget(
                mainLeft + mainWidth + 6, 104, manageWidth, 22,
                "MANAGE", button -> openProxyManager()));

        int footerY = this.height - 28;
        int footerWidth = Math.min(190, Math.max(1, panelWidth - 12));
        int saveWidth = Math.max(1, footerWidth / 2 - 3);
        int footerLeft = left + 6;
        this.addButton(new MsmButtonWidget(
                footerLeft, footerY, saveWidth, 20, "SAVE",
                button -> saveSettings()));
        this.addButton(new MsmButtonWidget(
                footerLeft + saveWidth + 6, footerY,
                footerWidth - saveWidth - 6, 20, "BACK",
                button -> onClose()));
    }

    private String getProxyLabel() {
        if (this.selectedProxyProfileId.isEmpty()) return "PROXY: DIRECT";
        for (ProxyProfile proxy : this.availableProxies) {
            if (proxy.getId().equals(this.selectedProxyProfileId)) {
                return "PROXY: " + proxy.getName();
            }
        }
        return "PROXY: UNAVAILABLE";
    }

    private void cycleProxyProfile() {
        try {
            this.availableProxies = new ArrayList<>(this.controller.getProxyProfileStore().load());
            List<String> options = new ArrayList<>();
            options.add("");
            for (ProxyProfile proxy : this.availableProxies) options.add(proxy.getId());
            int current = options.indexOf(this.selectedProxyProfileId);
            this.selectedProxyProfileId = options.get((current + 1 + options.size()) % options.size());
            this.status = this.selectedProxyProfileId.isEmpty()
                    ? "Direct Connection selected."
                    : "Proxy selected. Save and reconnect this account to apply it.";
        } catch (Exception exception) {
            this.status = "Could not load proxy profiles: " + exception.getMessage();
        }
        if (this.proxyButton != null) this.proxyButton.setMessage(getProxyLabel());
    }

    private void openProxyManager() {
        MinecraftClient.getInstance().openScreen(new SessionProxyManagerScreen(this, this.controller));
    }

    private void saveSettings() {
        try {
            this.controller.getNetworkStore().save(this.username, this.authMode,
                    new SessionNetworkSettings(this.selectedAddress, this.selectedProxyProfileId));
            this.status = "Saved proxy for " + this.username
                    + ". Reconnect this account's servers to apply it.";
        } catch (Exception exception) {
            this.status = "Could not save proxy selection: " + exception.getMessage();
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderMsmBackground();
        int panelWidth = Math.max(1, Math.min(560, this.width - 24));
        int left = Math.max(12, (this.width - panelWidth) / 2);
        int right = left + panelWidth;
        this.drawGlassPanel(left, 42, right, this.height - 44);

        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                "PROXY PER ACCOUNT", this.width / 2, 52, 0xFFE7EDF5);
        drawString(MinecraftClient.getInstance().textRenderer,
                "ACCOUNT: " + this.username + "  |  " + this.authMode.name(),
                left + 14, 70, 0xFF9EADBD);
        this.drawSectionTitle("PROXY FOR THIS ACCOUNT", left + 14, 137);
        drawString(MinecraftClient.getInstance().textRenderer,
                "Select a proxy profile for this account's secondary sessions.",
                left + 14, 155, 0xFF8492A3);
        drawString(MinecraftClient.getInstance().textRenderer,
                "Direct Connection, SOCKS5, and HTTP CONNECT are supported.",
                left + 14, 170, 0xFF8492A3);
        this.drawGlassDivider(left + 14, 188, right - 14);

        this.renderMsmFooter(this.status, 0);
        super.render(mouseX, mouseY, delta);
    }


}
