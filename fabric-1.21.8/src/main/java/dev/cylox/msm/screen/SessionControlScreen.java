package dev.cylox.msm.screen;

import dev.cylox.msm.MsmClient;
import dev.cylox.msm.automation.SessionAutomationScheduler;
import dev.cylox.msm.automation.SessionAutomationSettings;
import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.session.SessionState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.Collections;
import java.util.List;

public final class SessionControlScreen extends MsmScreen {
    private enum Page {
        CHAT, AUTO_CHAT, SETTINGS
    }

    private final long sessionId;
    private final String username;
    private final Page page;
    private SessionAutomationSettings draft;
    private TextFieldWidget chatField;
    private TextFieldWidget ruleMessageField;
    private TextFieldWidget ruleDelayField;
    private TextFieldWidget reconnectDelayField;
    private TextFieldWidget reconnectAttemptsField;
    private TextFieldWidget reconnectMaxDelayField;
    private TextFieldWidget initialChatDelayField;
    private TextFieldWidget globalChatIntervalField;
    private SessionAutomationSettings.AutoChatRule.Mode ruleMode =
            SessionAutomationSettings.AutoChatRule.Mode.ONCE;
    private int selectedRule = -1;
    private int rulePage;
    private int scrollOffset;
    private List<String> cachedMessages = Collections.emptyList();
    private int cachedMessageCount = -1;
    private String cachedFirstMessage;
    private String cachedLastMessage;
    private String status = "";
    private boolean autoChatEnabled;
    private boolean autoReconnectEnabled;
    private boolean retryBackoff;

    public SessionControlScreen(Screen parent, long sessionId, String username) {
        this(parent, sessionId, username, Page.CHAT, null);
    }

    private SessionControlScreen(Screen parent, long sessionId, String username,
                                 Page page, SessionAutomationSettings draft) {
        super(Text.literal("Control Manage - " + username), parent);
        this.sessionId = sessionId;
        this.username = username == null ? "" : username;
        this.page = page;
        try {
            this.draft = draft == null
                    ? MsmClient.getAutomationScheduler().getSettings(sessionId)
                    : draft.copy();
        } catch (RuntimeException exception) {
            this.draft = new SessionAutomationSettings();
            this.status = "Could not load saved settings: " + safeMessage(exception);
        }
        this.autoChatEnabled = this.draft.isAutoChatEnabled();
        this.autoReconnectEnabled = this.draft.isAutoReconnect();
        this.retryBackoff = this.draft.isRetryBackoff();
    }

    @Override
    protected void init() {
        this.clearChildren();
        int margin = 12;
        int width = Math.max(1, this.width - margin * 2);
        int gap = 5;
        int tabWidth = Math.max(1, (width - gap * 2) / 3);

        this.addAction(margin, 34, tabWidth, 20, "CHAT",
                button -> openPage(Page.CHAT));
        this.addAction(margin + tabWidth + gap, 34, tabWidth, 20, "AUTOCHAT",
                button -> openPage(Page.AUTO_CHAT));
        this.addAction(margin + (tabWidth + gap) * 2, 34,
                width - (tabWidth + gap) * 2, 20, "SETTINGS",
                button -> openPage(Page.SETTINGS));

        addConnectionControls(margin, width);

        if (this.page == Page.CHAT) {
            initChat(margin, width);
            refreshChatMessages();
        } else if (this.page == Page.AUTO_CHAT) {
            initAutoChat(margin, width);
        } else {
            initSettings(margin, width);
        }
    }

    private void addConnectionControls(int margin, int width) {
        SessionState state;
        try {
            state = MsmClient.getSecondarySessions().getState(this.sessionId);
        } catch (RuntimeException exception) {
            state = SessionState.FAILED;
        }
        String label = state == SessionState.ONLINE ? "DISCONNECT"
                : state == SessionState.CONNECTING ? "CANCEL" : "JOIN / CONNECT";
        int leftWidth = Math.max(1, (width - 6) / 2);
        this.addAction(margin, 58, leftWidth, 20, label,
                button -> toggleConnection());
        this.addAction(margin + leftWidth + 6, 58,
                width - leftWidth - 6, 20, "BACK",
                button -> close());
    }

