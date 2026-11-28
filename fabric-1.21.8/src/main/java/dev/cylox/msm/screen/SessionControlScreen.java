package dev.cylox.msm.screen;

import dev.cylox.msm.MsmClient;
import dev.cylox.msm.automation.SessionAutomationScheduler;
import dev.cylox.msm.automation.SessionAutomationSettings;
import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.session.SessionState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

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
    private SessionAutomationSettings draft;
    private SessionAutomationSettings appliedSettings;

    private volatile String status = "";

    private TextFieldWidget chatField;
    private MsmButtonWidget sendButton;
    private MsmButtonWidget connectionButton;

    private MsmButtonWidget autoReconnectButton;
    private MsmButtonWidget retryBackoffButton;
    private TextFieldWidget reconnectDelayField;
    private TextFieldWidget reconnectAttemptsField;
    private TextFieldWidget reconnectMaxDelayField;

    private MsmButtonWidget undoButton;
    private MsmButtonWidget applyButton;
    private MsmButtonWidget closeButton;
    private int scrollOffset;
    private int contentLeft;
    private int contentTop;
    private int contentWidth;
    private int contentBottom;
    private int chatPanelTop;
    private int chatPanelBottom;
    private int settingsReconnectY;
    private int settingsMaxDelayY;
    private int settingsRetryY;
    private int settingsFirstX;
    private int settingsSecondX;
    private int settingsThirdX;
    private int settingsFieldWidth;
    private int settingsColumns;

    private List<String> wrappedMessages = Collections.emptyList();
    private int cachedMessageCount = -1;
    private int cachedMessageWidth = -1;
    private String cachedFirstMessage;
    private String cachedLastMessage;

    public SessionControlScreen(Screen parent, long sessionId, String username) {
        this(parent, MsmClient.getSecondarySessions(),
                MsmClient.getAutomationScheduler(), sessionId, username);
    }

    public SessionControlScreen(Screen parent, SecondarySessionController controller,
                                SessionAutomationScheduler scheduler,
                                long sessionId, String username) {
        this(parent, controller, scheduler, sessionId, username,
                Tab.CHAT, scheduler.getSettings(sessionId),
                scheduler.getSettings(sessionId));
    }

    private SessionControlScreen(Screen parent, SecondarySessionController controller,
                                 SessionAutomationScheduler scheduler,
                                 long sessionId, String username, Tab tab,
                                 SessionAutomationSettings draft,
                                 SessionAutomationSettings appliedSettings) {
        super(Text.literal("Control Manage - " + username), parent);
        if (controller == null || scheduler == null || draft == null
                || appliedSettings == null) {
            throw new IllegalArgumentException(
                    "Controller, scheduler, and settings are required");
        }
        this.parent = parent;
        this.controller = controller;
        this.scheduler = scheduler;
        this.sessionId = sessionId;
        this.username = username == null ? "" : username;
        this.tab = tab;
        this.draft = draft.copy();
        this.appliedSettings = appliedSettings.copy();
    }

    @Override
    protected void init() {
        int workspaceWidth = Math.max(1, Math.min(this.width - 24, 900));
        int workspaceLeft = Math.max(12, (this.width - workspaceWidth) / 2);
        boolean sidebar = workspaceWidth >= 520;
        this.contentLeft = sidebar ? workspaceLeft + 132 : workspaceLeft + 12;
        this.contentTop = sidebar ? 44 : 76;
        this.contentWidth = Math.max(1, workspaceWidth - (sidebar ? 144 : 24));
        this.contentBottom = Math.max(this.contentTop + 1, this.height - 42);

        if (sidebar) {
            addTabButton(workspaceLeft + 16, 58, 100, "CHAT", Tab.CHAT);
            addTabButton(workspaceLeft + 16, 86, 100, "SETTINGS", Tab.SETTINGS);
        } else {
            int gap = 5;
            int tabWidth = Math.min(220, Math.max(1, (workspaceWidth - 24 - gap) / 2));
            addTabButton(12, 44, tabWidth, "CHAT", Tab.CHAT);
            addTabButton(12 + tabWidth + gap, 44, tabWidth, "SETTINGS", Tab.SETTINGS);
        }

        initFooter();

        if (this.tab == Tab.CHAT) {
            initChat();
            refreshWrappedMessages();
        } else {
            initSettings();
        }

        updateFooterState();
    }

    private void addTabButton(int x, int y, int width, String label, Tab target) {
        MsmButtonWidget button = this.addButton(new MsmButtonWidget(
                x, y, width, 22, label, widget -> openTab(target)));
        button.setSelected(this.tab == target);
        button.active = true;
    }

    private void initFooter() {
        int buttonWidth = this.width < 360 ? 54 : 62;
        int gap = 5;
        int y = this.height - 30;
        int right = this.contentLeft + this.contentWidth;

        this.closeButton = this.addButton(new MsmButtonWidget(
                right - buttonWidth, y, buttonWidth, 20,
                "BACK", button -> close()));
        right -= buttonWidth + gap;

        this.applyButton = this.addButton(new MsmButtonWidget(
                right - buttonWidth, y, buttonWidth, 20,
                "APPLY", button -> applyChanges()));
        right -= buttonWidth + gap;

        this.undoButton = this.addButton(new MsmButtonWidget(
                right - buttonWidth, y, buttonWidth, 20,
                "UNDO", button -> undoChanges()));
    }

    private void openTab(Tab next) {
        if (next == this.tab || !commitEditorToDraft()) return;
        this.client.setScreen(new SessionControlScreen(
                this.parent, this.controller, this.scheduler, this.sessionId,
                this.username, next, this.draft, this.appliedSettings));
    }

    private void initChat() {
        int left = this.contentLeft + 12;
        int width = Math.max(1, this.contentWidth - 24);
        int gap = 6;
        int inputWidth = Math.min(620, width);
        int inputLeft = left + (width - inputWidth) / 2;
        int sendWidth = Math.min(70, Math.max(48, inputWidth / 5));
        sendWidth = Math.min(sendWidth, Math.max(1, inputWidth - gap - 1));
        int fieldWidth = Math.max(1, inputWidth - sendWidth - gap);
        int inputY = this.contentBottom - 28;

        this.chatPanelTop = this.contentTop + 38;
        this.chatPanelBottom = Math.max(this.chatPanelTop + 1, inputY - 18);

        this.chatField = this.addButton(new TextFieldWidget(
                this.textRenderer, inputLeft, inputY, fieldWidth, 20,
                Text.literal("Message")));
        this.chatField.setMaxLength(256);

        this.sendButton = this.addButton(new MsmButtonWidget(
                inputLeft + fieldWidth + gap, inputY, sendWidth, 20,
                "SEND", button -> sendChat()));

        int connectionWidth = Math.min(132, Math.max(88, width / 3));
        this.connectionButton = this.addButton(new MsmButtonWidget(
                left + width - connectionWidth, this.contentTop + 5,
                connectionWidth, 20, "JOIN / CONNECT",
                button -> toggleConnection()));
        updateConnectionControls();
    }

    private void initSettings() {
        int left = this.contentLeft + 12;
        int width = Math.max(1, this.contentWidth - 24);
        int gap = 8;
        boolean compact = this.height < 340;
        boolean veryCompact = this.height < 320;
        this.settingsColumns = width >= 500 ? 3 : 2;
        this.settingsFieldWidth = Math.min(180, Math.max(60,
                (width - gap * (this.settingsColumns - 1)) / this.settingsColumns));
        int fieldGroupWidth = this.settingsFieldWidth * this.settingsColumns
                + gap * (this.settingsColumns - 1);
        int fieldGroupLeft = left + (width - fieldGroupWidth) / 2;
        this.settingsFirstX = fieldGroupLeft;
        this.settingsSecondX = fieldGroupLeft + this.settingsFieldWidth + gap;
        this.settingsThirdX = this.settingsColumns == 3
                ? this.settingsSecondX + this.settingsFieldWidth + gap : fieldGroupLeft;

        int baseY = this.contentTop + (veryCompact ? 8 : compact ? 18 : 28);
        int toggleWidth = Math.min(190, width);
        int toggleLeft = left + (width - toggleWidth) / 2;
        this.autoReconnectButton = this.addButton(new MsmButtonWidget(
                toggleLeft, baseY, toggleWidth, 20, getAutoReconnectLabel(),
                button -> {
                    this.draft.setAutoReconnect(!this.draft.isAutoReconnect());
                    this.autoReconnectButton.setMessage(getAutoReconnectLabel());
                    updateFooterState();
                }));

        this.settingsReconnectY = baseY + (veryCompact ? 34 : compact ? 44 : 50);
        this.settingsMaxDelayY = this.settingsColumns == 3
                ? this.settingsReconnectY
                : this.settingsReconnectY + (veryCompact ? 28 : compact ? 34 : 40);

        int reconnectBottom = Math.max(
                this.settingsReconnectY, this.settingsMaxDelayY) + 20;
        this.settingsRetryY = reconnectBottom
                + (veryCompact ? 12 : compact ? 20 : 26);

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
                toggleLeft, this.settingsRetryY, toggleWidth, 20, getRetryBackoffLabel(),
                button -> {
                    this.draft.setRetryBackoff(!this.draft.isRetryBackoff());
                    this.retryBackoffButton.setMessage(getRetryBackoffLabel());
                    updateFooterState();
                }));

    }

    private TextFieldWidget addNumberField(int x, int y, int width, int value) {
        TextFieldWidget field = this.addButton(new TextFieldWidget(
                this.textRenderer, x, y, width, 20, Text.literal("")));
        field.setMaxLength(5);
        field.setText(Integer.toString(value));
        return field;
    }

    private String getAutoReconnectLabel() {
        return "AUTO RECONNECT  " + (this.draft.isAutoReconnect() ? "[ ON ]" : "[ OFF ]");
    }

    private String getRetryBackoffLabel() {
        return "RETRY BACKOFF  " + (this.draft.isRetryBackoff() ? "[ ON ]" : "[ OFF ]");
    }

    private boolean commitEditorToDraft() {
        try {
            if (this.tab == Tab.SETTINGS) {
                commitSettingsFields();
            }
            updateFooterState();
            return true;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
            return false;
        }
    }

    private void commitSettingsFields() {
        this.draft.setReconnectDelaySeconds(
                parseNumber(this.reconnectDelayField, 15));
        this.draft.setMaxReconnectAttempts(
                parseNumber(this.reconnectAttemptsField, 5));
        this.draft.setMaxReconnectDelaySeconds(
                parseNumber(this.reconnectMaxDelayField, 300));

        setFieldTextIfChanged(this.reconnectDelayField,
                this.draft.getReconnectDelaySeconds());
        setFieldTextIfChanged(this.reconnectAttemptsField,
                this.draft.getMaxReconnectAttempts());
        setFieldTextIfChanged(this.reconnectMaxDelayField,
                this.draft.getMaxReconnectDelaySeconds());
    }

    private boolean applyChanges() {
        if (!commitEditorToDraft()) return false;
        if (!hasPendingChanges()) {
            this.status = "No pending changes.";
            updateFooterState();
            return true;
        }

        try {
            SessionAutomationSettings latest = this.scheduler.getSettings(this.sessionId);
            this.draft.setDesiredOnline(latest.isDesiredOnline());
            this.scheduler.saveSettings(this.sessionId, this.draft);
            this.appliedSettings = this.draft.copy();
            this.status = "Changes applied.";
            updateFooterState();
            return true;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
            updateFooterState();
            return false;
        }
    }

    private void undoChanges() {
        if (!hasPendingChanges()) return;
        this.client.setScreen(new SessionControlScreen(
                this.parent, this.controller, this.scheduler, this.sessionId,
                this.username, this.tab, this.appliedSettings, this.appliedSettings));
    }

    private void updateFooterState() {
        boolean pending = hasPendingChanges();
        if (this.undoButton != null) this.undoButton.active = pending;
        if (this.applyButton != null) this.applyButton.active = pending;
        if (this.closeButton != null) this.closeButton.active = true;
    }

    private boolean hasPendingChanges() {
        if (!settingsEqual(this.draft, this.appliedSettings)) return true;
        if (this.tab != Tab.SETTINGS) return false;
        return !fieldMatches(this.reconnectDelayField, this.draft.getReconnectDelaySeconds())
                || !fieldMatches(this.reconnectAttemptsField, this.draft.getMaxReconnectAttempts())
                || !fieldMatches(this.reconnectMaxDelayField, this.draft.getMaxReconnectDelaySeconds());
    }

    private static boolean settingsEqual(SessionAutomationSettings first,
                                          SessionAutomationSettings second) {
        return first.isAutoReconnect() == second.isAutoReconnect()
                && first.getReconnectDelaySeconds() == second.getReconnectDelaySeconds()
                && first.getMaxReconnectAttempts() == second.getMaxReconnectAttempts()
                && first.isRetryBackoff() == second.isRetryBackoff()
                && first.getMaxReconnectDelaySeconds() == second.getMaxReconnectDelaySeconds();
    }

    private static boolean fieldMatches(TextFieldWidget field, int value) {
        return field == null || Integer.toString(value).equals(field.getText().trim());
    }


    private static void setFieldTextIfChanged(TextFieldWidget field, int value) {
        String normalized = Integer.toString(value);
        if (field != null && !normalized.equals(field.getText())) {
            field.setText(normalized);
        }
    }

    private int parseNumber(TextFieldWidget field, int fallback) {
        if (field == null) return fallback;
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Enter a valid number for every delay and limit.");
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
                            : state == SessionState.ONLINE ? "DISCONNECT"
                            : "JOIN / CONNECT");
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
            this.status = latest == null
                    ? "OFFLINE as " + this.username + "." : latest;
        }
    }

    @Override
    public void tick() {
        super.tick();
        refreshWrappedMessages();
        updateConnectionControls();
        updateFooterState();
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
        int messageWidth = Math.max(1, this.contentWidth - 44);
        if (count == this.cachedMessageCount
                && messageWidth == this.cachedMessageWidth
                && equal(first, this.cachedFirstMessage)
                && equal(last, this.cachedLastMessage)) {
            return;
        }

        List<String> wrapped = new ArrayList<String>();
        int maxCharacters = Math.max(8, messageWidth / 6);
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
        this.cachedMessageWidth = messageWidth;
        this.cachedFirstMessage = first;
        this.cachedLastMessage = last;
        int visibleLines = getChatVisibleLines();
        this.scrollOffset = Math.min(this.scrollOffset,
                Math.max(0, wrapped.size() - visibleLines));
    }

    private int getChatVisibleLines() {
        return Math.max(1, (this.chatPanelBottom - this.chatPanelTop - 12) / 11);
    }

    private static boolean equal(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private String connectionSummary() {
        try {
            SessionState state = this.controller.getState(this.sessionId);
            return state == SessionState.ONLINE ? "ONLINE"
                    : state == SessionState.CONNECTING ? "CONNECTING" : "OFFLINE";
        } catch (IllegalArgumentException exception) {
            return "OFFLINE";
        }
    }

    private void drawPanel(DrawContext context, int left, int top,
                           int right, int bottom, int fill) {
        if (right - left < 2 || bottom - top < 2) return;
        context.fill(left, top, right, bottom, fill);
        context.fill(left, top, right, top + 1, 0x503F5265);
        context.fill(left, bottom - 1, right, bottom, 0x302E3C4C);
        context.fill(left, top, left + 1, bottom, 0x302E3C4C);
        context.fill(right - 1, top, right, bottom, 0x302E3C4C);
    }

    @Override
    public void close() {
        if (!applyChanges()) return;
        this.client.setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderMsmBackground(context, mouseX, mouseY, delta);

        boolean sidebar = this.width >= 520;
        if (sidebar) {
            drawPanel(context, 12, 44, 120, this.contentBottom, 0x4A18212C);
        }
        drawPanel(context, this.contentLeft, this.contentTop,
                this.width - 12, this.contentBottom, 0x52141D28);

        String title = this.tab == Tab.CHAT ? "CHAT" : "SETTINGS";
        String subtitle = this.tab == Tab.CHAT
                ? "Messages and connection controls"
                : "Configure safe reconnect behavior";

        this.text(context, title, this.contentLeft + 12,
                this.contentTop + 9, 0xFFF1F5F9);
        this.text(context, subtitle, this.contentLeft + 12,
                this.contentTop + 22, 0xFF9EADBD);

        if (this.tab == Tab.CHAT) {
            drawPanel(context, this.contentLeft + 12, this.chatPanelTop,
                    this.contentLeft + this.contentWidth - 12,
                    this.chatPanelBottom, 0x50101822);
        } else {
            if (this.height >= 340) {
                this.text(context, "RECONNECTION", this.contentLeft + 12,
                        this.settingsReconnectY - 25, 0xFF9EADBD);
            }
            this.text(context, "RECONNECT DELAY", this.settingsFirstX,
                    this.settingsReconnectY - 12, 0xFF9EADBD);
            this.text(context, "MAX ATTEMPTS", this.settingsSecondX,
                    this.settingsReconnectY - 12, 0xFF9EADBD);
            this.text(context, "MAX DELAY", this.settingsThirdX,
                    this.settingsMaxDelayY - 12, 0xFF9EADBD);
        }

        boolean pending = hasPendingChanges();
        String footer = pending ? "●  UNSAVED CHANGES" : "No pending changes";
        if (this.tab == Tab.CHAT && this.status != null && !this.status.isEmpty()) {
            footer = this.status + "  |  " + footer;
        }
        int statusColor = pending ? 0xFFFFD27A : 0xFF9EADBD;
        renderMsmFooter(context, fitText(footer, Math.max(40, this.width - 230)),
                230, statusColor);

        super.render(context, mouseX, mouseY, delta);
        if (this.tab == Tab.CHAT) renderChat(context);
    }

    private void renderChat(DrawContext context) {
        List<String> messages = this.wrappedMessages;
        int visibleLines = getChatVisibleLines();
        int end = Math.max(0, messages.size() - this.scrollOffset);
        int start = Math.max(0, end - visibleLines);

        context.enableScissor(this.contentLeft + 12, this.chatPanelTop,
                this.contentLeft + this.contentWidth - 12, this.chatPanelBottom);
        int y = this.chatPanelTop + 6;
        for (int index = start; index < end; index++) {
            context.drawTextWithShadow(this.textRenderer,
                    messages.get(index), this.contentLeft + 20, y, 0xFFE3EAF1);
            y += 11;
        }
        context.disableScissor();
    }

    private String fitText(String value, int pixelWidth) {
        if (value == null) return "";
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }
}
