package dev.cylox.msm.screen;

import dev.cylox.msm.MsmClient;
import dev.cylox.msm.automation.SessionAutomationScheduler;
import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

public final class SessionManagerScreen extends MsmScreen {
    private static final int PAGE_SIZE = 6;
    private static final String DEFAULT_STATUS =
            "Add an account once, then add servers to it. Select a server to connect or manage.";

    private final List<SessionManager.Session> sessions = new ArrayList<>();
    private final Map<Long, MsmButtonWidget> rowButtons = new LinkedHashMap<>();
    private final Map<String, MsmButtonWidget> accountButtons = new LinkedHashMap<>();
    private final List<AccountGroup> accountGroups = new ArrayList<>();
    private final List<AccountListEntry> accountRows = new ArrayList<>();

    private final int requestedPage;
    private int accountsLabelY;
    private int rowStartY;
    private int accountsPanelBottom;
    private int formPanelLeft;
    private int formPanelWidth;
    private int accountsPanelLeft;
    private int accountsPanelWidth;
    private boolean compactLayout;
    private String expandedAccountKey;

    private TextFieldWidget usernameField;
    private TextFieldWidget serverField;
    private MsmButtonWidget authModeButton;
    private MsmButtonWidget addAccountButton;
    private MsmButtonWidget connectionButton;
    private MsmButtonWidget controlButton;
    private MsmButtonWidget removeButton;

    private volatile String status;
    private SessionState lastObservedState;
    private long selectedSessionId;
    private int pageIndex;
    private int pageSize = PAGE_SIZE;
    private SessionAuthMode authMode;
    private String usernameValue;
    private String serverValue;

    public SessionManagerScreen(Screen parent) {
        this(parent, DEFAULT_STATUS, 0, -1L, null,
                SessionAuthMode.OFFLINE_LOCAL, "", "");
    }

    private SessionManagerScreen(Screen parent, String status, int page,
                                 long selectedSessionId, String expandedAccountKey,
                                 SessionAuthMode authMode,
                                 String usernameValue, String serverValue) {
        super(Text.literal("Multi Session Manager"), parent);
        this.status = status == null ? DEFAULT_STATUS : status;
        this.requestedPage = Math.max(0, page);
        this.selectedSessionId = selectedSessionId;
        this.expandedAccountKey = expandedAccountKey;
        this.authMode = authMode == null ? SessionAuthMode.OFFLINE_LOCAL : authMode;
        this.usernameValue = usernameValue == null ? "" : usernameValue;
        this.serverValue = serverValue == null ? "" : serverValue;
        refreshSessions();
    }

    private void refreshSessions() {
        this.sessions.clear();
        this.accountGroups.clear();
        this.accountRows.clear();

        List<SessionProfile> profiles = MsmClient.getProfiles();
        Map<String, AccountGroup> grouped = new LinkedHashMap<>();
        for (SessionManager.Session session : MsmClient.getSessionManager().getSessions()) {
            if (!profiles.contains(session.getProfile())) continue;
            this.sessions.add(session);

            SessionProfile profile = session.getProfile();
            String key = accountKey(profile);
            AccountGroup group = grouped.get(key);
            if (group == null) {
                group = new AccountGroup(key, profile.getUsername(), profile.getAuthMode());
                grouped.put(key, group);
            }
            group.servers.add(session);
        }

        this.accountGroups.addAll(grouped.values());
        if (this.expandedAccountKey != null && !grouped.containsKey(this.expandedAccountKey)) {
            this.expandedAccountKey = null;
        }
        for (AccountGroup group : this.accountGroups) {
            this.accountRows.add(new AccountListEntry(group, null));
            if (group.key.equals(this.expandedAccountKey)) {
                for (SessionManager.Session session : group.servers) {
                    this.accountRows.add(new AccountListEntry(group, session));
                }
            }
        }

        for (Map.Entry<String, MsmButtonWidget> entry : this.accountButtons.entrySet()) {
            AccountGroup group = grouped.get(entry.getKey());
            if (group != null) {
                entry.getValue().setMessage(getAccountGroupLabel(group));
                entry.getValue().setSelected(group.key.equals(this.expandedAccountKey));
            }
        }
        for (Map.Entry<Long, MsmButtonWidget> entry : this.rowButtons.entrySet()) {
            Optional<SessionManager.Session> session =
                    MsmClient.getSessionManager().find(entry.getKey());
            if (session.isPresent() && profiles.contains(session.get().getProfile())) {
                entry.getValue().setMessage(getServerLabel(session.get(), entry.getKey()));
                entry.getValue().setSelected(entry.getKey() == this.selectedSessionId);
            }
        }
        if (this.selectedSessionId >= 0L
                && !MsmClient.getSessionManager().find(this.selectedSessionId).isPresent()) {
            this.selectedSessionId = -1L;
        }
        refreshControls();
    }

