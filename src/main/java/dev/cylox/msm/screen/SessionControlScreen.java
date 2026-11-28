package dev.cylox.msm.screen;

import dev.cylox.msm.automation.SessionAutomationScheduler;
import dev.cylox.msm.automation.SessionAutomationSettings;
import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.session.SessionState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.LiteralText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SessionControlScreen extends MsmScreen {
    private enum Tab {
        CHAT,
        SETTINGS
    }

    private final Screen parent;
    private final SecondarySessionController controller;
    private final SessionAutomationScheduler scheduler;
    private final long sessionId;
    private final String username;
    private final Tab tab;
    private final SessionAutomationSettings draft;

    private volatile String status = "";

    private TextFieldWidget chatField;
    private ButtonWidget sendButton;
    private ButtonWidget connectionButton;

    private ButtonWidget autoReconnectButton;
    private ButtonWidget retryBackoffButton;
    private TextFieldWidget reconnectDelayField;
    private TextFieldWidget reconnectAttemptsField;
    private TextFieldWidget reconnectMaxDelayField;
    private int scrollOffset;
    private int settingsPanelLeft;
    private int settingsPanelWidth;
    private int settingsFirstX;
    private int settingsSecondX;
    private int settingsThirdX;
    private int settingsFieldWidth;
    private int settingsReconnectY;
    private int settingsMaxDelayY;
    private List<String> wrappedMessages = Collections.emptyList();
    private int cachedMessageCount = -1;
    private int cachedMessageWidth = -1;
    private String cachedFirstMessage;
    private String cachedLastMessage;

    public SessionControlScreen(Screen parent, SecondarySessionController controller,
                                SessionAutomationScheduler scheduler,
                                long sessionId, String username) {
        this(parent, controller, scheduler, sessionId, username,
                Tab.CHAT, scheduler.getSettings(sessionId));
    }

    private SessionControlScreen(Screen parent, SecondarySessionController controller,
                                 SessionAutomationScheduler scheduler,
                                 long sessionId, String username, Tab tab,
                                 SessionAutomationSettings draft) {
        super(new LiteralText("Control Manage - " + username));
        if (controller == null || scheduler == null || draft == null) {
            throw new IllegalArgumentException("Controller, scheduler, and settings are required");
        }
        this.parent = parent;
        this.controller = controller;
        this.scheduler = scheduler;
        this.sessionId = sessionId;
        this.username = username == null ? "" : username;
        this.tab = tab;
        this.draft = draft.copy();
    }

    @Override
    protected void init() {
        int contentWidth = Math.max(1, Math.min(this.width - 24, 760));
        int left = Math.max(12, (this.width - contentWidth) / 2);
        int gap = 8;
        int tabGroupWidth = 72 + 88 + 72 + gap * 2;
        if (tabGroupWidth <= contentWidth) {
            int tabLeft = left + (contentWidth - tabGroupWidth) / 2;
            addTabButton(tabLeft, 28, 72, "CHAT", Tab.CHAT);
            addTabButton(tabLeft + 80, 28, 88, "SETTINGS", Tab.SETTINGS);
            this.addButton(new MsmButtonWidget(
                    tabLeft + 176, 28, 72, 20, "BACK", button -> onClose()));
        } else {
            int tabWidth = Math.max(1, (contentWidth - gap * 2) / 3);
            addTabButton(left, 28, tabWidth, "CHAT", Tab.CHAT);
            addTabButton(left + tabWidth + gap, 28, tabWidth, "SETTINGS", Tab.SETTINGS);
            this.addButton(new MsmButtonWidget(
                    left + (tabWidth + gap) * 2, 28, tabWidth, 20,
                    "BACK", button -> onClose()));
        }

        if (this.tab == Tab.CHAT) {
            initChat(left, contentWidth, gap);
            refreshWrappedMessages();
        } else {
            initSettings(left, contentWidth, gap);
        }
    }

    private void addTabButton(int x, int y, int width, String label, Tab target) {
        ButtonWidget button = this.addButton(new MsmButtonWidget(
                x, y, width, 20, label, widget -> openTab(target)));
        button.active = this.tab != target;
    }

    private void openTab(Tab next) {
        MinecraftClient.getInstance().openScreen(new SessionControlScreen(
                this.parent, this.controller, this.scheduler, this.sessionId,
                this.username, next, this.draft));
    }

    private void initChat(int left, int width, int gap) {
        int inputWidth = Math.min(620, width);
        int inputLeft = left + (width - inputWidth) / 2;
        int sendWidth = 60;
        int fieldWidth = Math.max(1, inputWidth - sendWidth - gap);
        int inputY = this.height - 76;
        this.chatField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer,
                inputLeft, inputY, fieldWidth, 20, "Message"));
        this.chatField.setMaxLength(256);
        this.sendButton = this.addButton(new MsmButtonWidget(
                inputLeft + fieldWidth + gap, inputY, sendWidth, 20,
                "SEND", button -> sendChat()));

        int actionWidth = Math.min(216, width);
        int actionLeft = left + width - actionWidth;
        int connectWidth = Math.min(136, actionWidth);
        int backWidth = Math.max(1, actionWidth - connectWidth - gap);
        this.connectionButton = this.addButton(new MsmButtonWidget(
                actionLeft, this.height - 24, connectWidth, 20,
                "JOIN / CONNECT", button -> toggleConnection()));
        this.addButton(new MsmButtonWidget(
                actionLeft + connectWidth + gap, this.height - 24,
                backWidth, 20, "BACK", button -> onClose()));
        updateConnectionControls();
    }

    private void initSettings(int left, int width, int gap) {
        int columns = width >= 520 ? 3 : 2;
        this.settingsFieldWidth = Math.min(180, Math.max(60,
                (width - gap * (columns - 1)) / columns));
        int fieldGroupWidth = this.settingsFieldWidth * columns + gap * (columns - 1);
        int fieldGroupLeft = left + Math.max(0, (width - fieldGroupWidth) / 2);
        this.settingsPanelLeft = Math.max(left, fieldGroupLeft - 20);
        this.settingsPanelWidth = Math.min(width, fieldGroupWidth + 40);
        this.settingsFirstX = fieldGroupLeft;
        this.settingsSecondX = fieldGroupLeft + this.settingsFieldWidth + gap;
        this.settingsThirdX = columns == 3
                ? this.settingsSecondX + this.settingsFieldWidth + gap : fieldGroupLeft;
        this.settingsReconnectY = 96;
        this.settingsMaxDelayY = columns == 3 ? this.settingsReconnectY : 132;
        int reconnectBottom = Math.max(this.settingsReconnectY, this.settingsMaxDelayY) + 20;
        int retryY = reconnectBottom + 16;

        int toggleWidth = Math.min(190, this.settingsPanelWidth);
        int toggleLeft = this.settingsPanelLeft + (this.settingsPanelWidth - toggleWidth) / 2;
        this.autoReconnectButton = this.addButton(new MsmButtonWidget(
                toggleLeft, 58, toggleWidth, 20, getAutoReconnectLabel(),
                button -> {
                    this.draft.setAutoReconnect(!this.draft.isAutoReconnect());
                    this.autoReconnectButton.setMessage(getAutoReconnectLabel());
                }));

        this.reconnectDelayField = addNumberField(
                this.settingsFirstX, this.settingsReconnectY,
                this.settingsFieldWidth, this.draft.getReconnectDelaySeconds());
        this.reconnectAttemptsField = addNumberField(
                this.settingsSecondX, this.settingsReconnectY,
                this.settingsFieldWidth, this.draft.getMaxReconnectAttempts());
        this.reconnectMaxDelayField = addNumberField(
                this.settingsThirdX, this.settingsMaxDelayY,
                this.settingsFieldWidth, this.draft.getMaxReconnectDelaySeconds());

        this.retryBackoffButton = this.addButton(new MsmButtonWidget(
                toggleLeft, retryY, toggleWidth, 20, getRetryBackoffLabel(),
                button -> {
                    this.draft.setRetryBackoff(!this.draft.isRetryBackoff());
                    this.retryBackoffButton.setMessage(getRetryBackoffLabel());
                }));


        int saveWidth = Math.min(120, this.settingsPanelWidth);
        this.addButton(new MsmButtonWidget(
                this.settingsPanelLeft + this.settingsPanelWidth - saveWidth,
                this.height - 24, saveWidth, 20, "SAVE SETTINGS", button -> saveSettings()));
    }

    private TextFieldWidget addNumberField(int x, int y, int width, int value) {
        TextFieldWidget field = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer, x, y, width, 20, ""));
        field.setMaxLength(5);
        field.setText(Integer.toString(value));
        return field;
    }

    private String getAutoReconnectLabel() {
        return "AUTO RECONNECT: " + (this.draft.isAutoReconnect() ? "ON" : "OFF");
    }

    private String getRetryBackoffLabel() {
        return "RETRY BACKOFF: " + (this.draft.isRetryBackoff() ? "ON" : "OFF");
    }

    private void saveSettings() {
        try {
            if (this.tab == Tab.SETTINGS) {
                this.draft.setReconnectDelaySeconds(parseNumber(this.reconnectDelayField, 15));
                this.draft.setMaxReconnectAttempts(parseNumber(this.reconnectAttemptsField, 5));
                this.draft.setMaxReconnectDelaySeconds(parseNumber(this.reconnectMaxDelayField, 300));
            }
            this.scheduler.saveSettings(this.sessionId, this.draft);
            this.status = "Settings saved.";
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        }
    }

    private int parseNumber(TextFieldWidget field, int fallback) {
        if (field == null) return fallback;
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Enter a valid number for every delay and limit.");
        }
    }

    private void sendChat() {
        if (this.chatField == null) return;
        String message = this.chatField.getText();
        if (message == null || message.trim().isEmpty()) return;
        try {
            this.controller.sendChat(this.sessionId, message);
            this.chatField.setText("");
            this.status = "Message sent.";
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        }
    }

    private void toggleConnection() {
        try {
            SessionState state = this.controller.getState(this.sessionId);
            if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
                this.scheduler.disconnectManually(this.sessionId);
                this.status = "Manual disconnect requested.";
            } else {
                this.scheduler.connectManually(this.sessionId);
                this.status = "Manual connection requested.";
            }
            updateConnectionControls();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        }
    }

    private void updateConnectionControls() {
        SessionState state;
        try {
            state = this.controller.getState(this.sessionId);
        } catch (IllegalArgumentException exception) {
            state = SessionState.FAILED;
        }

        if (this.connectionButton != null) {
            this.connectionButton.setMessage(
                    state == SessionState.CONNECTING ? "CANCEL"
                            : state == SessionState.ONLINE ? "DISCONNECT" : "JOIN / CONNECT");
        }
        if (this.sendButton != null) {
            this.sendButton.active = state == SessionState.ONLINE;
        }
        if (state == SessionState.CONNECTING) {
            this.status = "CONNECTING as " + this.username + "...";
        } else if (state == SessionState.ONLINE) {
            this.status = "ONLINE as " + this.username + ".";
        } else {
            List<String> messages = this.controller.getMessages(this.sessionId);
            String latest = null;
            for (int index = messages.size() - 1; index >= 0; index--) {
                String message = messages.get(index);
                if (message.startsWith("[MSM] Disconnected during ")
                        || message.startsWith("[MSM] Login rejected:")
                        || message.startsWith("[MSM] Server disconnected:")
                        || message.startsWith("[MSM] AutoReconnect")) {
                    latest = message;
                    break;
                }
            }
            this.status = latest == null ? "OFFLINE as " + this.username + "." : latest;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.chatField != null) this.chatField.tick();
        if (this.reconnectDelayField != null) this.reconnectDelayField.tick();
        if (this.reconnectAttemptsField != null) this.reconnectAttemptsField.tick();
        if (this.reconnectMaxDelayField != null) this.reconnectMaxDelayField.tick();
        refreshWrappedMessages();
        updateConnectionControls();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.tab == Tab.CHAT) {
            if (keyCode == 257 && this.controller.isOnline(this.sessionId)) {
                sendChat();
                return true;
            }
            if (keyCode == 265) {
                this.scrollOffset += 3;
                return true;
            }
            if (keyCode == 264) {
                this.scrollOffset = Math.max(0, this.scrollOffset - 3);
                return true;
            }
            if (keyCode == 269) {
                this.scrollOffset = 0;
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void refreshWrappedMessages() {
        if (this.tab != Tab.CHAT) return;
        List<String> messages = this.controller.getMessages(this.sessionId);
        int count = messages.size();
        String first = count == 0 ? null : messages.get(0);
        String last = count == 0 ? null : messages.get(count - 1);
        if (count == this.cachedMessageCount
                && this.width == this.cachedMessageWidth
                && equal(first, this.cachedFirstMessage)
                && equal(last, this.cachedLastMessage)) {
            return;
        }

        List<String> wrapped = new ArrayList<String>();
        int maxCharacters = Math.max(8, (this.width - 24) / 6);
        for (String message : messages) {
            String value = message == null ? "" : message;
            for (int offset = 0; offset < value.length(); offset += maxCharacters) {
                wrapped.add(value.substring(offset,
                        Math.min(value.length(), offset + maxCharacters)));
            }
            if (value.isEmpty()) wrapped.add("");
        }

        this.wrappedMessages = wrapped;
        this.cachedMessageCount = count;
        this.cachedMessageWidth = this.width;
        this.cachedFirstMessage = first;
        this.cachedLastMessage = last;
        int visibleLines = Math.max(1, (this.height - 142) / 11);
        this.scrollOffset = Math.min(this.scrollOffset,
                Math.max(0, wrapped.size() - visibleLines));
    }

    private List<String> getWrappedMessages() {
        return this.wrappedMessages;
    }

    private static boolean equal(String first, String second) {
        return first == null ? second == null : first.equals(second);
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
        if (this.tab == Tab.CHAT) {
            drawGlassPanel(16, 52, this.width - 16, this.height - 104);
            renderChat();
        } else {
            drawString(MinecraftClient.getInstance().textRenderer,
                    "RECONNECT DELAY", this.settingsFirstX,
                    this.settingsReconnectY - 12, 0xFF9EADBD);
            drawString(MinecraftClient.getInstance().textRenderer,
                    "MAX ATTEMPTS", this.settingsSecondX,
                    this.settingsReconnectY - 12, 0xFF9EADBD);
            drawString(MinecraftClient.getInstance().textRenderer,
                    "MAX DELAY", this.settingsThirdX,
                    this.settingsMaxDelayY - 12, 0xFF9EADBD);
        }

        renderMsmFooter(this.status, 230,
                0xFF9EADBD);
        super.render(mouseX, mouseY, delta);
    }

    private void renderChat() {
        List<String> messages = getWrappedMessages();
        int lineHeight = 11;
        int visibleLines = Math.max(1, (this.height - 142) / lineHeight);
        int end = Math.max(0, messages.size() - this.scrollOffset);
        int start = Math.max(0, end - visibleLines);
        int y = 56;
        for (int index = start; index < end; index++) {
            drawString(MinecraftClient.getInstance().textRenderer,
                    messages.get(index), 12, y, 0xE0E0E0);
            y += lineHeight;
        }

    }

    private String fitText(String value, int pixelWidth) {
        if (value == null) return "";
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }
}
