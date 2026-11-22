package dev.cylox.msm.screen;

import dev.cylox.msm.net.SessionNetworkManager;
import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.LiteralText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SessionControlScreen extends Screen {
    private interface ControlAction {
        void run() throws IOException;
    }

    private final Screen parent;
    private final SessionManager sessionManager;
    private final SessionNetworkManager networkManager;
    private final long sessionId;
    private final List<ButtonWidget> onlineControls = new ArrayList<ButtonWidget>();

    private TextFieldWidget chatField;
    private ButtonWidget connectButton;
    private ButtonWidget disconnectButton;
    private ButtonWidget sendButton;
    private ButtonWidget sneakButton;
    private ButtonWidget sprintButton;
    private String status = "";

    public SessionControlScreen(Screen parent, SessionManager sessionManager,
                                SessionNetworkManager networkManager, long sessionId) {
        super(new LiteralText("MSM Session Control"));
        if (sessionManager == null || networkManager == null) {
            throw new IllegalArgumentException("Session and network managers are required.");
        }
        this.parent = parent;
        this.sessionManager = sessionManager;
        this.networkManager = networkManager;
        this.sessionId = sessionId;
    }

    @Override
    protected void init() {
        Optional<SessionManager.Session> selected = this.sessionManager.find(this.sessionId);
        if (!selected.isPresent()) {
            this.status = "This session profile no longer exists.";
            return;
        }
        SessionProfile profile = selected.get().getProfile();
        this.status = profile.getAuthMode() == SessionAuthMode.OFFLINE_LOCAL
                ? "OFFLINE_LOCAL works only with a server configured for offline-mode."
                : "Microsoft sign-in is not implemented yet for this session.";

        int contentLeft = 12;
        int contentWidth = Math.max(120, this.width - 24);
        int gap = 4;
        int halfWidth = (contentWidth - gap) / 2;
        int rowY = 44;

        this.connectButton = this.addButton(new ButtonWidget(contentLeft, rowY,
                halfWidth, 20, "CONNECT", button -> connect()));
        this.disconnectButton = this.addButton(new ButtonWidget(
                contentLeft + halfWidth + gap, rowY, halfWidth, 20,
                "DISCONNECT", button -> disconnect()));

        int sendWidth = Math.min(58, contentWidth / 3);
        int chatWidth = contentWidth - sendWidth - gap;
        this.chatField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer, contentLeft, 72,
                chatWidth, 20, "Chat or command"));
        this.chatField.setMaxLength(256);
        this.sendButton = this.addButton(new ButtonWidget(
                contentLeft + chatWidth + gap, 72, sendWidth, 20,
                "SEND", button -> sendChat()));

        int buttonWidth = (contentWidth - gap * 3) / 4;
        int controlsY = 100;
        addControlButton(contentLeft, controlsY, buttonWidth, "W",
                () -> this.networkManager.move(this.sessionId, SessionNetworkManager.Direction.FORWARD));
        addControlButton(contentLeft + buttonWidth + gap, controlsY, buttonWidth, "JUMP",
                () -> this.networkManager.jump(this.sessionId));
        this.sneakButton = addToggleButton(contentLeft + (buttonWidth + gap) * 2,
                controlsY, buttonWidth, "SNEAK", true);
        this.sprintButton = addToggleButton(contentLeft + (buttonWidth + gap) * 3,
                controlsY, buttonWidth, "SPRINT", false);

        int secondRowY = 122;
        addControlButton(contentLeft, secondRowY, buttonWidth, "A",
                () -> this.networkManager.move(this.sessionId, SessionNetworkManager.Direction.LEFT));
        addControlButton(contentLeft + buttonWidth + gap, secondRowY, buttonWidth, "S",
                () -> this.networkManager.move(this.sessionId, SessionNetworkManager.Direction.BACKWARD));
        addControlButton(contentLeft + (buttonWidth + gap) * 2, secondRowY, buttonWidth, "D",
                () -> this.networkManager.move(this.sessionId, SessionNetworkManager.Direction.RIGHT));
        this.addButton(new ButtonWidget(contentLeft + (buttonWidth + gap) * 3,
                secondRowY, buttonWidth, 20, "CLEAR LOG", button -> clearLog()));

        this.addButton(new ButtonWidget(contentLeft, this.height - 24,
                contentWidth, 20, "BACK", button -> onClose()));
        updateButtonStates();
    }

    private void addControlButton(int x, int y, int width, String label, ControlAction action) {
        ButtonWidget button = this.addButton(new ButtonWidget(x, y, width, 20, label,
                widget -> runAction(action)));
        this.onlineControls.add(button);
    }

    private ButtonWidget addToggleButton(int x, int y, int width, String label, boolean sneak) {
        ButtonWidget button = this.addButton(new ButtonWidget(x, y, width, 20, label,
                widget -> runAction(() -> {
                    boolean enabled = sneak
                            ? this.networkManager.toggleSneak(this.sessionId)
                            : this.networkManager.toggleSprint(this.sessionId);
                    widget.setMessage(label + (enabled ? " ON" : ""));
                })));
        this.onlineControls.add(button);
        return button;
    }

    private void connect() {
        try {
            this.networkManager.connect(this.sessionId);
            this.status = "Connecting...";
            updateButtonStates();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        }
    }

    private void disconnect() {
        try {
            this.networkManager.disconnect(this.sessionId);
            this.status = "Disconnecting...";
            updateButtonStates();
        } catch (IllegalStateException exception) {
            this.status = exception.getMessage();
        }
    }

    private void sendChat() {
        try {
            String message = this.chatField.getText();
            if (message == null || message.trim().isEmpty()) return;
            this.networkManager.sendChat(this.sessionId, message);
            this.chatField.setText("");
        } catch (IOException exception) {
            this.status = exception.getMessage();
        }
    }

    private void runAction(ControlAction action) {
        try {
            action.run();
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        }
    }

    private void clearLog() {
        this.networkManager.clearHistory(this.sessionId);
        this.status = "Session chat history cleared.";
    }

    private void updateButtonStates() {
        Optional<SessionManager.Session> selected = this.sessionManager.find(this.sessionId);
        SessionState state = selected.map(SessionManager.Session::getState).orElse(SessionState.FAILED);
        boolean connectingOrOnline = state == SessionState.CONNECTING || state == SessionState.ONLINE;
        boolean online = state == SessionState.ONLINE;
        boolean offlineAuth = selected.isPresent()
                && selected.get().getProfile().getAuthMode() == SessionAuthMode.OFFLINE_LOCAL;

        if (this.connectButton != null) this.connectButton.active = offlineAuth && !connectingOrOnline;
        if (this.disconnectButton != null) this.disconnectButton.active = connectingOrOnline;
        if (this.chatField != null) this.chatField.active = online;
        if (this.sendButton != null) this.sendButton.active = online;
        for (ButtonWidget button : this.onlineControls) button.active = online;

        if (this.sneakButton != null) {
            this.sneakButton.setMessage(this.networkManager.isSneaking(this.sessionId)
                    ? "SNEAK ON" : "SNEAK");
        }
        if (this.sprintButton != null) {
            this.sprintButton.setMessage(this.networkManager.isSprinting(this.sessionId)
                    ? "SPRINT ON" : "SPRINT");
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.chatField != null) this.chatField.tick();
        updateButtonStates();
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
        this.renderBackground();
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                this.title.asFormattedString(), this.width / 2, 10, 0xFFFFFF);

        Optional<SessionManager.Session> selected = this.sessionManager.find(this.sessionId);
        if (selected.isPresent()) {
            SessionProfile profile = selected.get().getProfile();
            drawString(MinecraftClient.getInstance().textRenderer,
                    fitText(profile.getUsername() + "  |  " + profile.getServerAddress(), this.width - 24),
                    12, 25, 0xA0A0A0);
            drawString(MinecraftClient.getInstance().textRenderer,
                    "STATE: " + selected.get().getState().name(), 12, 34, 0x8CCBFF);
        } else {
            drawString(MinecraftClient.getInstance().textRenderer,
                    "SESSION PROFILE NOT FOUND", 12, 25, 0xFF5555);
        }

        drawString(MinecraftClient.getInstance().textRenderer,
                fitText(this.status, this.width - 24), 12, 137, 0xFFFFFF);
        drawString(MinecraftClient.getInstance().textRenderer,
                fitText(this.networkManager.getPositionText(this.sessionId), this.width - 24),
                12, 150, 0xA0A0A0);
        drawString(MinecraftClient.getInstance().textRenderer,
                "SESSION CHAT / SERVER FEEDBACK", 12, 166, 0x8CCBFF);

        List<String> messages = this.networkManager.getRecentMessages(this.sessionId);
        int maxLines = Math.max(0, (this.height - 205) / 11);
        int start = Math.max(0, messages.size() - maxLines);
        int y = 178;
        for (int index = start; index < messages.size(); index++) {
            drawString(MinecraftClient.getInstance().textRenderer,
                    fitText(messages.get(index), this.width - 24), 12, y, 0xD0D0D0);
            y += 11;
        }

        super.render(mouseX, mouseY, delta);
    }

    private String fitText(String value, int pixelWidth) {
        if (value == null) return "";
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }
}