    private static String accountKey(SessionProfile profile) {
        return profile.getAuthMode().name() + ":" + profile.getUsername().toLowerCase(Locale.ROOT);
    }

    private AccountGroup findAccountGroup(String username, SessionAuthMode mode) {
        if (username == null || username.trim().isEmpty() || mode == null) return null;
        String key = mode.name() + ":" + username.trim().toLowerCase(Locale.ROOT);
        for (AccountGroup group : this.accountGroups) {
            if (group.key.equals(key)) return group;
        }
        return null;
    }

    private String getAccountGroupLabel(AccountGroup group) {
        int online = 0;
        for (SessionManager.Session server : group.servers) {
            if (server.getState() == SessionState.ONLINE) online++;
        }
        String prefix = group.key.equals(this.expandedAccountKey) ? "> " : "";
        String label = prefix + group.username + "  ["
                + group.servers.size() + " SERVERS | " + online + " ONLINE]";
        int availableWidth = this.accountsPanelWidth > 0
                ? this.accountsPanelWidth - 34 : this.width - 42;
        return fitText(label, availableWidth);
    }

    private String getServerLabel(SessionManager.Session session, long id) {
        String prefix = id == this.selectedSessionId ? "  > " : "    ";
        String label = prefix + session.getProfile().getServerAddress()
                + "  [" + session.getState().name() + "]";
        int availableWidth = this.accountsPanelWidth > 0
                ? this.accountsPanelWidth - 34 : this.width - 42;
        return fitText(label, availableWidth);
    }

    private Optional<SessionManager.Session> getSelectedSession() {
        return this.selectedSessionId < 0L
                ? Optional.empty() : MsmClient.getSessionManager().find(this.selectedSessionId);
    }

    private void refreshControls() {
        Optional<SessionManager.Session> selected = getSelectedSession();
        if (selected.isPresent()) {
            SessionManager.Session session = selected.get();
            SessionState currentState = session.getState();
            if (this.lastObservedState != currentState) {
                SessionState previousState = this.lastObservedState;
                this.lastObservedState = currentState;
                if (currentState == SessionState.CONNECTING) {
                    this.status = "CONNECTING as " + session.getProfile().getUsername() + "...";
                } else if (currentState == SessionState.ONLINE) {
                    this.status = "ONLINE as " + session.getProfile().getUsername() + ".";
                } else if (previousState == SessionState.CONNECTING
                        || previousState == SessionState.ONLINE
                        || currentState == SessionState.FAILED) {
                    this.status = getLatestConnectionMessage(session.getId());
                }
            }
        } else {
            this.lastObservedState = null;
        }

        if (this.connectionButton != null) {
            if (!selected.isPresent()) {
                this.connectionButton.setMessage("JOIN / CONNECT");
                this.connectionButton.active = false;
            } else {
                SessionState state = selected.get().getState();
                this.connectionButton.setMessage(
                        state == SessionState.CONNECTING ? "CANCEL"
                                : state == SessionState.ONLINE ? "DISCONNECT" : "JOIN / CONNECT");
                this.connectionButton.active = true;
            }
        }
        if (this.controlButton != null) {
            this.controlButton.active = selected.isPresent();
        }
        if (this.removeButton != null) {
            boolean removable = false;
            if (selected.isPresent()) {
                SessionState state = selected.get().getState();
                removable = state != SessionState.CONNECTING && state != SessionState.ONLINE;
            }
            this.removeButton.active = removable;
        }
    }