    private void initChat(int margin, int width) {
        int bottom = this.height - 64;
        int fieldWidth = Math.max(1, width - 70);
        this.chatField = this.addDrawableChild(new TextFieldWidget(
                this.textRenderer, margin, bottom, fieldWidth, 20, Text.literal("Message")));
        this.chatField.setMaxLength(256);
        this.chatField.setPlaceholder(Text.literal("Type a chat message"));
        this.addAction(margin + fieldWidth + 6, bottom, width - fieldWidth - 6, 20,
                "SEND", button -> sendChat());
        int half = Math.max(1, (width - 6) / 2);
        this.addAction(margin, this.height - 34, half, 20,
                "OLDER", button -> scrollChat(-5));
        this.addAction(margin + half + 6, this.height - 34,
                width - half - 6, 20, "NEWER", button -> scrollChat(5));
    }

    private void initAutoChat(int margin, int width) {
        int half = Math.max(1, (width - 6) / 2);
        this.addAction(margin, 84, half, 20,
                this.autoChatEnabled ? "AUTOCHAT: ON" : "AUTOCHAT: OFF",
                button -> {
                    this.autoChatEnabled = !this.autoChatEnabled;
                    this.draft.setAutoChatEnabled(this.autoChatEnabled);
                    button.setMessage(Text.literal(this.autoChatEnabled
                            ? "AUTOCHAT: ON" : "AUTOCHAT: OFF"));
                });

        this.globalChatIntervalField = addField(margin + half + 6, 84,
                width - half - 6, "Global interval (sec)",
                Integer.toString(this.draft.getGlobalChatIntervalSeconds()), 5);

        this.ruleMessageField = addField(margin, 116, Math.max(1, width - 86),
                "Message", "", 256);
        this.ruleDelayField = addField(margin + width - 80, 116, 80,
                "Delay", "0", 6);
        int modeWidth = Math.max(1, (width - 6) / 2);
        this.addAction(margin, 148, modeWidth, 20, "MODE: " + this.ruleMode,
                button -> {
                    this.ruleMode = this.ruleMode == SessionAutomationSettings.AutoChatRule.Mode.ONCE
                            ? SessionAutomationSettings.AutoChatRule.Mode.REPEAT
                            : SessionAutomationSettings.AutoChatRule.Mode.ONCE;
                    button.setMessage(Text.literal("MODE: " + this.ruleMode));
                    if (this.ruleMode == SessionAutomationSettings.AutoChatRule.Mode.REPEAT
                            && "0".equals(this.ruleDelayField.getText().trim())) {
                        this.ruleDelayField.setText("10");
                    }
                });
        this.addAction(margin + modeWidth + 6, 148, width - modeWidth - 6, 20,
                "ADD RULE", button -> addRule());

        int listY = 184;
        int rowHeight = 22;
        int listCount = this.draft.getRules().size();
        int pageCount = Math.max(1, (listCount + 3) / 4);
        this.rulePage = Math.max(0, Math.min(this.rulePage, pageCount - 1));
        int start = this.rulePage * 4;
        int end = Math.min(listCount, start + 4);
        for (int i = start; i < end; i++) {
            final int index = i;
            SessionAutomationSettings.AutoChatRule rule = this.draft.getRules().get(i);
            String label = (i == this.selectedRule ? "> " : "")
                    + (rule.isEnabled() ? "[ON] " : "[OFF] ")
                    + rule.getMode() + " | " + rule.getMessage();
            this.addAction(margin, listY, width, 19,
                    fit(label, width - 12), button -> selectRule(index));
            listY += rowHeight;
        }

        if (pageCount > 1) {
            int pageButtonWidth = Math.max(1, (width - 6) / 2);
            this.addAction(margin, this.height - 90, pageButtonWidth, 20,
                    "PREVIOUS RULES", button -> changeRulePage(-1)).active = this.rulePage > 0;
            this.addAction(margin + pageButtonWidth + 6, this.height - 90,
                    width - pageButtonWidth - 6, 20,
                    "NEXT RULES", button -> changeRulePage(1)).active = this.rulePage + 1 < pageCount;
        }

        int actionWidth = Math.max(1, (width - 6) / 2);
        this.addAction(margin, this.height - 64, actionWidth, 20,
                "TOGGLE RULE", button -> toggleSelectedRule()).active =
                this.selectedRule >= 0 && this.selectedRule < listCount;
        this.addAction(margin + actionWidth + 6, this.height - 64,
                width - actionWidth - 6, 20, "DELETE RULE",
                button -> deleteSelectedRule()).active =
                this.selectedRule >= 0 && this.selectedRule < listCount;
        this.addAction(margin, this.height - 34, width, 20,
                "SAVE ALL", button -> saveSettings());
    }

