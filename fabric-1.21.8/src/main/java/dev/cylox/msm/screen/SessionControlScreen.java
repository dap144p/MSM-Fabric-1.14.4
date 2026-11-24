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
    private MsmButtonWidget sendButton;
    private MsmButtonWidget connectionButton;

    private TextFieldWidget ruleMessageField;
    private TextFieldWidget ruleDelayField;
    private MsmButtonWidget ruleModeButton;
    private MsmButtonWidget autoChatEnabledButton;
    private MsmButtonWidget ruleAddButton;
    private MsmButtonWidget ruleToggleButton;
    private MsmButtonWidget ruleDeleteButton;

    private MsmButtonWidget autoReconnectButton;
    private MsmButtonWidget retryBackoffButton;
    private TextFieldWidget reconnectDelayField;
    private TextFieldWidget reconnectAttemptsField;
    private TextFieldWidget reconnectMaxDelayField;
    private TextFieldWidget initialChatDelayField;
    private TextFieldWidget globalChatIntervalField;

    private int selectedRuleIndex;
    private int scrollOffset;
    private int settingsPanelLeft;
    private int settingsPanelWidth;
    private int settingsFirstX;
    private int settingsSecondX;
    private int settingsThirdX;
    private int settingsFieldWidth;
    private int settingsReconnectY;
    private int settingsMaxDelayY;
    private int settingsChatY;
    private List<String> wrappedMessages = Collections.emptyList();
    private int cachedMessageCount = -1;
    private int cachedMessageWidth = -1;
    private String cachedFirstMessage;
    private String cachedLastMessage;

    private static final int AUTO_SAVE_DELAY_TICKS = 8;
    private int autoSaveTicks;
    private String lastAutoSaveSnapshot;
    private String autoSaveStatus = "SAVED";

    public SessionControlScreen(Screen parent, long sessionId, String username) {
        this(parent, MsmClient.getSecondarySessions(),
                MsmClient.getAutomationScheduler(), sessionId, username);
    }

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
        super(Text.literal("Control Manage - " + username), parent);
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
        this.addButton(new MsmButtonWidget(
                left + (tabWidth + gap) * 3, 28, tabWidth, 20,
                "BACK", button -> close()));

        if (this.tab == Tab.CHAT) {
            initChat(left, contentWidth, gap);
            refreshWrappedMessages();
        } else if (this.tab == Tab.AUTO_CHAT) {
            initAutoChat(left, contentWidth, gap);
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
        flushPendingAutoSave();
        this.client.setScreen(new SessionControlScreen(
                this.parent, this.controller, this.scheduler, this.sessionId,
                this.username, next, this.draft, this.rulePage, this.selectedRuleIndex));
    }

    private void initChat(int left, int width, int gap) {
        int sendWidth = Math.min(64, Math.max(42, width / 6));
        sendWidth = Math.min(sendWidth, Math.max(1, width - gap - 1));
        int fieldWidth = Math.max(1, width - sendWidth - gap);
        this.chatField = this.addButton(new TextFieldWidget(
                this.textRenderer,
                left, this.height - 76, fieldWidth, 20, Text.literal("Message")));
        this.chatField.setMaxLength(256);
        this.sendButton = this.addButton(new MsmButtonWidget(
                left + fieldWidth + gap, this.height - 76, sendWidth, 20,
                "SEND", button -> sendChat()));

        this.connectionButton = this.addButton(new MsmButtonWidget(
                left, this.height - 24, width, 20,
                "JOIN / CONNECT", button -> toggleConnection()));
        updateConnectionControls();
    }

    private void initAutoChat(int left, int width, int gap) {
        this.autoChatEnabledButton = this.addButton(new MsmButtonWidget(
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
            this.addButton(new MsmButtonWidget(
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
            ButtonWidget previous = new MsmButtonWidget(
                    left, rowY, pageWidth, 18,
                    "PREVIOUS", button -> openRulePage(this.rulePage - 1, this.selectedRuleIndex));
            ButtonWidget next = new MsmButtonWidget(
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
                this.textRenderer,
                left, editorY, width, 20, Text.literal("Chat or command")));
        this.ruleMessageField.setMaxLength(256);

        int delayWidth = Math.min(90, Math.max(58, width / 4));
        int modeWidth = Math.max(1, width - delayWidth - gap);
        this.ruleModeButton = this.addButton(new MsmButtonWidget(
                left, editorY + 25, modeWidth, 20, editingMode.name(),
                button -> cycleRuleMode()));
        this.ruleDelayField = this.addButton(new TextFieldWidget(
                this.textRenderer,
                left + modeWidth + gap, editorY + 25, delayWidth, 20, Text.literal("Delay")));
        this.ruleDelayField.setMaxLength(5);

        int editorButtonWidth = Math.max(1, (width - gap * 2) / 3);
        this.ruleAddButton = this.addButton(new MsmButtonWidget(
                left, editorY + 50, editorButtonWidth, 20,
                "ADD RULE", button -> addRule()));
        this.ruleToggleButton = this.addButton(new MsmButtonWidget(
                left + editorButtonWidth + gap, editorY + 50, editorButtonWidth, 20,
                getSelectedRuleEnabledLabel(), button -> toggleSelectedRule()));
        this.ruleDeleteButton = this.addButton(new MsmButtonWidget(
                left + (editorButtonWidth + gap) * 2, editorY + 50,
                editorButtonWidth, 20, "DELETE", button -> deleteRule()));

        this.ruleDeleteButton.active = this.selectedRuleIndex >= 0;
        this.ruleToggleButton.active = this.selectedRuleIndex >= 0;
        if (this.selectedRuleIndex >= 0 && this.selectedRuleIndex < rules.size()) {
            loadRuleIntoEditor(rules.get(this.selectedRuleIndex));
        } else {
            this.selectedRuleIndex = -1;
            this.ruleDelayField.setText("0");
        }
        this.lastAutoSaveSnapshot = getAutoSaveSnapshot();
    }

    private int drawEditorHintY;

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
        this.settingsChatY = retryY + 34;

        this.autoReconnectButton = this.addButton(new MsmButtonWidget(
                this.settingsPanelLeft, 58, this.settingsPanelWidth, 20, getAutoReconnectLabel(),
                button -> {
                    flushPendingAutoSave();
                    this.draft.setAutoReconnect(!this.draft.isAutoReconnect());
                    this.autoReconnectButton.setMessage(getAutoReconnectLabel());
                    saveSettings();
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
                this.settingsPanelLeft, retryY, this.settingsPanelWidth, 20, getRetryBackoffLabel(),
                button -> {
                    flushPendingAutoSave();
                    this.draft.setRetryBackoff(!this.draft.isRetryBackoff());
                    this.retryBackoffButton.setMessage(getRetryBackoffLabel());
                    saveSettings();
                }));

        this.initialChatDelayField = addNumberField(
                this.settingsFirstX, this.settingsChatY,
                this.settingsFieldWidth, this.draft.getInitialChatDelaySeconds());
        this.globalChatIntervalField = addNumberField(
                this.settingsSecondX, this.settingsChatY,
                this.settingsFieldWidth, this.draft.getGlobalChatIntervalSeconds());
        this.lastAutoSaveSnapshot = getAutoSaveSnapshot();
    }

    private TextFieldWidget addNumberField(int x, int y, int width, int value) {
        TextFieldWidget field = this.addButton(new TextFieldWidget(
                this.textRenderer, x, y, width, 20, Text.literal("")));
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
        flushPendingAutoSave();
        this.draft.setAutoChatEnabled(!this.draft.isAutoChatEnabled());
        this.autoChatEnabledButton.setMessage(getAutoChatLabel());
        saveSettings();
    }

    private void selectRule(int index) {
        flushPendingAutoSave();
        List<SessionAutomationSettings.AutoChatRule> rules = this.draft.getRules();
        if (index < 0 || index >= rules.size()) return;
        this.selectedRuleIndex = index;
        openAutoChatPage(this.rulePage, index);
    }

    private void openRulePage(int page, int selectedIndex) {
        openAutoChatPage(page, selectedIndex);
    }

    private void openAutoChatPage(int page, int selectedIndex) {
        flushPendingAutoSave();
        this.client.setScreen(new SessionControlScreen(
                this.parent, this.controller, this.scheduler, this.sessionId,
                this.username, Tab.AUTO_CHAT, this.draft, page, selectedIndex));
    }

    private void loadRuleIntoEditor(SessionAutomationSettings.AutoChatRule rule) {
        this.ruleMessageField.setText(rule.getMessage());
        this.ruleDelayField.setText(Integer.toString(rule.getDelaySeconds()));
        this.editingMode = rule.getMode();
        this.ruleModeButton.setMessage(this.editingMode.name());
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
        flushPendingAutoSave();
        if (this.selectedRuleIndex < 0 || this.selectedRuleIndex >= this.draft.getRules().size()) return;
        SessionAutomationSettings.AutoChatRule current =
                this.draft.getRules().get(this.selectedRuleIndex);
        this.draft.updateRule(this.selectedRuleIndex,
                new SessionAutomationSettings.AutoChatRule(
                        current.getMessage(), current.getMode(),
                        current.getDelaySeconds(), !current.isEnabled()));
        saveSettings();
        this.status = "Rule enable state saved.";
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

    private void addRule() {
        flushPendingAutoSave();
        try {
            int delay = parseNumber(this.ruleDelayField, 0);
            SessionAutomationSettings.AutoChatRule rule =
                    new SessionAutomationSettings.AutoChatRule(
                            this.ruleMessageField.getText(), this.editingMode, delay, true);
            this.draft.addRule(rule);
            this.selectedRuleIndex = this.draft.getRules().size() - 1;
            saveSettings();
            this.status = "AutoChat rule added and saved.";
            openAutoChatPage(this.rulePage, this.selectedRuleIndex);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
            this.autoSaveStatus = "ERROR";
        }
    }

    private void deleteRule() {
        flushPendingAutoSave();
        if (this.selectedRuleIndex < 0) return;
        try {
            this.draft.removeRule(this.selectedRuleIndex);
            this.selectedRuleIndex = -1;
            saveSettings();
            this.status = "Rule deleted and saved.";
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
                setFieldTextIfChanged(this.reconnectDelayField, this.draft.getReconnectDelaySeconds());
                setFieldTextIfChanged(this.reconnectAttemptsField, this.draft.getMaxReconnectAttempts());
                setFieldTextIfChanged(this.reconnectMaxDelayField, this.draft.getMaxReconnectDelaySeconds());
                setFieldTextIfChanged(this.initialChatDelayField, this.draft.getInitialChatDelaySeconds());
                setFieldTextIfChanged(this.globalChatIntervalField, this.draft.getGlobalChatIntervalSeconds());
            }
            this.scheduler.saveSettings(this.sessionId, this.draft);
            this.autoSaveTicks = 0;
            if (this.tab == Tab.SETTINGS) {
                this.autoSaveStatus = "SAVED";
                this.lastAutoSaveSnapshot = getAutoSaveSnapshot();
            }
            this.status = "Changes saved automatically.";
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.autoSaveStatus = "ERROR";
            this.status = exception.getMessage();
        }
    }

    private String getAutoSaveSnapshot() {
        if (this.tab == Tab.SETTINGS) {
            return this.draft.isAutoReconnect() + "|"
                    + this.draft.isRetryBackoff() + "|"
                    + fieldText(this.reconnectDelayField) + "|"
                    + fieldText(this.reconnectAttemptsField) + "|"
                    + fieldText(this.reconnectMaxDelayField) + "|"
                    + fieldText(this.initialChatDelayField) + "|"
                    + fieldText(this.globalChatIntervalField);
        }
        if (this.tab == Tab.AUTO_CHAT) {
            return this.selectedRuleIndex + "|" + this.editingMode + "|"
                    + fieldText(this.ruleMessageField) + "|"
                    + fieldText(this.ruleDelayField);
        }
        return "";
    }

    private static String fieldText(TextFieldWidget field) {
        return field == null ? "" : field.getText();
    }

    private static void setFieldTextIfChanged(TextFieldWidget field, int value) {
        String normalized = Integer.toString(value);
        if (field != null && !normalized.equals(field.getText())) {
            field.setText(normalized);
        }
    }

    private void tickAutoSave() {
        if (this.tab != Tab.SETTINGS
                && (this.tab != Tab.AUTO_CHAT || this.selectedRuleIndex < 0)) {
            return;
        }

        String snapshot = getAutoSaveSnapshot();
        if (this.lastAutoSaveSnapshot == null) {
            this.lastAutoSaveSnapshot = snapshot;
        } else if (!snapshot.equals(this.lastAutoSaveSnapshot)) {
            this.lastAutoSaveSnapshot = snapshot;
            this.autoSaveTicks = AUTO_SAVE_DELAY_TICKS;
            this.autoSaveStatus = "PENDING";
        }

        if (this.autoSaveTicks > 0 && --this.autoSaveTicks == 0) {
            if (this.tab == Tab.SETTINGS) {
                saveSettings();
            } else {
                saveEditedRule();
            }
        }
    }

    private void flushPendingAutoSave() {
        if (this.tab != Tab.SETTINGS
                && (this.tab != Tab.AUTO_CHAT || this.selectedRuleIndex < 0)) {
            return;
        }
        String snapshot = getAutoSaveSnapshot();
        boolean changed = !snapshot.equals(this.lastAutoSaveSnapshot);
        if (!changed && this.autoSaveTicks <= 0) return;

        this.autoSaveTicks = 0;
        if (this.tab == Tab.SETTINGS) {
            saveSettings();
        } else {
            saveEditedRule();
        }
    }

    private void saveEditedRule() {
        if (this.ruleMessageField == null || this.ruleDelayField == null
                || this.selectedRuleIndex < 0) {
            return;
        }

        try {
            List<SessionAutomationSettings.AutoChatRule> rules = this.draft.getRules();
            if (this.selectedRuleIndex >= rules.size()) return;

            SessionAutomationSettings.AutoChatRule current = rules.get(this.selectedRuleIndex);
            SessionAutomationSettings.AutoChatRule updated =
                    new SessionAutomationSettings.AutoChatRule(
                            this.ruleMessageField.getText(), this.editingMode,
                            parseNumber(this.ruleDelayField, 0), current.isEnabled());
            setFieldTextIfChanged(this.ruleDelayField, updated.getDelaySeconds());
            boolean unchanged = current.getMessage().equals(updated.getMessage())
                    && current.getMode() == updated.getMode()
                    && current.getDelaySeconds() == updated.getDelaySeconds();
            if (!unchanged) {
                this.draft.updateRule(this.selectedRuleIndex, updated);
                this.scheduler.saveSettings(this.sessionId, this.draft);
                this.status = "AutoChat rule saved automatically.";
            }
            this.autoSaveTicks = 0;
            this.autoSaveStatus = "SAVED";
            this.lastAutoSaveSnapshot = getAutoSaveSnapshot();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.autoSaveStatus = "ERROR";
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
        tickAutoSave();
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
    public void close() {
        flushPendingAutoSave();
        this.client.setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderMsmBackground(context, mouseX, mouseY, delta);
        this.drawGlassPanel(context, 8, 6, this.width - 8, 52);
        this.drawGlassPanel(context, 8, 54, this.width - 8, this.height - 8);
        context.drawCenteredTextWithShadow(this.textRenderer,
                this.title, this.width / 2, 10, 0xFFFFFF);

        if (this.tab == Tab.CHAT) {
            this.drawGlassPanel(context, 16, 52, this.width - 16, this.height - 104);
        } else if (this.tab == Tab.AUTO_CHAT) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(this.draft.getRules().size() + " RULES  |  "
                            + getAutoChatLabel() + "  |  AUTO SAVE: " + this.autoSaveStatus),
                    this.width / 2, 48, 0xA0A0A0);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(fitText(this.status, this.width - 24)),
                    this.width / 2, this.height - 48, 0xA0A0A0);
        } else {
            this.text(context, "RECONNECT DELAY", this.settingsFirstX,
                    this.settingsReconnectY - 12, 0xFF9EADBD);
            this.text(context, "MAX ATTEMPTS", this.settingsSecondX,
                    this.settingsReconnectY - 12, 0xFF9EADBD);
            this.text(context, "MAX DELAY", this.settingsThirdX,
                    this.settingsMaxDelayY - 12, 0xFF9EADBD);
            this.text(context, "INITIAL AUTOCHAT DELAY", this.settingsFirstX,
                    this.settingsChatY - 12, 0xFF9EADBD);
            this.text(context, "GLOBAL CHAT INTERVAL", this.settingsSecondX,
                    this.settingsChatY - 12, 0xFF9EADBD);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(fitText(this.status + "  |  AUTO SAVE: "
                            + this.autoSaveStatus, this.width - 24)),
                    this.width / 2, this.height - 48, 0xA0A0A0);
        }

        super.render(context, mouseX, mouseY, delta);
        if (this.tab == Tab.CHAT) {
            renderChat(context);
        }
    }

    private void renderChat(DrawContext context) {
        List<String> messages = getWrappedMessages();
        int lineHeight = 11;
        int visibleLines = Math.max(1, (this.height - 142) / lineHeight);
        int end = Math.max(0, messages.size() - this.scrollOffset);
        int start = Math.max(0, end - visibleLines);
        int y = 56;
        for (int index = start; index < end; index++) {
            context.drawTextWithShadow(this.textRenderer,
                    messages.get(index), 12, y, 0xFFE0E0E0);
            y += lineHeight;
        }

        this.text(context, fitText(this.status, this.width - 24),
                12, this.height - 95, 0xFFA0A0A0);
    }

    private String fitText(String value, int pixelWidth) {
        if (value == null) return "";
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }
}