    private String getLatestConnectionMessage(long sessionId) {
        List<String> messages = MsmClient.getSecondarySessions().getMessages(sessionId);
        for (int index = messages.size() - 1; index >= 0; index--) {
            String message = messages.get(index);
            if (message.startsWith("[MSM] Disconnected during ")
                    || message.startsWith("[MSM] Login rejected:")
                    || message.startsWith("[MSM] Server disconnected:")
                    || message.startsWith("[MSM] AutoReconnect")) {
                return message;
            }
        }
        return "Session is disconnected. Open CONTROL MANAGE for details.";
    }

    @Override
    protected void init() {
        this.clearChildren();
        this.rowButtons.clear();
        this.accountButtons.clear();
        this.compactLayout = this.width <= this.height;

        int margin = this.compactLayout ? 10 : 16;
        int contentLeft = margin;
        int contentWidth = Math.max(1, this.width - margin * 2);
        int gap = 12;
        int fieldGap = 6;

        if (this.compactLayout) {
            this.formPanelLeft = contentLeft;
            this.formPanelWidth = contentWidth;
            this.accountsPanelLeft = contentLeft;
            this.accountsPanelWidth = contentWidth;
            this.accountsLabelY = 190;
            this.rowStartY = 214;
            this.accountsPanelBottom = Math.max(this.rowStartY + 32, this.height - 135);

            int innerLeft = contentLeft + 12;
            int innerWidth = Math.max(1, contentWidth - 24);
            int halfWidth = Math.max(1, (innerWidth - fieldGap) / 2);

            this.usernameField = this.addField(
                    innerLeft, 54, innerWidth, 20, "Account username");
            this.usernameField.setMaxLength(16);
            this.usernameField.setText(this.usernameValue);

            this.serverField = this.addField(
                    innerLeft, 94, innerWidth, 20, "Server address");
            this.serverField.setMaxLength(255);
            this.serverField.setPlaceholder(Text.literal("server.example"));
            this.serverField.setText(this.serverValue);

            this.authModeButton = this.addButton(new MsmButtonWidget(
                    innerLeft, 126, halfWidth, 20,
                    getAuthModeLabel(), button -> cycleAuthMode()));
            this.authModeButton.active = false;
            this.addAccountButton = this.addButton(new MsmButtonWidget(
                    innerLeft + halfWidth + fieldGap, 126,
                    innerWidth - halfWidth - fieldGap, 20,
                    getAddAccountLabel(), button -> addAccount()));
        } else {
            this.formPanelLeft = contentLeft;
            this.formPanelWidth = Math.min(330,
                    Math.max(190, (contentWidth - gap) * 34 / 100));
            this.accountsPanelLeft = this.formPanelLeft + this.formPanelWidth + gap;
            this.accountsPanelWidth = Math.max(1,
                    contentLeft + contentWidth - this.accountsPanelLeft);
            this.accountsLabelY = 51;
            this.rowStartY = 78;
            this.accountsPanelBottom = this.height - 94;

            int innerLeft = this.formPanelLeft + 12;
            int innerWidth = Math.max(1, this.formPanelWidth - 24);
            int halfWidth = Math.max(1, (innerWidth - fieldGap) / 2);

            this.usernameField = this.addField(
                    innerLeft, 88, innerWidth, 20, "Account username");
            this.usernameField.setMaxLength(16);
            this.usernameField.setText(this.usernameValue);

            this.serverField = this.addField(
                    innerLeft, 132, innerWidth, 20, "Server address");
            this.serverField.setMaxLength(255);
            this.serverField.setPlaceholder(Text.literal("server.example"));
            this.serverField.setText(this.serverValue);

            this.authModeButton = this.addButton(new MsmButtonWidget(
                    innerLeft, 174, halfWidth, 20,
                    getAuthModeLabel(), button -> cycleAuthMode()));
            this.authModeButton.active = false;
            this.addAccountButton = this.addButton(new MsmButtonWidget(
                    innerLeft + halfWidth + fieldGap, 174,
                    innerWidth - halfWidth - fieldGap, 20,
                    getAddAccountLabel(), button -> addAccount()));
        }

        int pageButtonY = this.compactLayout
                ? this.accountsPanelBottom - 25 : this.height - 122;
        int availableRows = Math.max(1, (pageButtonY - this.rowStartY - 4) / 23 + 1);
        this.pageSize = Math.min(PAGE_SIZE, availableRows);

        int pageCount = Math.max(1, (this.accountRows.size() + this.pageSize - 1) / this.pageSize);
        this.pageIndex = Math.min(this.requestedPage, pageCount - 1);
        int start = this.pageIndex * this.pageSize;
        int end = Math.min(start + this.pageSize, this.accountRows.size());
        int rowY = this.rowStartY;

        for (int index = start; index < end; index++) {
            AccountListEntry entry = this.accountRows.get(index);
            if (entry.server == null) {
                AccountGroup group = entry.account;
                MsmButtonWidget row = new MsmButtonWidget(
                        this.accountsPanelLeft + 10, rowY,
                        Math.max(1, this.accountsPanelWidth - 20), 20,
                        getAccountGroupLabel(group),
                        button -> toggleAccountGroup(group.key));
                row.setSelected(group.key.equals(this.expandedAccountKey));
                this.accountButtons.put(group.key, row);
                this.addButton(row);
            } else {
                SessionManager.Session session = entry.server;
                MsmButtonWidget row = new MsmButtonWidget(
                        this.accountsPanelLeft + 10, rowY,
                        Math.max(1, this.accountsPanelWidth - 20), 20,
                        getServerLabel(session, session.getId()),
                        button -> selectServer(session, entry.account.key));
                row.setSelected(session.getId() == this.selectedSessionId);
                this.rowButtons.put(session.getId(), row);
                this.addButton(row);
            }
            rowY += 23;
        }

        if (pageCount > 1) {
            int pageWidth = Math.min(100, Math.max(1, (this.accountsPanelWidth - 30) / 2));
            int pageLeft = this.accountsPanelLeft
                    + (this.accountsPanelWidth - pageWidth * 2 - 8) / 2;
            MsmButtonWidget previous = new MsmButtonWidget(
                    pageLeft, pageButtonY, pageWidth, 18,
                    "PREVIOUS", button -> openPage(this.pageIndex - 1));
            MsmButtonWidget next = new MsmButtonWidget(
                    pageLeft + pageWidth + 8, pageButtonY, pageWidth, 18,
                    "NEXT", button -> openPage(this.pageIndex + 1));
            previous.active = this.pageIndex > 0;
            next.active = this.pageIndex + 1 < pageCount;
            this.addButton(previous);
            this.addButton(next);
        }

        if (this.compactLayout) {
            int actionGap = 6;
            int actionWidth = Math.max(1, (contentWidth - actionGap) / 2);
            int firstRowY = this.height - 76;
            int secondRowY = this.height - 50;
            this.connectionButton = this.addButton(new MsmButtonWidget(
                    contentLeft, firstRowY, actionWidth, 20,
                    (contentWidth < 540 ? "JOIN" : "JOIN / CONNECT"),
                    button -> toggleConnection()));
            this.controlButton = this.addButton(new MsmButtonWidget(
                    contentLeft + actionWidth + actionGap, firstRowY,
                    contentWidth - actionWidth - actionGap, 20,
                    (contentWidth < 540 ? "CONTROL" : "CONTROL MANAGE"),
                    button -> openControlManage()));
            this.removeButton = this.addButton(new MsmButtonWidget(
                    contentLeft, secondRowY, actionWidth, 20,
                    "REMOVE SERVER", button -> requestRemoveServer()));
            this.removeButton.setDanger(true);
            this.addButton(new MsmButtonWidget(
                    contentLeft + actionWidth + actionGap, secondRowY,
                    contentWidth - actionWidth - actionGap, 20,
                    "CLOSE", button -> close()));
        } else {
            int actionGap = 6;
            int actionWidth = Math.max(1, (contentWidth - actionGap * 3) / 4);
            int actionY = this.height - 28;
            this.connectionButton = this.addButton(new MsmButtonWidget(
                    contentLeft, actionY, actionWidth, 20,
                    (contentWidth < 540 ? "JOIN" : "JOIN / CONNECT"),
                    button -> toggleConnection()));
            this.controlButton = this.addButton(new MsmButtonWidget(
                    contentLeft + actionWidth + actionGap, actionY, actionWidth, 20,
                    (contentWidth < 540 ? "CONTROL" : "CONTROL MANAGE"),
                    button -> openControlManage()));
            this.removeButton = this.addButton(new MsmButtonWidget(
                    contentLeft + (actionWidth + actionGap) * 2, actionY,
                    actionWidth, 20, (contentWidth < 540 ? "REMOVE" : "REMOVE SERVER"),
                    button -> requestRemoveServer()));
            this.removeButton.setDanger(true);
            this.addButton(new MsmButtonWidget(
                    contentLeft + (actionWidth + actionGap) * 3, actionY,
                    actionWidth, 20, "CLOSE", button -> close()));
        }

        this.addButton(new MsmButtonWidget(
                this.width - 78, 6, 70, 18, "UPDATES",
                button -> openUpdateScreen()));
        refreshControls();
    }

