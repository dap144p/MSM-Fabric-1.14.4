package dev.cylox.msm.screen;

import dev.cylox.msm.MsmClient;
import dev.cylox.msm.network.NetworkInterfaceAddress;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class SessionNetworkScreen extends MsmScreen {
    private final long sessionId;
    private final SessionProfile profile;
    private final String initialBinding;
    private List<NetworkInterfaceAddress> localAddresses = Collections.emptyList();
    private TextFieldWidget bindingField;
    private String status = "";
    private boolean compactLayout;

    public SessionNetworkScreen(Screen parent, long sessionId) {
        super(Text.literal("Network Settings"), parent);
        this.sessionId = sessionId;

        Optional<SessionManager.Session> selected = MsmClient.getSessionManager().find(sessionId);
        this.profile = selected.isPresent() ? selected.get().getProfile() : null;

        String savedBinding = "";
        if (this.profile != null) {
            try {
                savedBinding = MsmClient.getNetworkSettingsStore().loadBindAddress(this.profile);
            } catch (IOException exception) {
                this.status = "Could not load network settings: " + exception.getMessage();
            }
        } else {
            this.status = "This account is no longer available.";
        }
        this.initialBinding = savedBinding;
        refreshLocalAddresses();
    }

    @Override
    protected void init() {
        this.clearChildren();
        this.compactLayout = this.width <= this.height;

        int margin = this.compactLayout ? 10 : 16;
        int contentLeft = margin;
        int contentWidth = Math.max(1, this.width - margin * 2);
        int innerLeft = contentLeft + 12;
        int innerWidth = Math.max(1, contentWidth - 24);

        this.bindingField = this.addField(
                innerLeft, 88, innerWidth, 20, "Blank = automatic system routing");
        this.bindingField.setMaxLength(64);
        this.bindingField.setText(this.initialBinding);

        if (this.compactLayout) {
            int actionGap = 6;
            int actionWidth = Math.max(1, (contentWidth - actionGap) / 2);
            int firstRowY = this.height - 76;
            int secondRowY = this.height - 50;
            this.addButton(new MsmButtonWidget(
                    contentLeft, firstRowY, actionWidth, 20,
                    "AUTO", button -> useAutomaticRouting()));
            this.addButton(new MsmButtonWidget(
                    contentLeft + actionWidth + actionGap, firstRowY,
                    contentWidth - actionWidth - actionGap, 20,
                    "NEXT IP", button -> selectNextAddress()));
            this.addButton(new MsmButtonWidget(
                    contentLeft, secondRowY, actionWidth, 20,
                    "SAVE", button -> saveBinding()));
            this.addButton(new MsmButtonWidget(
                    contentLeft + actionWidth + actionGap, secondRowY,
                    contentWidth - actionWidth - actionGap, 20,
                    "BACK", button -> close()));
        } else {
            int actionGap = 6;
            int actionWidth = Math.max(1, (contentWidth - actionGap * 3) / 4);
            int actionY = this.height - 28;
            this.addButton(new MsmButtonWidget(
                    contentLeft, actionY, actionWidth, 20,
                    "AUTO", button -> useAutomaticRouting()));
            this.addButton(new MsmButtonWidget(
                    contentLeft + actionWidth + actionGap, actionY, actionWidth, 20,
                    "NEXT IP", button -> selectNextAddress()));
            this.addButton(new MsmButtonWidget(
                    contentLeft + (actionWidth + actionGap) * 2, actionY,
                    actionWidth, 20, "SAVE", button -> saveBinding()));
            this.addButton(new MsmButtonWidget(
                    contentLeft + (actionWidth + actionGap) * 3, actionY,
                    contentWidth - (actionWidth + actionGap) * 3, 20,
                    "BACK", button -> close()));
        }
    }

    private void refreshLocalAddresses() {
        try {
            this.localAddresses = NetworkInterfaceAddress.discover();
        } catch (SocketException exception) {
            this.localAddresses = Collections.emptyList();
            this.status = "Could not inspect local interfaces: " + exception.getMessage();
        }
    }

    private void useAutomaticRouting() {
        if (this.bindingField != null) this.bindingField.setText("");
        this.status = "Automatic routing selected. Press SAVE to apply it to this account.";
    }

    private void selectNextAddress() {
        if (this.localAddresses.isEmpty()) refreshLocalAddresses();
        if (this.localAddresses.isEmpty()) {
            this.status = "No active local IP addresses were detected.";
            return;
        }

        String current = this.bindingField == null ? "" : this.bindingField.getText().trim();
        int next = 0;
        for (int index = 0; index < this.localAddresses.size(); index++) {
            if (this.localAddresses.get(index).getAddress().equalsIgnoreCase(current)) {
                next = (index + 1) % this.localAddresses.size();
                break;
            }
        }

        NetworkInterfaceAddress selected = this.localAddresses.get(next);
        this.bindingField.setText(selected.getAddress());
        this.status = "Selected " + selected.getInterfaceName() + ". Press SAVE to apply.";
    }

    private void saveBinding() {
        if (this.profile == null || this.bindingField == null) {
            this.status = "This account is no longer available.";
            return;
        }

        String binding = this.bindingField.getText() == null
                ? "" : this.bindingField.getText().trim();
        try {
            if (!binding.isEmpty()) {
                binding = NetworkInterfaceAddress.resolveLocalAddress(binding).getHostAddress();
            }
            MsmClient.getNetworkSettingsStore().saveBindAddress(this.profile, binding);
            SessionState state = MsmClient.getSessionManager().find(this.sessionId)
                    .map(SessionManager.Session::getState).orElse(SessionState.FAILED);
            if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
                this.status = "Saved. Disconnect and reconnect this account to apply the new IP.";
            } else if (binding.isEmpty()) {
                this.status = "Automatic routing saved for " + this.profile.getUsername() + ".";
            } else {
                this.status = "Outgoing IP " + binding + " saved for " + this.profile.getUsername() + ".";
            }

            if (this.parent instanceof SessionManagerScreen) {
                ((SessionManagerScreen) this.parent).setStatus(
                        "Network settings saved for " + this.profile.getUsername() + ".");
            }
        } catch (IOException | IllegalArgumentException exception) {
            this.status = "Could not save network settings: " + exception.getMessage();
        }
    }

    private String getActualLocalIp() {
        String active = MsmClient.getSecondarySessions().getActiveLocalAddress(this.sessionId);
        if (!active.isEmpty()) return active;

        Optional<SessionManager.Session> selected =
                MsmClient.getSessionManager().find(this.sessionId);
        if (selected.isPresent() && selected.get().getState() == SessionState.CONNECTING) {
            return "Waiting for the TCP socket...";
        }
        return "Not connected";
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    @Override
    public void tick() {
        super.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderMsmBackground(context, mouseX, mouseY, delta);
        this.drawGlassDivider(context, 12, 28, this.width - 12);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("ACCOUNT NETWORK SETTINGS"), this.width / 2, 11, 0xFFF1F5FA);

        int margin = this.compactLayout ? 10 : 16;
        int contentLeft = margin;
        int contentWidth = Math.max(1, this.width - margin * 2);
        int mainBottom = this.height - (this.compactLayout ? 90 : 48);
        this.drawGlassPanel(context, contentLeft, 36,
                contentLeft + contentWidth, mainBottom);
        if (this.compactLayout) {
            this.drawGlassPanel(context, 10, this.height - 82,
                    this.width - 10, this.height - 8);
        } else {
            this.drawGlassPanel(context, 16, this.height - 48,
                    this.width - 16, this.height - 8);
        }

        String username = this.profile == null ? "Unavailable"
                : this.profile.getUsername();
        this.drawSectionTitle(context, "ACCOUNT", contentLeft + 12, 49);
        this.text(context, fit(username, contentWidth - 24),
                contentLeft + 12, 63, 0xFFE7EDF5);

        this.drawSectionTitle(context, "OUTGOING SOURCE IP  (BLANK = AUTO)", contentLeft + 12, 76);

        this.drawSectionTitle(context, "AVAILABLE LOCAL INTERFACES", contentLeft + 12, 120);
        int actualY = Math.max(190, Math.min(this.height - 116, 270));
        int listY = 138;
        int maxLines = Math.max(0, (actualY - listY - 8) / 13);
        if (this.localAddresses.isEmpty()) {
            this.text(context, "No active non-loopback local IP addresses detected.",
                    contentLeft + 12, listY, 0xFF9EADBD);
        } else {
            int visible = Math.min(this.localAddresses.size(), maxLines);
            for (int index = 0; index < visible; index++) {
                NetworkInterfaceAddress local = this.localAddresses.get(index);
                this.text(context, fit(local.getInterfaceName() + "  " + local.getAddress(),
                                contentWidth - 24),
                        contentLeft + 12, listY + index * 13, 0xFFB8C4D3);
            }
            if (this.localAddresses.size() > visible && visible > 0) {
                this.text(context, "+" + (this.localAddresses.size() - visible)
                                + " more. Use NEXT IP to cycle.",
                        contentLeft + 12, listY + visible * 13, 0xFF8FA8C7);
            }
        }

        String actual = "ACTUAL SOCKET LOCAL IP: " + getActualLocalIp();
        this.text(context, fit(actual, contentWidth - 24),
                contentLeft + 12, actualY, 0xFFE7EDF5);
        this.text(context, fit(this.status, contentWidth - 24),
                contentLeft + 12, actualY + 16, 0xFF9EADBD);

        super.render(context, mouseX, mouseY, delta);
    }
}