    private void initSettings(int margin, int width) {
        int half = Math.max(1, (width - 6) / 2);
        this.addAction(margin, 84, half, 20,
                this.autoReconnectEnabled ? "AUTORECONNECT: ON" : "AUTORECONNECT: OFF",
                button -> {
                    this.autoReconnectEnabled = !this.autoReconnectEnabled;
                    this.draft.setAutoReconnect(this.autoReconnectEnabled);
                    button.setMessage(Text.literal(this.autoReconnectEnabled
                            ? "AUTORECONNECT: ON" : "AUTORECONNECT: OFF"));
                });
        this.addAction(margin + half + 6, 84, width - half - 6, 20,
                this.retryBackoff ? "RETRY BACKOFF: ON" : "RETRY BACKOFF: OFF",
                button -> {
                    this.retryBackoff = !this.retryBackoff;
                    this.draft.setRetryBackoff(this.retryBackoff);
                    button.setMessage(Text.literal(this.retryBackoff
                            ? "RETRY BACKOFF: ON" : "RETRY BACKOFF: OFF"));
                });

        int gap = 6;
        int columnWidth = Math.max(1, (width - gap) / 2);
        int first = margin;
        int second = margin + columnWidth + gap;
        this.reconnectDelayField = addField(first, 116, columnWidth,
                "Reconnect delay (sec)", Integer.toString(this.draft.getReconnectDelaySeconds()), 6);
        this.reconnectAttemptsField = addField(second, 116, columnWidth,
                "Max attempts", Integer.toString(this.draft.getMaxReconnectAttempts()), 4);
        this.reconnectMaxDelayField = addField(first, 158, columnWidth,
                "Max reconnect delay", Integer.toString(this.draft.getMaxReconnectDelaySeconds()), 6);
        this.initialChatDelayField = addField(second, 158, columnWidth,
                "Initial chat delay", Integer.toString(this.draft.getInitialChatDelaySeconds()), 6);
        this.globalChatIntervalField = addField(first, 200, width,
                "Global chat interval (sec)", Integer.toString(this.draft.getGlobalChatIntervalSeconds()), 6);
        this.addAction(margin, this.height - 34, width, 20,
                "SAVE ALL", button -> saveSettings());
    }

    private TextFieldWidget addField(int x, int y, int width,
                                     String placeholder, String value, int maxLength) {
        TextFieldWidget field = this.addDrawableChild(new TextFieldWidget(
                this.textRenderer, x, y, Math.max(1, width), 20, Text.literal(placeholder)));
        field.setMaxLength(maxLength);
        field.setPlaceholder(Text.literal(placeholder));
        field.setText(value);
        return field;
    }

    private void openPage(Page next) {
        this.client.setScreen(new SessionControlScreen(
                this.parent, this.sessionId, this.username, next, this.draft));
    }