    private void openUpdateScreen() {
        if (MsmClient.getUpdateManager() == null) {
            this.status = "Updater is unavailable.";
            return;
        }
        this.client.setScreen(new MsmUpdateScreen(
                this, MsmClient.getUpdateManager()));
    }

    private void snapshotFormValues() {
        if (this.usernameField != null) this.usernameValue = this.usernameField.getText();
        if (this.serverField != null) this.serverValue = this.serverField.getText();
    }

    private String getAddAccountLabel() {
        return findAccountGroup(this.usernameValue, this.authMode) == null
                ? "ADD ACCOUNT" : "ADD SERVER";
    }

    private void updateAddAccountLabel() {
        if (this.addAccountButton != null) {
            this.addAccountButton.setMessage(getAddAccountLabel());
        }
    }

    private void toggleAccountGroup(String key) {
        AccountGroup group = null;
        for (AccountGroup candidate : this.accountGroups) {
            if (candidate.key.equals(key)) {
                group = candidate;
                break;
            }
        }
        if (group == null) return;

        snapshotFormValues();
        if (key.equals(this.expandedAccountKey)) {
            this.expandedAccountKey = null;
            Optional<SessionManager.Session> selected = getSelectedSession();
            if (selected.isPresent()
                    && accountKey(selected.get().getProfile()).equals(key)) {
                this.selectedSessionId = -1L;
            }
        } else {
            this.expandedAccountKey = key;
            this.selectedSessionId = -1L;
            this.lastObservedState = null;
            this.usernameValue = group.username;
            this.serverValue = "";
            this.authMode = group.authMode;
        }
        openScreen("Account " + group.username + " selected.",
                0, this.selectedSessionId);
    }

