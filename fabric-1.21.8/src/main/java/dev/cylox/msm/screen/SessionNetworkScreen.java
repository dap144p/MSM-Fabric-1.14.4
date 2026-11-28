package dev.cylox.msm.screen;

import dev.cylox.msm.config.LocalAddressCatalog;
import dev.cylox.msm.config.ProxyProfile;
import dev.cylox.msm.config.SessionNetworkSettings;
import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SessionNetworkScreen extends MsmScreen {
    private final String username;
    private final SessionAuthMode authMode;
    private final SecondarySessionController controller;
    private final List<String> availableAddresses = new ArrayList<>();

    private String selectedAddress = "";
    private String selectedProxyProfileId = "";
    private List<ProxyProfile> availableProxies = new ArrayList<>();
    private MsmButtonWidget proxyButton;
    private String status = "Choose an IP assigned to this device, or use Automatic.";
    private MsmButtonWidget outgoingButton;

    public SessionNetworkScreen(Screen parent, String username, SessionAuthMode authMode,
                                SecondarySessionController controller) {
        super(Text.literal("Custom Outgoing IP"), parent);
        if (username == null || authMode == null || controller == null) {
            throw new IllegalArgumentException("Account and network controller are required.");
        }
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
        refreshAddressCatalog(false);
    }

    @Override
    protected void init() {
        int panelWidth = Math.max(1, Math.min(560, this.width - 24));
        int left = Math.max(12, (this.width - panelWidth) / 2);
        int right = left + panelWidth;
        int mainWidth = Math.max(1, panelWidth - 96);
        int mainLeft = left + 12;
        int refreshWidth = Math.min(78, Math.max(58, panelWidth - mainWidth - 30));

        this.outgoingButton = this.addButton(new MsmButtonWidget(
                mainLeft, 88, mainWidth, 22, getOutgoingLabel(),
                button -> cycleOutgoingAddress()));
        this.addButton(new MsmButtonWidget(
                mainLeft + mainWidth + 6, 88, refreshWidth, 22,
                "REFRESH", button -> refreshAddressCatalog(true)));
        this.proxyButton = this.addButton(new MsmButtonWidget(
                mainLeft, 116, mainWidth, 22, getProxyLabel(),
                button -> cycleProxyProfile()));
        this.addButton(new MsmButtonWidget(
                mainLeft + mainWidth + 6, 116, refreshWidth, 22,
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
                button -> close()));
    }

    private String getOutgoingLabel() {
        if (this.selectedAddress.isEmpty()) return "OUTGOING IP: AUTOMATIC";
        if (!this.availableAddresses.contains(this.selectedAddress)) {
            return "OUTGOING IP: UNAVAILABLE";
        }
        return "OUTGOING IP: " + this.selectedAddress;
    }

    private void cycleOutgoingAddress() {
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(this.availableAddresses);
        int current = options.indexOf(this.selectedAddress);
        this.selectedAddress = options.get((current + 1 + options.size()) % options.size());
        if (this.outgoingButton != null) {
            this.outgoingButton.setMessage(getOutgoingLabel());
        }
        this.status = this.selectedAddress.isEmpty()
                ? "Automatic source IP selection is enabled."
                : "Selected " + this.selectedAddress + ". Save to apply on the next connection.";
    }

    private void refreshAddressCatalog(boolean updateStatus) {
        try {
            this.availableAddresses.clear();
            this.availableAddresses.addAll(LocalAddressCatalog.getAvailableAddresses());
            if (updateStatus) {
                this.status = this.availableAddresses.isEmpty()
                        ? "No eligible local IP addresses were found."
                        : "Found " + this.availableAddresses.size() + " local IP address(es).";
            }
        } catch (SocketException exception) {
            this.status = "Could not inspect local network interfaces: " + exception.getMessage();
        }
        if (this.outgoingButton != null) {
            this.outgoingButton.setMessage(getOutgoingLabel());
        }
    }

    private String getProxyLabel() {
        if (this.selectedProxyProfileId.isEmpty()) return "PROXY: DIRECT";
        for (ProxyProfile proxy : this.availableProxies) {
            if (proxy.getId().equals(this.selectedProxyProfileId)) return "PROXY: " + proxy.getName();
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
        this.client.setScreen(new SessionProxyManagerScreen(this, this.controller));
    }

    private void saveSettings() {
        try {
            if (!this.selectedAddress.isEmpty()) {
                LocalAddressCatalog.resolveAvailableAddress(this.selectedAddress);
            }
            this.controller.getNetworkStore().save(this.username, this.authMode,
                    new SessionNetworkSettings(this.selectedAddress, this.selectedProxyProfileId));
            this.status = "Saved for " + this.username
                    + ". Reconnect this account's servers to apply the new source IP.";
        } catch (Exception exception) {
            this.status = "Could not save outgoing IP: " + exception.getMessage();
        }
    }

    @Override
    public void tick() {
        super.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderMsmBackground(context, mouseX, mouseY, delta);
        int panelWidth = Math.max(1, Math.min(560, this.width - 24));
        int left = Math.max(12, (this.width - panelWidth) / 2);
        int right = left + panelWidth;
        this.drawGlassPanel(context, left, 42, right, this.height - 44);

        this.centered(context, "CUSTOM OUTGOING IP", this.width / 2, 52, 0xFFE7EDF5);
        this.text(context, "ACCOUNT: " + this.username + "  |  " + this.authMode.name(),
                left + 14, 70, 0xFF9EADBD);
        this.drawSectionTitle(context, "SOURCE IP FOR NEW CONNECTIONS", left + 14, 147);
        this.text(context, "AUTO lets the operating system choose the source interface.",
                left + 14, 164, 0xFF8492A3);
        this.text(context, "Only addresses assigned to this device are listed.",
                left + 14, 177, 0xFF8492A3);
        this.drawGlassDivider(context, left + 14, 194, right - 14);

        this.drawSectionTitle(context, "ACTUAL LOCAL IP", left + 14, 206);
        List<SessionManager.Session> sessions = matchingSessions();
        if (sessions.isEmpty()) {
            this.text(context, "No saved servers for this account yet.",
                    left + 14, 225, 0xFF9EADBD);
        } else {
            int y = 225;
            int availableRows = Math.max(1, (this.height - 278) / 14);
            int shown = 0;
            for (SessionManager.Session session : sessions) {
                if (shown >= availableRows) break;
                String actual = this.controller.getLocalAddress(session.getId());
                String detail = actual.isEmpty()
                        ? (session.getState() == SessionState.CONNECTING ? "CONNECTING" : "NOT CONNECTED")
                        : actual;
                this.text(context, fit(session.getProfile().getServerAddress()
                        + "  |  " + detail, panelWidth - 28), left + 14, y,
                        actual.isEmpty() ? 0xFF8492A3 : 0xFF75C995);
                y += 14;
                shown++;
            }
            if (sessions.size() > shown) {
                this.text(context, (sessions.size() - shown) + " more server(s) not shown.",
                        left + 14, y, 0xFF8492A3);
            }
        }

        this.renderMsmFooter(context, this.status, 0);
        super.render(context, mouseX, mouseY, delta);
    }

    private List<SessionManager.Session> matchingSessions() {
        List<SessionManager.Session> matches = new ArrayList<>();
        for (SessionManager.Session session : this.controller.getSessionManager().getSessions()) {
            if (session.getProfile().getUsername().equalsIgnoreCase(this.username)
                    && session.getProfile().getAuthMode() == this.authMode) {
                matches.add(session);
            }
        }
        return matches;
    }

    @Override
    protected String fit(String value, int pixelWidth) {
        return value == null ? "" : this.textRenderer.trimToWidth(value, Math.max(1, pixelWidth));
    }
}
