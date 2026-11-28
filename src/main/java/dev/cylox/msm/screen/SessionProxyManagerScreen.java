package dev.cylox.msm.screen;

import dev.cylox.msm.config.ProxyEndpointTester;
import dev.cylox.msm.config.ProxyProfile;
import dev.cylox.msm.network.SecondarySessionController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.LiteralText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class SessionProxyManagerScreen extends MsmScreen {
    private final Screen parent;
    private final SecondarySessionController controller;
    private final List<ProxyProfile> profiles = new ArrayList<ProxyProfile>();
    private String selectedId = "";
    private ProxyProfile.Type selectedType = ProxyProfile.Type.SOCKS5;
    private volatile String status = "Proxy profiles are stored locally; credentials are not encrypted.";
    private TextFieldWidget nameField;
    private TextFieldWidget hostField;
    private TextFieldWidget portField;
    private TextFieldWidget usernameField;
    private TextFieldWidget passwordField;
    private MsmButtonWidget profileButton;
    private MsmButtonWidget typeButton;

    public SessionProxyManagerScreen(Screen parent, SecondarySessionController controller) {
        super(new LiteralText("Proxy Manager"));
        if (controller == null) throw new IllegalArgumentException("Network controller is required.");
        this.parent = parent;
        this.controller = controller;
        refreshProfiles();
    }

    @Override
    protected void init() {
        int panelWidth = Math.max(1, Math.min(560, this.width - 24));
        int left = Math.max(12, (this.width - panelWidth) / 2);
        int fieldLeft = left + 12;
        int fieldWidth = panelWidth - 24;
        this.nameField = this.addButton(new TextFieldWidget(MinecraftClient.getInstance().textRenderer, fieldLeft, 70, fieldWidth, 20, "Profile name"));
        this.nameField.setMaxLength(32);
        this.hostField = this.addButton(new TextFieldWidget(MinecraftClient.getInstance().textRenderer, fieldLeft, 96, fieldWidth, 20, "Proxy host"));
        this.hostField.setMaxLength(253);
        this.portField = this.addButton(new TextFieldWidget(MinecraftClient.getInstance().textRenderer, fieldLeft, 122, fieldWidth, 20, "Port"));
        this.portField.setMaxLength(5);
        this.usernameField = this.addButton(new TextFieldWidget(MinecraftClient.getInstance().textRenderer, fieldLeft, 148, fieldWidth, 20, "Username (optional)"));
        this.usernameField.setMaxLength(128);
        this.passwordField = this.addButton(new TextFieldWidget(MinecraftClient.getInstance().textRenderer, fieldLeft, 174, fieldWidth, 20, "Password (optional)"));
        this.passwordField.setMaxLength(128);
        this.typeButton = this.addButton(new MsmButtonWidget(
                fieldLeft, 204, 126, 20, getTypeLabel(), button -> cycleType()));
        this.profileButton = this.addButton(new MsmButtonWidget(
                fieldLeft + 132, 204, fieldWidth - 132, 20, getProfileLabel(),
                button -> cycleProfile()));
        int buttonY = 232;
        int saveWidth = Math.max(1, fieldWidth / 3 - 4);
        int testWidth = saveWidth;
        int deleteWidth = Math.max(1, fieldWidth - saveWidth - testWidth - 12);
        this.addButton(new MsmButtonWidget(fieldLeft, buttonY, saveWidth, 20,
                "SAVE", button -> saveProfile()));
        this.addButton(new MsmButtonWidget(fieldLeft + saveWidth + 6, buttonY, testWidth, 20,
                "TEST", button -> testEndpoint()));
        this.addButton(new MsmButtonWidget(fieldLeft + saveWidth + testWidth + 12,
                buttonY, deleteWidth, 20, "DELETE", button -> deleteProfile()));
        this.addButton(new MsmButtonWidget(left + 12, this.height - 28, 96, 20,
                "BACK", button -> onClose()));
    }

    private String getTypeLabel() {
        return this.selectedType == ProxyProfile.Type.SOCKS5 ? "TYPE: SOCKS5" : "TYPE: HTTP CONNECT";
    }

    private String getProfileLabel() {
        if (this.selectedId.isEmpty()) return "PROFILE: NEW";
        for (ProxyProfile profile : this.profiles) {
            if (profile.getId().equals(this.selectedId)) return "PROFILE: " + profile.getName();
        }
        return "PROFILE: NEW";
    }

    private void refreshProfiles() {
        try {
            this.profiles.clear();
            this.profiles.addAll(this.controller.getProxyProfileStore().load());
        } catch (IOException exception) {
            this.status = "Could not load proxy profiles: " + exception.getMessage();
        }
    }

    private void cycleType() {
        this.selectedType = this.selectedType == ProxyProfile.Type.SOCKS5
                ? ProxyProfile.Type.HTTP_CONNECT : ProxyProfile.Type.SOCKS5;
        this.typeButton.setMessage(getTypeLabel());
    }

    private void cycleProfile() {
        if (this.profiles.isEmpty()) {
            clearForm();
            return;
        }
        int current = -1;
        for (int i = 0; i < this.profiles.size(); i++) {
            if (this.profiles.get(i).getId().equals(this.selectedId)) current = i;
        }
        int next = (current + 1) % (this.profiles.size() + 1);
        if (next == this.profiles.size()) {
            clearForm();
        } else {
            loadProfile(this.profiles.get(next));
        }
    }

    private void loadProfile(ProxyProfile profile) {
        this.selectedId = profile.getId();
        this.selectedType = profile.getType();
        this.nameField.setText(profile.getName());
        this.hostField.setText(profile.getHost());
        this.portField.setText(Integer.toString(profile.getPort()));
        this.usernameField.setText(profile.getUsername());
        this.passwordField.setText(profile.getPassword());
        this.typeButton.setMessage(getTypeLabel());
        this.profileButton.setMessage(getProfileLabel());
        this.status = "Editing " + profile.getName() + ".";
    }

    private void clearForm() {
        this.selectedId = "";
        this.selectedType = ProxyProfile.Type.SOCKS5;
        this.nameField.setText("");
        this.hostField.setText("");
        this.portField.setText("");
        this.usernameField.setText("");
        this.passwordField.setText("");
        this.typeButton.setMessage(getTypeLabel());
        this.profileButton.setMessage(getProfileLabel());
        this.status = "New proxy profile.";
    }

    private ProxyProfile buildProfile() {
        String portText = this.portField.getText().trim();
        int port = Integer.parseInt(portText);
        return new ProxyProfile(this.selectedId.isEmpty()
                ? java.util.UUID.randomUUID().toString() : this.selectedId,
                this.nameField.getText(), this.selectedType, this.hostField.getText(),
                port, this.usernameField.getText(), this.passwordField.getText());
    }

    private void saveProfile() {
        try {
            ProxyProfile updated = buildProfile();
            List<ProxyProfile> next = new ArrayList<ProxyProfile>(this.profiles);
            boolean replaced = false;
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i).getId().equals(updated.getId())) {
                    next.set(i, updated);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) next.add(updated);
            this.controller.getProxyProfileStore().save(next);
            this.selectedId = updated.getId();
            refreshProfiles();
            this.profileButton.setMessage(getProfileLabel());
            this.status = "Saved " + updated.getName() + ". Credentials are stored locally without encryption.";
        } catch (Exception exception) {
            this.status = "Could not save proxy profile: " + exception.getMessage();
        }
    }

    private void testEndpoint() {
        try {
            final ProxyProfile profile = buildProfile();
            this.status = "Checking TCP reachability of " + profile.getHost() + ":" + profile.getPort() + "...";
            new Thread(() -> {
                String result;
                try {
                    ProxyEndpointTester.testEndpoint(profile, 5000);
                    result = "Proxy endpoint is reachable. Authentication and tunnel were not tested.";
                } catch (Exception exception) {
                    result = "Proxy endpoint test failed: " + exception.getMessage();
                }
                final String message = result;
                MinecraftClient.getInstance().execute(() -> this.status = message);
            }, "MSM-Proxy-Test").start();
        } catch (Exception exception) {
            this.status = "Invalid proxy profile: " + exception.getMessage();
        }
    }

    private void deleteProfile() {
        if (this.selectedId.isEmpty()) {
            this.status = "Select a saved proxy profile first.";
            return;
        }
        try {
            if (this.controller.getNetworkStore().isProxyProfileInUse(this.selectedId)) {
                this.status = "This proxy is assigned to an account. Switch that account to Direct or another proxy first.";
                return;
            }
            List<ProxyProfile> next = new ArrayList<ProxyProfile>(this.profiles);
            final String removedId = this.selectedId;
            next.removeIf(profile -> profile.getId().equals(removedId));
            this.controller.getProxyProfileStore().save(next);
            refreshProfiles();
            clearForm();
            this.status = "Proxy profile deleted.";
        } catch (Exception exception) {
            this.status = "Could not delete proxy profile: " + exception.getMessage();
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderMsmBackground();
        int panelWidth = Math.max(1, Math.min(560, this.width - 24));
        int left = Math.max(12, (this.width - panelWidth) / 2);
        this.drawGlassPanel(left, 42, left + panelWidth, this.height - 44);
        drawCenteredString(MinecraftClient.getInstance().textRenderer, "PROXY MANAGER", this.width / 2, 52, 0xFFE7EDF5);
        this.drawSectionTitle("PROFILE NAME", left + 12, 62);
        this.drawSectionTitle("HOST", left + 12, 88);
        this.drawSectionTitle("PORT", left + 12, 114);
        this.drawSectionTitle("USERNAME", left + 12, 140);
        this.drawSectionTitle("PASSWORD", left + 12, 166);
        this.drawSectionTitle("SUPPORTED: SOCKS5 AND HTTP CONNECT", left + 12, 260);
        this.drawSectionTitle("TEST checks TCP endpoint reachability only.", left + 12, 276);
        this.renderMsmFooter(this.status, 0);
        super.render(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        MinecraftClient.getInstance().openScreen(this.parent);
    }
}