    private void selectServer(SessionManager.Session session, String accountKey) {
        this.expandedAccountKey = accountKey;
        this.selectedSessionId = session.getId();
        this.lastObservedState = null;
        this.status = "Selected " + session.getProfile().getUsername()
                + " on " + session.getProfile().getServerAddress() + ".";
        refreshSessions();
    }

    private void addAccount() {
        snapshotFormValues();
        try {
            AccountGroup existing = findAccountGroup(this.usernameValue, this.authMode);
            String username = existing == null ? this.usernameValue : existing.username;
            SessionProfile profile = new SessionProfile(
                    username, this.serverValue, this.authMode);
            if (MsmClient.getProfiles().contains(profile)) {
                this.status = "This server is already added to this account.";
                return;
            }

            SessionManager.Session created = MsmClient.addProfile(profile);
            this.expandedAccountKey = accountKey(profile);
            refreshSessions();

            int selectedIndex = -1;
            for (int index = 0; index < this.accountRows.size(); index++) {
                AccountListEntry entry = this.accountRows.get(index);
                if (entry.server != null && entry.server.getId() == created.getId()) {
                    selectedIndex = index;
                    break;
                }
            }
            int targetPage = Math.max(0,
                    selectedIndex / Math.max(1, this.pageSize));
            String message = existing == null
                    ? "Account " + profile.getUsername() + " created."
                    : "Server " + profile.getServerAddress()
                        + " added to " + profile.getUsername() + ".";
            openScreen(message, targetPage, created.getId());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        } catch (IOException exception) {
            this.status = "Could not save server: " + exception.getMessage();
        }
    }