    private void toggleConnection() {
        SessionAutomationScheduler scheduler = MsmClient.getAutomationScheduler();
        try {
            SessionState state = MsmClient.getSecondarySessions().getState(this.sessionId);
            if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
                scheduler.disconnectManually(this.sessionId);
                this.status = "Manual disconnect requested.";
            } else {
                scheduler.connectManually(this.sessionId);
                this.status = "Connection requested.";
            }
        } catch (RuntimeException exception) {
            this.status = safeMessage(exception);
        }
    }

    private void scrollChat(int amount) {
        int count = MsmClient.getSecondarySessions().getMessages(this.sessionId).size();
        int visibleLines = Math.max(1, (this.height - 180) / 11);
        this.scrollOffset = Math.max(0, Math.min(
                Math.max(0, count - visibleLines), this.scrollOffset + amount));
    }

    private void sendChat() {
        if (this.chatField == null) return;
        String message = this.chatField.getText();
        if (message == null || message.trim().isEmpty()) return;
        try {
            MsmClient.getSecondarySessions().sendChat(this.sessionId, message);
            this.chatField.setText("");
            this.status = "Message sent.";
        } catch (RuntimeException exception) {
            this.status = safeMessage(exception);
        }
    }

    private void addRule() {
        try {
            int delay = parseNumber(this.ruleDelayField, 0);
            SessionAutomationSettings.AutoChatRule rule =
                    new SessionAutomationSettings.AutoChatRule(
                            this.ruleMessageField.getText(),
                            this.ruleMode,
                            delay,
                            true);
            this.draft.addRule(rule);
            this.selectedRule = this.draft.getRules().size() - 1;
            this.rulePage = this.selectedRule / 4;
            this.status = "Rule added to draft. Press SAVE ALL to apply.";
            this.init(this.client, this.width, this.height);
        } catch (RuntimeException exception) {
            this.status = safeMessage(exception);
        }
    }

    private void selectRule(int index) {
        this.selectedRule = index;
        this.init(this.client, this.width, this.height);
    }

    private void toggleSelectedRule() {
        if (this.selectedRule < 0 || this.selectedRule >= this.draft.getRules().size()) return;
        SessionAutomationSettings.AutoChatRule current = this.draft.getRules().get(this.selectedRule);
        this.draft.updateRule(this.selectedRule, new SessionAutomationSettings.AutoChatRule(
                current.getMessage(), current.getMode(), current.getDelaySeconds(), !current.isEnabled()));
        this.status = "Rule changed in draft. Press SAVE ALL to apply.";
        this.init(this.client, this.width, this.height);
    }

    private void deleteSelectedRule() {
        if (this.selectedRule < 0 || this.selectedRule >= this.draft.getRules().size()) return;
        this.draft.removeRule(this.selectedRule);
        this.selectedRule = -1;
        this.rulePage = Math.min(this.rulePage, Math.max(0, (this.draft.getRules().size() - 1) / 4));
        this.status = "Rule removed from draft. Press SAVE ALL to apply.";
        this.init(this.client, this.width, this.height);
    }

    private void changeRulePage(int delta) {
        this.rulePage = Math.max(0, this.rulePage + delta);
        this.init(this.client, this.width, this.height);
    }

    private void saveSettings() {
        try {
            if (this.page == Page.AUTO_CHAT) {
                this.draft.setGlobalChatIntervalSeconds(parseNumber(this.globalChatIntervalField, 3));
                this.draft.setAutoChatEnabled(this.autoChatEnabled);
            } else if (this.page == Page.SETTINGS) {
                this.draft.setReconnectDelaySeconds(parseNumber(this.reconnectDelayField, 10));
                this.draft.setMaxReconnectAttempts(parseNumber(this.reconnectAttemptsField, 10));
                this.draft.setMaxReconnectDelaySeconds(parseNumber(this.reconnectMaxDelayField, 60));
                this.draft.setInitialChatDelaySeconds(parseNumber(this.initialChatDelayField, 5));
                this.draft.setGlobalChatIntervalSeconds(parseNumber(this.globalChatIntervalField, 3));
                this.draft.setAutoReconnect(this.autoReconnectEnabled);
                this.draft.setRetryBackoff(this.retryBackoff);
            }
            MsmClient.getAutomationScheduler().saveSettings(this.sessionId, this.draft);
            this.draft = this.draft.copy();
            this.status = "Settings saved.";
        } catch (RuntimeException exception) {
            this.status = "Could not save settings: " + safeMessage(exception);
        }
    }

    private int parseNumber(TextFieldWidget field, int fallback) {
        if (field == null) return fallback;
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Enter a valid number for each delay and limit.");
        }
    }

    private void refreshState() {
        try {
            SessionState state = MsmClient.getSecondarySessions().getState(this.sessionId);
            if (state == SessionState.CONNECTING) {
                this.status = "CONNECTING as " + this.username + "...";
            } else if (state == SessionState.ONLINE) {
                this.status = "ONLINE as " + this.username + ".";
            } else {
                List<String> messages = this.page == Page.CHAT
                        ? this.cachedMessages : Collections.<String>emptyList();
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
        } catch (RuntimeException exception) {
            this.status = "This account is no longer available.";
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.page == Page.CHAT) refreshChatMessages();
        refreshState();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        panel(context, 8, 8, this.width - 8, this.height - 8);
        centered(context, "CONTROL MANAGE  |  " + this.username,
                this.width / 2, 17, 0xFFF1F5FA);

        if (this.page == Page.CHAT) {
            renderChat(context);
            text(context, fit(this.status, this.width - 24), 12, this.height - 80, 0xFF9EADBD);
        } else if (this.page == Page.AUTO_CHAT) {
            text(context, "RULE MESSAGE", 12, 106, 0xFF9EADBD);
            text(context, "DELAY", this.width - 92, 106, 0xFF9EADBD);
            text(context, "SAVED RULES", 12, 174, 0xFF9EADBD);
            centered(context, fit(this.status, this.width - 24),
                    this.width / 2, this.height - 84, 0xFF9EADBD);
        } else {
            text(context, "SECONDS / ATTEMPTS", 12, 105, 0xFF9EADBD);
            centered(context, fit(this.status, this.width - 24),
                    this.width / 2, this.height - 58, 0xFF9EADBD);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void refreshChatMessages() {
        List<String> messages = MsmClient.getSecondarySessions().getMessages(this.sessionId);
        int count = messages.size();
        String first = count == 0 ? null : messages.get(0);
        String last = count == 0 ? null : messages.get(count - 1);
        int visibleLines = Math.max(1, (this.height - 180) / 11);
        this.scrollOffset = Math.min(this.scrollOffset, Math.max(0, count - visibleLines));
        if (count == this.cachedMessageCount
                && equal(first, this.cachedFirstMessage)
                && equal(last, this.cachedLastMessage)) {
            return;
        }

        this.cachedMessages = messages;
        this.cachedMessageCount = count;
        this.cachedFirstMessage = first;
        this.cachedLastMessage = last;
    }

    private static boolean equal(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private void renderChat(DrawContext context) {
        List<String> messages = this.cachedMessages;
        int lineHeight = 11;
        int visibleLines = Math.max(1, (this.height - 180) / lineHeight);
        int end = Math.max(0, messages.size() - this.scrollOffset);
        int start = Math.max(0, end - visibleLines);
        int y = 88;
        for (int i = start; i < end; i++) {
            text(context, fit(messages.get(i), this.width - 24), 12, y, 0xFFE1E7EF);
            y += lineHeight;
        }
        if (messages.isEmpty()) {
            text(context, "Chat history will appear here after the session connects.",
                    12, y, 0xFF9EADBD);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
