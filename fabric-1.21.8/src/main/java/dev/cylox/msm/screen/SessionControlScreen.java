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
    private SessionAutomationSettings draft;
    private SessionAutomationSettings appliedSettings;
    private final int requestedRulePage;

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

    private MsmButtonWidget undoButton;
    private MsmButtonWidget applyButton;
    private MsmButtonWidget closeButton;

    private int selectedRuleIndex;
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
    private int settingsChatY;
    private int settingsFirstX;
    private int settingsSecondX;
    private int settingsThirdX;
    private int settingsFieldWidth;
    private int settingsColumns;
    private int settingsChatLabelY;

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
                scheduler.getSettings(sessionId), 0, -1);
    }

    private SessionControlScreen(Screen parent, SecondarySessionController controller,
                                 SessionAutomationScheduler scheduler,
                                 long sessionId, String username, Tab tab,
                                 SessionAutomationSettings draft,
                                 SessionAutomationSettings appliedSettings,
                                 int rulePage, int selectedRuleIndex) {
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
        this.requestedRulePage = Math.max(0, rulePage);
        this.selectedRuleIndex = selectedRuleIndex;
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
            addTabButton(workspaceLeft + 16, 86, 100, "AUTO CHAT", Tab.AUTO_CHAT);
            addTabButton(workspaceLeft + 16, 114, 100, "SETTINGS", Tab.SETTINGS);
        } else {
            int gap = 5;
            int tabWidth = Math.min(160, Math.max(1, (workspaceWidth - 24 - gap * 2) / 3));
            addTabButton(12, 44, tabWidth, "CHAT", Tab.CHAT);
            addTabButton(12 + tabWidth + gap, 44, tabWidth, "AUTO CHAT", Tab.AUTO_CHAT);
            addTabButton(12 + (tabWidth + gap) * 2, 44, tabWidth,
                    "SETTINGS", Tab.SETTINGS);
        }

        initFooter();

        if (this.tab == Tab.CHAT) {
            initChat();
            refreshWrappedMessages();
        } else if (this.tab == Tab.AUTO_CHAT) {
            initAutoChat();
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
                this.username, next, this.draft, this.appliedSettings,
                this.rulePage, this.selectedRuleIndex));
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

    private void initAutoChat() {
        int left = this.contentLeft + 12;
        int width = Math.max(1, this.contentWidth - 24);
        int gap = 5;
        boolean compact = this.height < 320;
        int rowHeight = compact ? 18 : 20;
        int rowStep = compact ? 20 : 23;
        int pageHeight = compact ? 14 : 16;
        int editorStep = compact ? 20 : 22;
        int editorActionOffset = compact ? 40 : 44;
        int editorHeight = compact ? 18 : 20;
        int toolbarY = this.contentTop + 34;

        int toggleWidth = Math.min(190, width);
        int toggleLeft = left + (width - toggleWidth) / 2;
        this.autoChatEnabledButton = this.addButton(new MsmButtonWidget(
                toggleLeft, toolbarY, toggleWidth, 20, getAutoChatLabel(),
                button -> toggleAutoChat()));

        List<SessionAutomationSettings.AutoChatRule> rules = this.draft.getRules();
        int rowY = toolbarY + 40;
        int maxEditorY = this.contentBottom - (compact ? 58 : 66);
        int rows = Math.max(1, Math.min(4,
                (maxEditorY - rowY - pageHeight - 4) / rowStep));
        this.rulePageSize = rows;

        int pageCount = Math.max(1,
                (rules.size() + this.rulePageSize - 1) / this.rulePageSize);
        this.rulePage = Math.min(this.requestedRulePage, pageCount - 1);
        int start = this.rulePage * this.rulePageSize;
        int end = Math.min(start + this.rulePageSize, rules.size());
        int rowWidth = Math.min(620, width);
        int rowLeft = left + (width - rowWidth) / 2;

        for (int index = start; index < end; index++) {
            final int ruleIndex = index;
            SessionAutomationSettings.AutoChatRule rule = rules.get(index);
            String label = (rule.isEnabled() ? "ON  " : "OFF  ")
                    + rule.getMode().name() + "  " + rule.getDelaySeconds() + "s  "
                    + rule.getMessage();
            MsmButtonWidget row = this.addButton(new MsmButtonWidget(
                    rowLeft, rowY, rowWidth, rowHeight, fitText(label, rowWidth - 14),
                    button -> selectRule(ruleIndex)));
            row.setSelected(ruleIndex == this.selectedRuleIndex);
            rowY += rowStep;
        }

        if (pageCount > 1) {
            int pageWidth = Math.min(96, Math.max(1, (width - gap) / 2));
            ButtonWidget previous = new MsmButtonWidget(
                    rowLeft, rowY, pageWidth, pageHeight, "PREVIOUS",
                    button -> openRulePage(this.rulePage - 1));
            ButtonWidget next = new MsmButtonWidget(
                    rowLeft + pageWidth + gap, rowY, pageWidth, pageHeight, "NEXT",
                    button -> openRulePage(this.rulePage + 1));
            previous.active = this.rulePage > 0;
            next.active = this.rulePage + 1 < pageCount;
            this.addButton(previous);
            this.addButton(next);
            rowY += pageHeight + 2;
        }

        int editorY = Math.min(maxEditorY, Math.max(toolbarY + 44, rowY + 2));
        int editorWidth = Math.min(620, width);
        int editorLeft = left + (width - editorWidth) / 2;
        this.ruleMessageField = this.addButton(new TextFieldWidget(
                this.textRenderer, editorLeft, editorY, editorWidth, editorHeight,
                Text.literal("Chat or command")));
        this.ruleMessageField.setMaxLength(256);

        int delayWidth = Math.min(100, Math.max(64, width / 4));
        int modeWidth = Math.min(170, Math.max(1, editorWidth - delayWidth - gap));
        this.ruleModeButton = this.addButton(new MsmButtonWidget(
                editorLeft, editorY + editorStep, modeWidth, editorHeight, this.editingMode.name(),
                button -> cycleRuleMode()));
        this.ruleDelayField = this.addButton(new TextFieldWidget(
                this.textRenderer, editorLeft + modeWidth + gap, editorY + editorStep,
                delayWidth, editorHeight, Text.literal("Delay")));
        this.ruleDelayField.setMaxLength(5);

        int actionGap = 6;
        int actionGroupWidth = Math.min(320, width);
        int actionLeft = left + (width - actionGroupWidth) / 2;
        int actionWidth = Math.max(1, (actionGroupWidth - actionGap * 2) / 3);
        this.ruleAddButton = this.addButton(new MsmButtonWidget(
                actionLeft, editorY + editorActionOffset, actionWidth, editorHeight,
                "ADD RULE", button -> addRule()));
        this.ruleToggleButton = this.addButton(new MsmButtonWidget(
                actionLeft + actionWidth + actionGap, editorY + editorActionOffset,
                actionWidth, editorHeight, getSelectedRuleEnabledLabel(),
                button -> toggleSelectedRule()));
        this.ruleDeleteButton = this.addButton(new MsmButtonWidget(
                actionLeft + (actionWidth + actionGap) * 2, editorY + editorActionOffset,
                actionWidth, editorHeight, "DELETE", button -> deleteRule()));

        this.ruleDeleteButton.active = this.selectedRuleIndex >= 0
                && this.selectedRuleIndex < rules.size();
        this.ruleToggleButton.active = this.ruleDeleteButton.active;

        if (this.selectedRuleIndex >= 0 && this.selectedRuleIndex < rules.size()) {
            loadRuleIntoEditor(rules.get(this.selectedRuleIndex));
        } else {
            this.selectedRuleIndex = -1;
            this.ruleDelayField.setText("0");
        }
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
        this.settingsChatY = Math.min(this.contentBottom - 20,
                this.settingsRetryY + (veryCompact ? 36 : compact ? 46 : 50));
        this.settingsChatLabelY = this.settingsChatY < this.settingsRetryY + 34
                ? -1 : this.settingsChatY - 13;

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

        int chatFieldWidth = this.settingsColumns == 3
                ? this.settingsFieldWidth : Math.max(1, (width - gap) / 2);
        this.initialChatDelayField = addNumberField(
                this.settingsFirstX, this.settingsChatY,
                chatFieldWidth, this.draft.getInitialChatDelaySeconds());
        this.globalChatIntervalField = addNumberField(
                this.settingsColumns == 3 ? this.settingsSecondX
                        : left + chatFieldWidth + gap,
                this.settingsChatY, chatFieldWidth,
                this.draft.getGlobalChatIntervalSeconds());
    }

    private TextFieldWidget addNumberField(int x, int y, int width, int value) {
        TextFieldWidget field = this.addButton(new TextFieldWidget(
                this.textRenderer, x, y, width, 20, Text.literal("")));
        field.setMaxLength(5);
        field.setText(Integer.toString(value));
        return field;
    }

    private String getAutoChatLabel() {
        return "AUTO CHAT  " + (this.draft.isAutoChatEnabled() ? "[ ON ]" : "[ OFF ]");
    }

    private String getAutoReconnectLabel() {
        return "AUTO RECONNECT  " + (this.draft.isAutoReconnect() ? "[ ON ]" : "[ OFF ]");
    }

    private String getRetryBackoffLabel() {
        return "RETRY BACKOFF  " + (this.draft.isRetryBackoff() ? "[ ON ]" : "[ OFF ]");
    }

    private void toggleAutoChat() {
        this.draft.setAutoChatEnabled(!this.draft.isAutoChatEnabled());
        this.autoChatEnabledButton.setMessage(getAutoChatLabel());
        updateFooterState();
    }

    private void selectRule(int index) {
        if (!commitEditorToDraft()) return;
        List<SessionAutomationSettings.AutoChatRule> rules = this.draft.getRules();
        if (index < 0 || index >= rules.size()) return;
        this.selectedRuleIndex = index;
        openAutoChatPage(this.rulePage);
    }

    private void openRulePage(int page) {
        if (!commitEditorToDraft()) return;
        openAutoChatPage(page);
    }

    private void openAutoChatPage(int page) {
        this.client.setScreen(new SessionControlScreen(
                this.parent, this.controller, this.scheduler, this.sessionId,
                this.username, Tab.AUTO_CHAT, this.draft, this.appliedSettings,
                page, this.selectedRuleIndex));
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
        if (this.selectedRuleIndex < 0
                || this.selectedRuleIndex >= this.draft.getRules().size()) {
            return "ENABLE RULE";
        }
        return this.draft.getRules().get(this.selectedRuleIndex).isEnabled()
                ? "DISABLE RULE" : "ENABLE RULE";
    }

    private void toggleSelectedRule() {
        if (!commitEditorToDraft()) return;
        if (this.selectedRuleIndex < 0
                || this.selectedRuleIndex >= this.draft.getRules().size()) return;

        SessionAutomationSettings.AutoChatRule current =
                this.draft.getRules().get(this.selectedRuleIndex);
        this.draft.updateRule(this.selectedRuleIndex,
                new SessionAutomationSettings.AutoChatRule(
                        current.getMessage(), current.getMode(),
                        current.getDelaySeconds(), !current.isEnabled()));
        this.status = "Rule state changed. Apply to save.";
        openAutoChatPage(this.rulePage);
    }

    private void cycleRuleMode() {
        this.editingMode = this.editingMode
                == SessionAutomationSettings.AutoChatRule.Mode.ONCE
                ? SessionAutomationSettings.AutoChatRule.Mode.REPEAT
                : SessionAutomationSettings.AutoChatRule.Mode.ONCE;
        this.ruleModeButton.setMessage(this.editingMode.name());
        if (this.editingMode == SessionAutomationSettings.AutoChatRule.Mode.REPEAT
                && "0".equals(this.ruleDelayField.getText().trim())) {
            this.ruleDelayField.setText("10");
        }
        updateFooterState();
    }

    private void addRule() {
        if (!commitEditorToDraft()) return;
        try {
            int delay = parseNumber(this.ruleDelayField, 0);
            SessionAutomationSettings.AutoChatRule rule =
                    new SessionAutomationSettings.AutoChatRule(
                            this.ruleMessageField.getText(), this.editingMode,
                            delay, true);
            this.draft.addRule(rule);
            this.selectedRuleIndex = this.draft.getRules().size() - 1;
            this.status = "Rule added. Apply to save.";
            openAutoChatPage(this.rulePage);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        }
    }

    private void deleteRule() {
        if (!commitEditorToDraft()) return;
        if (this.selectedRuleIndex < 0) return;
        try {
            this.draft.removeRule(this.selectedRuleIndex);
            this.selectedRuleIndex = -1;
            this.status = "Rule deleted. Apply to save.";
            openAutoChatPage(this.rulePage);
        } catch (IndexOutOfBoundsException exception) {
            this.status = "Selected rule no longer exists.";
        }
    }

    private boolean commitEditorToDraft() {
        try {
            if (this.tab == Tab.SETTINGS) {
                commitSettingsFields();
            } else if (this.tab == Tab.AUTO_CHAT && this.selectedRuleIndex >= 0) {
                commitEditedRule();
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
                parseNumber(this.reconnectDelayField, 10));
        this.draft.setMaxReconnectAttempts(
                parseNumber(this.reconnectAttemptsField, 10));
        this.draft.setMaxReconnectDelaySeconds(
                parseNumber(this.reconnectMaxDelayField, 60));
        this.draft.setInitialChatDelaySeconds(
                parseNumber(this.initialChatDelayField, 5));
        this.draft.setGlobalChatIntervalSeconds(
                parseNumber(this.globalChatIntervalField, 3));

        setFieldTextIfChanged(this.reconnectDelayField,
                this.draft.getReconnectDelaySeconds());
        setFieldTextIfChanged(this.reconnectAttemptsField,
                this.draft.getMaxReconnectAttempts());
        setFieldTextIfChanged(this.reconnectMaxDelayField,
                this.draft.getMaxReconnectDelaySeconds());
        setFieldTextIfChanged(this.initialChatDelayField,
                this.draft.getInitialChatDelaySeconds());
        setFieldTextIfChanged(this.globalChatIntervalField,
                this.draft.getGlobalChatIntervalSeconds());
    }

    private void commitEditedRule() {
        List<SessionAutomationSettings.AutoChatRule> rules = this.draft.getRules();
        if (this.selectedRuleIndex >= rules.size()) return;

        SessionAutomationSettings.AutoChatRule current =
                rules.get(this.selectedRuleIndex);
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
        }
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
                this.username, this.tab, this.appliedSettings, this.appliedSettings,
                this.rulePage, this.selectedRuleIndex));
    }

    private void updateFooterState() {
        boolean pending = hasPendingChanges();
        if (this.undoButton != null) this.undoButton.active = pending;
        if (this.applyButton != null) this.applyButton.active = pending;
        if (this.closeButton != null) this.closeButton.active = true;
    }

    private boolean hasPendingChanges() {
        if (!settingsEqual(this.draft, this.appliedSettings)) return true;

        if (this.tab == Tab.SETTINGS) {
            return !fieldMatches(this.reconnectDelayField,
                            this.draft.getReconnectDelaySeconds())
                    || !fieldMatches(this.reconnectAttemptsField,
                            this.draft.getMaxReconnectAttempts())
                    || !fieldMatches(this.reconnectMaxDelayField,
                            this.draft.getMaxReconnectDelaySeconds())
                    || !fieldMatches(this.initialChatDelayField,
                            this.draft.getInitialChatDelaySeconds())
                    || !fieldMatches(this.globalChatIntervalField,
                            this.draft.getGlobalChatIntervalSeconds());
        }

        if (this.tab == Tab.AUTO_CHAT && this.selectedRuleIndex >= 0
                && this.selectedRuleIndex < this.draft.getRules().size()) {
            SessionAutomationSettings.AutoChatRule current =
                    this.draft.getRules().get(this.selectedRuleIndex);
            return !fieldText(this.ruleMessageField).trim().equals(current.getMessage())
                    || !fieldMatches(this.ruleDelayField, current.getDelaySeconds())
                    || this.editingMode != current.getMode();
        }
        return false;
    }

    private static boolean settingsEqual(SessionAutomationSettings first,
                                          SessionAutomationSettings second) {
        if (first.isAutoReconnect() != second.isAutoReconnect()
                || first.getReconnectDelaySeconds() != second.getReconnectDelaySeconds()
                || first.getMaxReconnectAttempts() != second.getMaxReconnectAttempts()
                || first.isRetryBackoff() != second.isRetryBackoff()
                || first.getMaxReconnectDelaySeconds() != second.getMaxReconnectDelaySeconds()
                || first.isAutoChatEnabled() != second.isAutoChatEnabled()
                || first.getInitialChatDelaySeconds() != second.getInitialChatDelaySeconds()
                || first.getGlobalChatIntervalSeconds() != second.getGlobalChatIntervalSeconds()) {
            return false;
        }

        List<SessionAutomationSettings.AutoChatRule> firstRules = first.getRules();
        List<SessionAutomationSettings.AutoChatRule> secondRules = second.getRules();
        if (firstRules.size() != secondRules.size()) return false;
        for (int index = 0; index < firstRules.size(); index++) {
            SessionAutomationSettings.AutoChatRule a = firstRules.get(index);
            SessionAutomationSettings.AutoChatRule b = secondRules.get(index);
            if (!a.getMessage().equals(b.getMessage())
                    || a.getMode() != b.getMode()
                    || a.getDelaySeconds() != b.getDelaySeconds()
                    || a.isEnabled() != b.isEnabled()) {
                return false;
            }
        }
        return true;
    }

    private static boolean fieldMatches(TextFieldWidget field, int value) {
        return field == null || Integer.toString(value).equals(field.getText().trim());
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

        String title = this.tab == Tab.CHAT ? "CHAT"
                : this.tab == Tab.AUTO_CHAT ? "AUTO CHAT" : "SETTINGS";
        String subtitle = this.tab == Tab.CHAT
                ? "Messages and connection controls"
                : this.tab == Tab.AUTO_CHAT
                ? "Manage automatic chat messages"
                : "Configure reconnect and chat behavior";

        this.text(context, title, this.contentLeft + 12,
                this.contentTop + 9, 0xFFF1F5F9);
        this.text(context, subtitle, this.contentLeft + 12,
                this.contentTop + 22, 0xFF9EADBD);

        if (this.tab == Tab.CHAT) {
            drawPanel(context, this.contentLeft + 12, this.chatPanelTop,
                    this.contentLeft + this.contentWidth - 12,
                    this.chatPanelBottom, 0x50101822);
        } else if (this.tab == Tab.AUTO_CHAT) {
            int left = this.contentLeft + 12;
            this.text(context, "MESSAGE RULES", left,
                    this.contentTop + 58, 0xFF9EADBD);
            if (this.draft.getRules().isEmpty()) {
                this.text(context, "No rules yet. Add one below.",
                        left, this.contentTop + 68, 0xFF9EADBD);
            }
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
            if (this.settingsChatLabelY >= 0) {
                if (this.settingsChatY >= this.settingsRetryY + 46) {
                    this.text(context, "CHAT AUTOMATION", this.contentLeft + 12,
                            this.settingsChatY - 26, 0xFF9EADBD);
                }
                this.text(context, "INITIAL CHAT DELAY",
                        this.settingsFirstX, this.settingsChatLabelY,
                        0xFF9EADBD);
                this.text(context, "GLOBAL CHAT INTERVAL",
                        this.settingsColumns == 3 ? this.settingsSecondX
                                : this.settingsFirstX + Math.max(1,
                                (this.contentWidth - 24 - 8) / 2) + 8,
                        this.settingsChatLabelY, 0xFF9EADBD);
            }
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