    private void requestRemoveServer() {
        Optional<SessionManager.Session> selected = getSelectedSession();
        if (!selected.isPresent()) {
            this.status = "Select a server first.";
            return;
        }

        SessionManager.Session session = selected.get();
        SessionState state = session.getState();
        if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
            this.status = "Disconnect this server before removing it.";
            return;
        }

        SessionProfile profile = session.getProfile();
        this.client.setScreen(new ConfirmRemoveAccountScreen(
                this, session.getId(), profile.getUsername(), profile.getServerAddress()));
    }

    private void selectServer(SessionManager.Session session, String accountKey) {
        this.expandedAccountKey = accountKey;
        this.selectedSessionId = session.getId();
        this.lastObservedState = null;
        this.status = "Selected " + session.getProfile().getUsername()
                + " on " + session.getProfile().getServerAddress() + ".";
        refreshSessions();
    }

    private void toggleConnection() {
        Optional<SessionManager.Session> selected = getSelectedSession();
        if (!selected.isPresent()) {
            this.status = "Select a server first.";
            return;
        }

        SessionManager.Session session = selected.get();
        SessionAutomationScheduler scheduler = MsmClient.getAutomationScheduler();
        try {
            SessionState state = session.getState();
            if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
                scheduler.disconnectManually(session.getId());
                this.status = "Disconnect requested for "
                        + session.getProfile().getServerAddress() + ".";
            } else {
                scheduler.connectManually(session.getId());
                this.status = "Joining " + session.getProfile().getServerAddress()
                        + " as " + session.getProfile().getUsername() + "...";
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        }
        refreshSessions();
    }

    private void openControlManage() {
        Optional<SessionManager.Session> selected = getSelectedSession();
        if (!selected.isPresent()) {
            this.status = "Select a server first.";
            return;
        }
        if (MsmClient.getAutomationScheduler() == null) {
            this.status = "Automation scheduler is unavailable.";
            return;
        }

        SessionManager.Session session = selected.get();
        this.client.setScreen(new SessionControlScreen(
                this, session.getId(), session.getProfile().getUsername()));
    }

    private void cycleAuthMode() {
        this.authMode = this.authMode == SessionAuthMode.OFFLINE_LOCAL
                ? SessionAuthMode.MICROSOFT : SessionAuthMode.OFFLINE_LOCAL;
        this.authModeButton.setMessage(getAuthModeLabel());
        this.status = "Authentication: " + getAuthModeLabel() + ".";
    }

    private String getAuthModeLabel() {
        return this.authMode == SessionAuthMode.OFFLINE_LOCAL ? "OFFLINE" : "MICROSOFT";
    }

    private void openPage(int page) {
        snapshotFormValues();
        openScreen(this.status, page, this.selectedSessionId);
    }

    private void openScreen(String newStatus, int page, long selectedId) {
        snapshotFormValues();
        this.client.setScreen(new SessionManagerScreen(
                this.parent, newStatus, page, selectedId, this.expandedAccountKey,
                this.authMode, this.usernameValue, this.serverValue));
    }

    private String fitText(String value, int pixelWidth) {
        return fit(value, pixelWidth);
    }

    public void setStatus(String status) {
        this.status = status == null ? "" : status;
        refreshSessions();
        this.init(this.client, this.width, this.height);
    }

    @Override
    public void tick() {
        super.tick();
        snapshotFormValues();
        refreshSessions();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderMsmBackground(context, mouseX, mouseY, delta);
        this.drawGlassDivider(context, 12, 28, this.width - 12);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("MULTI SESSION MANAGER"), this.width / 2, 11, 0xFFF1F5FA);

        int formBottom = this.compactLayout ? 174 : 212;
        int accountsTop = this.compactLayout ? 181 : 36;
        this.drawGlassPanel(context, this.formPanelLeft, 36,
                this.formPanelLeft + this.formPanelWidth, formBottom);
        this.drawGlassPanel(context, this.accountsPanelLeft, accountsTop,
                this.accountsPanelLeft + this.accountsPanelWidth, this.accountsPanelBottom);

        if (this.compactLayout) {
            this.drawSectionTitle(context, "ADD ACCOUNT", this.formPanelLeft + 12, 40);
            this.drawSectionTitle(context, "SERVER ADDRESS", this.formPanelLeft + 12, 82);
            this.drawSectionTitle(context, "ACCOUNTS  (" + this.accountGroups.size() + ")",
                    this.accountsPanelLeft + 12, this.accountsLabelY);
            if (this.sessions.isEmpty()) {
                context.drawCenteredTextWithShadow(this.textRenderer,
                        Text.literal("No accounts added yet."), this.width / 2,
                        this.rowStartY + 14, 0xFF9EADBD);
            }
            this.drawGlassPanel(context, 10, this.height - 130, this.width - 10, this.height - 82);
        } else {
            this.drawSectionTitle(context, "ADD ACCOUNT", this.formPanelLeft + 12, 51);
            this.drawSectionTitle(context, "USERNAME", this.formPanelLeft + 12, 73);
            this.drawSectionTitle(context, "SERVER ADDRESS", this.formPanelLeft + 12, 117);
            this.drawSectionTitle(context, "ACCOUNTS  (" + this.accountGroups.size() + ")",
                    this.accountsPanelLeft + 12, this.accountsLabelY);
            if (this.sessions.isEmpty()) {
                context.drawCenteredTextWithShadow(this.textRenderer,
                        Text.literal("No accounts added yet."),
                        this.accountsPanelLeft + this.accountsPanelWidth / 2,
                        this.rowStartY + 14, 0xFF9EADBD);
            }
            this.drawGlassPanel(context, 16, this.height - 88, this.width - 16, this.height - 54);
        }

        Optional<SessionManager.Session> selected = getSelectedSession();
        String selectedText = selected.isPresent()
                ? "SELECTED  " + selected.get().getProfile().getUsername()
                    + "  |  " + selected.get().getProfile().getServerAddress()
                    + "  |  " + selected.get().getState().name()
                : this.expandedAccountKey != null
                    ? "SELECTED ACCOUNT  " + this.usernameValue
                    : "NO ACCOUNT SELECTED";
        int selectedY = this.compactLayout ? this.height - 119 : this.height - 79;
        int statusY = this.compactLayout ? this.height - 102 : this.height - 65;
        this.text(context, fitText(selectedText, this.width - 32), 24, selectedY, 0xFFE7EDF5);
        this.text(context, fitText(this.status, this.width - 32), 24, statusY, 0xFF9EADBD);

        super.render(context, mouseX, mouseY, delta);
    }

    private static final class AccountGroup {
        private final String key;
        private final String username;
        private final SessionAuthMode authMode;
        private final List<SessionManager.Session> servers = new ArrayList<>();

        private AccountGroup(String key, String username, SessionAuthMode authMode) {
            this.key = key;
            this.username = username;
            this.authMode = authMode;
        }
    }

    private static final class AccountListEntry {
        private final AccountGroup account;
        private final SessionManager.Session server;

        private AccountListEntry(AccountGroup account, SessionManager.Session server) {
            this.account = account;
            this.server = server;
        }
    }
}
