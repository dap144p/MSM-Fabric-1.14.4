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
import java.util.List;

public final class SessionControlScreen extends Screen {
    private enum Tab {
        CHAT,
        AUTO_CHAT,
        SETTINGS
    }

    private final Screen parent;
    private final SecondarySessionController controller;
    private final SessionAutomationScheduler scheduler;
    private final long sessionId;
    private final String username;
    private final Tab tab;
    private final SessionAutomationSettings draft;
    private final int requestedRulePage;
    private final int requestedRuleIndex;

    private volatile String status = "";
    private int rulePage;
    private int rulePageSize = 3;
    private SessionAutomationSettings.AutoChatRule.Mode editingMode =
            SessionAutomationSettings.AutoChatRule.Mode.ONCE;

    private TextFieldWidget chatField;
    private ButtonWidget sendButton;
    private ButtonWidget connectionButton;

    private TextFieldWidget ruleMessageField;
    private TextFieldWidget ruleDelayField;
    private ButtonWidget ruleModeButton;
    private ButtonWidget autoChatEnabledButton;
    private ButtonWidget ruleSaveButton;
    private ButtonWidget ruleToggleButton;
    private ButtonWidget ruleDeleteButton;

    private ButtonWidget autoReconnectButton;
    private ButtonWidget retryBackoffButton;
    private TextFieldWidget reconnectDelayField;
    private TextFieldWidget reconnectAttemptsField;
    private TextFieldWidget reconnectMaxDelayField;
    private TextFieldWidget initialChatDelayField;
    private TextFieldWidget globalChatIntervalField;

    private int selectedRuleIndex;
    private int scrollOffset;

    public SessionControlScreen(Screen parent, SecondarySessionController controller,
                                SessionAutomationScheduler scheduler,
                                long sessionId, String username) {
        this(parent, controller, scheduler, sessionId, username,
                Tab.CHAT, scheduler.getSettings(sessionId), 0, -1);
    }

    private SessionControlScreen(Screen parent, SecondarySessionController controller,
                                 SessionAutomationScheduler scheduler,
                                 long sessionId, String username, Tab tab,
                                 SessionAutomationSettings draft,
                                 int rulePage, int selectedRuleIndex) {
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
        this.requestedRulePage = Math.max(0, rulePage);
        this.requestedRuleIndex = selectedRuleIndex;
        this.selectedRuleIndex = selectedRuleIndex;
    }

    @Override
    protected void init() {
        int left = 12;
        int contentWidth = Math.max(1, this.width - 24);
        int gap = 4;
        int tabWidth = Math.max(1, (contentWidth - gap * 3) / 4);

        addTabButton(left, 28, tabWidth, "CHAT", Tab.CHAT);
        addTabButton(left + tabWidth + gap, 28, tabWidth, "AUTO CHAT", Tab.AUTO_CHAT);
        addTabButton(left + (tabWidth + gap) * 2, 28, tabWidth, "SETTINGS", Tab.SETTINGS);
        this.addButton(new ButtonWidget(
                left + (tabWidth + gap) * 3, 28, tabWidth, 20,
                "BACK", button -> onClose()));

        if (this.tab == Tab.CHAT) {
            initChat(left, contentWidth, gap);
        } else if (this.tab == Tab.AUTO_CHAT) {
            initAutoChat(left, contentWidth, gap);
        } else {
            initSettings(left, contentWidth, gap);
        }
    }

    private void addTabButton(int x, int y, int width, String label, Tab target) {
        ButtonWidget button = this.addButton(new ButtonWidget(
                x, y, width, 20, label, widget -> openTab(target)));
        button.active = this.tab != target;
    }

    private void openTab(Tab next) {
        MinecraftClient.getInstance().openScreen(new SessionControlScreen(
                this.parent, this.controller, this.scheduler, this.sessionId,
                this.username, next, this.draft, this.rulePage, this.selectedRuleIndex));
    }

    private void initChat(int left, int width, int gap) {
        int sendWidth = Math.min(64, Math.max(42, width / 6));
        sendWidth = Math.min(sendWidth, Math.max(1, width - gap - 1));
        int fieldWidth = Math.max(1, width - sendWidth - gap);
        this.chatField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer,
                left, this.height - 76, fieldWidth, 20, "Message"));
        this.chatField.setMaxLength(256);
        this.sendButton = this.addButton(new ButtonWidget(
                left + fieldWidth + gap, this.height - 76, sendWidth, 20,
                "SEND", button -> sendChat()));

        int actionWidth = Math.max(1, (width - gap) / 2);
        this.connectionButton = this.addButton(new ButtonWidget(
                left, this.height - 24, actionWidth, 20,
                "JOIN / CONNECT", button -> toggleConnection()));
        this.addButton(new ButtonWidget(
                left + actionWidth + gap, this.height - 24,
                actionWidth, 20, "BACK", button -> onClose()));
        updateConnectionControls();
    }

    private void initAutoChat(int left, int width, int gap) {
        this.autoChatEnabledButton = this.addButton(new ButtonWidget(
                left, 56, width, 20, getAutoChatLabel(),
                button -> toggleAutoChat()));

        List<SessionAutomationSettings.AutoChatRule> rules = this.draft.getRules();
        this.rulePageSize = Math.max(1, Math.min(4, (this.height - 245) / 23));
        int pageCount = Math.max(1, (rules.size() + this.rulePageSize - 1) / this.rulePageSize);
        this.rulePage = Math.min(this.requestedRulePage, pageCount - 1);
        int start = this.rulePage * this.rulePageSize;
        int end = Math.min(start + this.rulePageSize, rules.size());
        int rowY = 84;

        for (int index = start; index < end; index++) {
            final int ruleIndex = index;
            SessionAutomationSettings.AutoChatRule rule = rules.get(index);
            String label = (rule.isEnabled() ? "ON " : "OFF ")
                    + rule.getMode().name() + " " + rule.getDelaySeconds() + "s  "
                    + rule.getMessage();
            this.addButton(new ButtonWidget(
                    left, rowY, width, 20, fitText(label, width - 10),
                    button -> selectRule(ruleIndex)));
            rowY += 23;
        }

        if (rules.isEmpty()) {
            drawEditorHintY = rowY;
        } else {
            drawEditorHintY = rowY;
        }

        if (pageCount > 1) {
            int pageWidth = Math.min(90, Math.max(1, (width - gap) / 2));
            ButtonWidget previous = new ButtonWidget(
                    left, rowY, pageWidth, 18,
                    "PREVIOUS", button -> openRulePage(this.rulePage - 1, this.selectedRuleIndex));
            ButtonWidget next = new ButtonWidget(
                    left + pageWidth + gap, rowY, pageWidth, 18,
                    "NEXT", button -> openRulePage(this.rulePage + 1, this.selectedRuleIndex));
            previous.active = this.rulePage > 0;
            next.active = this.rulePage + 1 < pageCount;
            this.addButton(previous);
            this.addButton(next);
            rowY += 22;
        }

        int editorY = Math.min(this.height - 118, Math.max(112, rowY + 5));
        this.ruleMessageField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer,
                left, editorY, width, 20, "Chat or command"));
        this.ruleMessageField.setMaxLength(256);

        int delayWidth = Math.min(90, Math.max(58, width / 4));
        int modeWidth = Math.max(1, width - delayWidth - gap);
        this.ruleModeButton = this.addButton(new ButtonWidget(
                left, editorY + 25, modeWidth, 20, editingMode.name(),
                button -> cycleRuleMode()));
        this.ruleDelayField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer,
                left + modeWidth + gap, editorY + 25, delayWidth, 20, "Delay"));
        this.ruleDelayField.setMaxLength(5);

        int editorButtonWidth = Math.max(1, (width - gap * 2) / 3);
        this.ruleSaveButton = this.addButton(new ButtonWidget(
                left, editorY + 50, editorButtonWidth, 20,
                this.selectedRuleIndex >= 0 ? "SAVE RULE" : "ADD RULE",
                button -> saveRule()));
        this.ruleToggleButton = this.addButton(new ButtonWidget(
                left + editorButtonWidth + gap, editorY + 50, editorButtonWidth, 20,
                getSelectedRuleEnabledLabel(), button -> toggleSelectedRule()));
        this.ruleDeleteButton = this.addButton(new ButtonWidget(
                left + (editorButtonWidth + gap) * 2, editorY + 50,
                editorButtonWidth, 20, "DELETE", button -> deleteRule()));

        this.ruleDeleteButton.active = this.selectedRuleIndex >= 0;
        this.ruleToggleButton.active = this.selectedRuleIndex >= 0;
        this.addButton(new ButtonWidget(
                left, this.height - 24, width, 20,
                "SAVE ALL", button -> saveSettings()));
        if (this.selectedRuleIndex >= 0 && this.selectedRuleIndex < rules.size()) {
            loadRuleIntoEditor(rules.get(this.selectedRuleIndex));
        } else {
            this.selectedRuleIndex = -1;
            this.ruleDelayField.setText("0");
        }
    }

    private int drawEditorHintY;

    private void initSettings(int left, int width, int gap) {
        int fieldWidth = Math.max(60, Math.min(100, (width - gap) / 3));
        int rowY = 62;

        this.autoReconnectButton = this.addButton(new ButtonWidget(
                left, rowY, width, 20, getAutoReconnectLabel(),
                button -> {
                    this.draft.setAutoReconnect(!this.draft.isAutoReconnect());
                    this.autoReconnectButton.setMessage(getAutoReconnectLabel());
                }));
        rowY += 30;

        this.reconnectDelayField = addNumberField(left, rowY, fieldWidth,
                this.draft.getReconnectDelaySeconds());
        this.reconnectAttemptsField = addNumberField(
                left + fieldWidth + gap, rowY, fieldWidth,
                this.draft.getMaxReconnectAttempts());
        this.reconnectMaxDelayField = addNumberField(
                left + (fieldWidth + gap) * 2, rowY, fieldWidth,
                this.draft.getMaxReconnectDelaySeconds());
        rowY += 36;

        this.retryBackoffButton = this.addButton(new ButtonWidget(
                left, rowY, width, 20, getRetryBackoffLabel(),
                button -> {
                    this.draft.setRetryBackoff(!this.draft.isRetryBackoff());
                    this.retryBackoffButton.setMessage(getRetryBackoffLabel());
                }));
        rowY += 30;

        this.initialChatDelayField = addNumberField(left, rowY, fieldWidth,
                this.draft.getInitialChatDelaySeconds());
        this.globalChatIntervalField = addNumberField(
                left + fieldWidth + gap, rowY, fieldWidth,
                this.draft.getGlobalChatIntervalSeconds());

        this.addButton(new ButtonWidget(
                left, this.height - 24, width, 20,
                "SAVE SETTINGS", button -> saveSettings()));
    }

    private TextFieldWidget addNumberField(int x, int y, int width, int value) {
        TextFieldWidget field = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer, x, y, width, 20, ""));
        field.setMaxLength(5);
        field.setText(Integer.toString(value));
        return field;
    }

    private String getAutoChatLabel() {
        return "AUTO CHAT: " + (this.draft.isAutoChatEnabled() ? "ON" : "OFF");
    }

    private String getAutoReconnectLabel() {
        return "AUTO RECONNECT: " + (this.draft.isAutoReconnect() ? "ON" : "OFF");
    }

    private String getRetryBackoffLabel() {
        return "RETRY BACKOFF: " + (this.draft.isRetryBackoff() ? "ON" : "OFF");
    }

    private void toggleAutoChat() {
        this.draft.setAutoChatEnabled(!this.draft.isAutoChatEnabled());
        this.autoChatEnabledButton.setMessage(getAutoChatLabel());
        saveSettings();
    }

    private void selectRule(int index) {
        List<SessionAutomationSettings.AutoChatRule> rules = this.draft.getRules();
        if (index < 0 || index >= rules.size()) return;
        this.selectedRuleIndex = index;
        openAutoChatPage(this.rulePage, index);
    }

    private void openRulePage(int page, int selectedIndex) {
        openAutoChatPage(page, selectedIndex);
    }

    private void openAutoChatPage(int page, int selectedIndex) {
        MinecraftClient.getInstance().openScreen(new SessionControlScreen(
                this.parent, this.controller, this.scheduler, this.sessionId,
                this.username, Tab.AUTO_CHAT, this.draft, page, selectedIndex));
    }

    private void loadRuleIntoEditor(SessionAutomationSettings.AutoChatRule rule) {
        this.ruleMessageField.setText(rule.getMessage());
        this.ruleDelayField.setText(Integer.toString(rule.getDelaySeconds()));
        this.editingMode = rule.getMode();
        this.ruleModeButton.setMessage(this.editingMode.name());
        this.ruleSaveButton.setMessage("SAVE RULE");
        this.ruleToggleButton.setMessage(getSelectedRuleEnabledLabel());
        this.ruleToggleButton.active = true;
    }

    private String getSelectedRuleEnabledLabel() {
        if (this.selectedRuleIndex < 0 || this.selectedRuleIndex >= this.draft.getRules().size()) {
            return "ENABLE RULE";
        }
        return this.draft.getRules().get(this.selectedRuleIndex).isEnabled()
                ? "DISABLE RULE" : "ENABLE RULE";
    }

    private void toggleSelectedRule() {
        if (this.selectedRuleIndex < 0 || this.selectedRuleIndex >= this.draft.getRules().size()) return;
        SessionAutomationSettings.AutoChatRule current =
                this.draft.getRules().get(this.selectedRuleIndex);
        this.draft.updateRule(this.selectedRuleIndex,
                new SessionAutomationSettings.AutoChatRule(
                        current.getMessage(), current.getMode(),
                        current.getDelaySeconds(), !current.isEnabled()));
        this.status = "Rule enable state changed in draft. Press SAVE ALL.";
        openAutoChatPage(this.rulePage, this.selectedRuleIndex);
    }

    private void cycleRuleMode() {
        this.editingMode = this.editingMode == SessionAutomationSettings.AutoChatRule.Mode.ONCE
                ? SessionAutomationSettings.AutoChatRule.Mode.REPEAT
                : SessionAutomationSettings.AutoChatRule.Mode.ONCE;
        this.ruleModeButton.setMessage(this.editingMode.name());
        if (this.editingMode == SessionAutomationSettings.AutoChatRule.Mode.REPEAT
                && "0".equals(this.ruleDelayField.getText().trim())) {
            this.ruleDelayField.setText("10");
        }
    }

    private void saveRule() {
        try {
            int delay = parseNumber(this.ruleDelayField, 0);
            SessionAutomationSettings.AutoChatRule rule =
                    new SessionAutomationSettings.AutoChatRule(
                            this.ruleMessageField.getText(), this.editingMode, delay, true);
            if (this.selectedRuleIndex >= 0
                    && this.selectedRuleIndex < this.draft.getRules().size()) {
                this.draft.updateRule(this.selectedRuleIndex, rule);
            } else {
                this.draft.addRule(rule);
                this.selectedRuleIndex = this.draft.getRules().size() - 1;
            }
            this.status = "AutoChat rule saved to draft. Press SAVE ALL to apply.";
            openAutoChatPage(this.rulePage, this.selectedRuleIndex);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        }
    }

    private void deleteRule() {
        if (this.selectedRuleIndex < 0) return;
        try {
            this.draft.removeRule(this.selectedRuleIndex);
            this.selectedRuleIndex = -1;
            this.status = "Rule deleted from draft. Press SAVE ALL to apply.";
            openAutoChatPage(this.rulePage, -1);
        } catch (IndexOutOfBoundsException exception) {
            this.status = "Selected rule no longer exists.";
        }
    }

    private void saveSettings() {
        try {
            if (this.tab == Tab.SETTINGS) {
                this.draft.setReconnectDelaySeconds(parseNumber(this.reconnectDelayField, 10));
                this.draft.setMaxReconnectAttempts(parseNumber(this.reconnectAttemptsField, 10));
                this.draft.setMaxReconnectDelaySeconds(parseNumber(this.reconnectMaxDelayField, 60));
                this.draft.setInitialChatDelaySeconds(parseNumber(this.initialChatDelayField, 5));
                this.draft.setGlobalChatIntervalSeconds(parseNumber(this.globalChatIntervalField, 3));
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
        if (this.ruleMessageField != null) this.ruleMessageField.tick();
        if (this.ruleDelayField != null) this.ruleDelayField.tick();
        if (this.reconnectDelayField != null) this.reconnectDelayField.tick();
        if (this.reconnectAttemptsField != null) this.reconnectAttemptsField.tick();
        if (this.reconnectMaxDelayField != null) this.reconnectMaxDelayField.tick();
        if (this.initialChatDelayField != null) this.initialChatDelayField.tick();
        if (this.globalChatIntervalField != null) this.globalChatIntervalField.tick();
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

    private List<String> getWrappedMessages() {
        List<String> wrapped = new ArrayList<String>();
        int maxCharacters = Math.max(8, (this.width - 24) / 6);
        for (String message : this.controller.getMessages(this.sessionId)) {
            String text = message == null ? "" : message;
            for (int offset = 0; offset < text.length(); offset += maxCharacters) {
                wrapped.add(text.substring(offset, Math.min(text.length(), offset + maxCharacters)));
            }
            if (text.isEmpty()) wrapped.add("");
        }
        int visibleLines = Math.max(1, (this.height - 142) / 11);
        this.scrollOffset = Math.min(this.scrollOffset,
                Math.max(0, wrapped.size() - visibleLines));
        return wrapped;
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

        if (this.tab == Tab.CHAT) {
            renderChat();
        } else if (this.tab == Tab.AUTO_CHAT) {
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    this.draft.getRules().size() + " RULES  |  " + getAutoChatLabel(),
                    this.width / 2, 48, 0xA0A0A0);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    this.status, this.width / 2, this.height - 48, 0xA0A0A0);
        } else {
            drawString(MinecraftClient.getInstance().textRenderer,
                    "RECONNECT DELAY (sec)", 12, 82, 0xA0A0A0);
            drawString(MinecraftClient.getInstance().textRenderer,
                    "MAX ATTEMPTS", Math.max(12, this.width / 2 - 30), 82, 0xA0A0A0);
            drawString(MinecraftClient.getInstance().textRenderer,
                    "MAX DELAY (sec)", this.width - 112, 82, 0xA0A0A0);
            drawString(MinecraftClient.getInstance().textRenderer,
                    "INITIAL AUTOCHAT DELAY", 12, 148, 0xA0A0A0);
            drawString(MinecraftClient.getInstance().textRenderer,
                    "GLOBAL CHAT INTERVAL", this.width / 2 + 8, 148, 0xA0A0A0);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    this.status, this.width / 2, this.height - 48, 0xA0A0A0);
        }

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

        drawString(MinecraftClient.getInstance().textRenderer,
                fitText(this.status, this.width - 24), 12, this.height - 95, 0xA0A0A0);
    }

    private String fitText(String value, int pixelWidth) {
        if (value == null) return "";
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }
}
