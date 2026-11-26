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

public final class SessionManagerScreen extends MsmScreen {
    @Override
    protected String getMsmPageTitle() {
        return this.addDialog
                ? (this.manageView ? "ADD SERVER" : "ADD ACCOUNT")
                : (this.manageView ? "MANAGE ACCOUNT" : "ACCOUNT MANAGER");
    }

    private static final int PAGE_SIZE = 6;
    private static final String DEFAULT_STATUS =
            "Add an account once, then add servers to it. Select a server to connect or manage.";

    private final List<SessionManager.Session> sessions = new ArrayList<>();
    private final Map<Long, MsmButtonWidget> rowButtons = new LinkedHashMap<>();
    private final Map<String, MsmButtonWidget> accountButtons = new LinkedHashMap<>();
    private final Map<Long, MsmButtonWidget> serverManageButtons = new LinkedHashMap<>();
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

    private TextFieldWidget searchField;
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
    private int pageCount;
    private int scrollButtonX;
    private int scrollTrackTop;
    private int scrollTrackBottom;
    private SessionAuthMode authMode;
    private String usernameValue;
    private String serverValue;
    private String searchValue = "";
    private boolean manageView;
    private boolean addDialog;

    public SessionManagerScreen(Screen parent) {
        this(parent, DEFAULT_STATUS, 0, -1L, null, false, false, "",
                SessionAuthMode.OFFLINE_LOCAL, "", "");
    }

    private SessionManagerScreen(Screen parent, String status, int page,
                                 long selectedSessionId, String expandedAccountKey,
                                 boolean manageView, boolean addDialog, String searchValue,
                                 SessionAuthMode authMode,
                                 String usernameValue, String serverValue) {
        super(Text.literal("Multi Session Manager"), parent);
        this.status = status == null ? DEFAULT_STATUS : status;
        this.requestedPage = Math.max(0, page);
        this.selectedSessionId = selectedSessionId;
        this.expandedAccountKey = expandedAccountKey;
        this.manageView = manageView;
        this.addDialog = addDialog;
        this.searchValue = searchValue == null ? "" : searchValue;
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
        if (this.manageView && (this.expandedAccountKey == null
                || !grouped.containsKey(this.expandedAccountKey))) {
            this.manageView = false;
            this.expandedAccountKey = null;
        }

        String filter = this.searchValue == null ? "" : this.searchValue.trim().toLowerCase();
        for (AccountGroup group : this.accountGroups) {
            if (this.manageView) {
                if (group.key.equals(this.expandedAccountKey)) {
                    for (SessionManager.Session session : group.servers) {
                        this.accountRows.add(new AccountListEntry(group, session));
                    }
                }
            } else if (filter.isEmpty() || group.username.toLowerCase().contains(filter)) {
                this.accountRows.add(new AccountListEntry(group, null));
            }
        }

        for (Map.Entry<String, MsmButtonWidget> entry : this.accountButtons.entrySet()) {
            AccountGroup group = grouped.get(entry.getKey());
            if (group != null) {
                entry.getValue().setMessage(getAccountGroupLabel(group));
                entry.getValue().setTrailingLabel(getAccountServerSummary(group));
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
        for (Map.Entry<Long, MsmButtonWidget> entry : this.serverManageButtons.entrySet()) {
            entry.getValue().active = MsmClient.getSessionManager().find(entry.getKey()).isPresent();
        }
        if (this.selectedSessionId >= 0L
                && !MsmClient.getSessionManager().find(this.selectedSessionId).isPresent()) {
            this.selectedSessionId = -1L;
        }
        refreshControls();
    }

        private static String accountKey(SessionProfile profile) {
        return profile.getAuthMode().name() + ":" + profile.getUsername();
    }

    private AccountGroup findAccountGroup(String username, SessionAuthMode mode) {
        if (username == null || username.trim().isEmpty() || mode == null) return null;
        String key = mode.name() + ":" + username.trim();
        for (AccountGroup group : this.accountGroups) {
            if (group.key.equals(key)) return group;
        }
        return null;
    }

    private String getAccountGroupLabel(AccountGroup group) {
        return group.username;
    }

    private String getAccountServerSummary(AccountGroup group) {
        int online = 0;
        for (SessionManager.Session server : group.servers) {
            if (server.getState() == SessionState.ONLINE) online++;
        }
        return "[SERVER " + online + "/" + group.servers.size() + "]";
    }

    private String getServerLabel(SessionManager.Session session, long id) {
        return fitText(session.getProfile().getServerAddress()
                + "   [" + session.getState().name() + "]",
                this.accountsPanelWidth > 0 ? this.accountsPanelWidth - 118 : this.width - 150);
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

        updateAddAccountLabel();
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
        this.connectionButton = null;
        this.removeButton = null;
        this.controlButton = null;
        refreshSessions();
        this.serverManageButtons.clear();

        int contentWidth = Math.max(1, this.manageView
                ? Math.min(this.width - 24, 560) : this.width - 24);
        int contentLeft = this.manageView ? Math.max(12, (this.width - contentWidth) / 2) : 12;
        this.accountsPanelLeft = contentLeft;
        this.accountsPanelWidth = contentWidth;
        this.rowStartY = 96;
        this.accountsPanelBottom = this.height - (this.manageView ? 58 : 40);

        if (this.addDialog) {
            int dialogWidth = Math.min(380, Math.max(250, this.width - 32));
            int left = (this.width - dialogWidth) / 2;
            int innerLeft = left + 14;
            int innerWidth = dialogWidth - 28;
            int top = Math.max(34, (this.height - 154) / 2);
            this.usernameField = this.addField(innerLeft, top + 34, innerWidth, 20, "Account username");
            this.usernameField.setMaxLength(16);
            this.usernameField.setText(this.manageView ? accountUsername(this.expandedAccountKey) : this.usernameValue);
            this.usernameField.setEditable(!this.manageView);
            this.serverField = this.addField(innerLeft, top + 64, innerWidth, 20, "Server address");
            this.serverField.setMaxLength(255);
            this.serverField.setPlaceholder(Text.literal("server.example"));
            this.serverField.setText(this.serverValue);
            int authWidth = 86;
            this.authModeButton = this.addButton(new MsmButtonWidget(
                    innerLeft, top + 98, authWidth, 20, "OFFLINE", button -> { }));
            this.authModeButton.active = false;
            this.addAccountButton = this.addButton(new MsmButtonWidget(
                    innerLeft + authWidth + 8, top + 98, innerWidth - authWidth - 8, 20,
                    this.manageView ? "ADD SERVER" : "ADD ACCOUNT", button -> addAccount()));
            this.addButton(new MsmButtonWidget(
                    left + dialogWidth - 80, top + 8, 66, 18, "CANCEL",
                    button -> this.client.setScreen(this.parent)));
            return;
        }

        if (!this.manageView) {
            int searchWidth = Math.max(1, contentWidth - 60);
            this.searchField = this.addField(
                    this.accountsPanelLeft + 42, 36, searchWidth, 20, "Search accounts...");
            this.searchField.setMaxLength(64);
            this.searchField.setText(this.searchValue);
        }

        int panelTop = this.manageView ? 42 : 66;
        this.scrollButtonX = this.accountsPanelLeft + this.accountsPanelWidth - 18;
        this.scrollTrackTop = panelTop + 24;
        this.scrollTrackBottom = this.accountsPanelBottom - 22;
        int listBottom = this.accountsPanelBottom - 28;
        int availableRows = Math.max(1, (listBottom - this.rowStartY) / 24 + 1);
        this.pageSize = Math.min(PAGE_SIZE, availableRows);
        this.pageCount = Math.max(1, (this.accountRows.size() + this.pageSize - 1) / this.pageSize);
        this.pageIndex = Math.min(this.requestedPage, this.pageCount - 1);
        int start = this.pageIndex * this.pageSize;
        int end = Math.min(start + this.pageSize, this.accountRows.size());
        int rowY = this.rowStartY;
        int rowWidth = Math.max(1, this.manageView
                ? Math.min(520, contentWidth - 44) : contentWidth - 24);
        int rowLeft = this.accountsPanelLeft + 12;
        int actionWidth = 58;
        int labelWidth = Math.max(1, rowWidth - actionWidth - 8);

        for (int index = start; index < end; index++) {
            AccountListEntry entry = this.accountRows.get(index);
            if (!this.manageView && entry.server == null) {
                AccountGroup group = entry.account;
                int manageWidth = 58;
                int deleteWidth = 20;
                int accountGap = 6;
                int accountLabelWidth = Math.max(1,
                        rowWidth - manageWidth - deleteWidth - accountGap * 2);
                MsmButtonWidget delete = new MsmButtonWidget(
                        rowLeft, rowY, deleteWidth, 20, "",
                        button -> requestDeleteAccount(group.key));
                delete.setTrashIcon(true);
                this.addButton(delete);
                MsmButtonWidget account = new MsmButtonWidget(
                        rowLeft + deleteWidth + accountGap, rowY, accountLabelWidth, 20,
                        getAccountGroupLabel(group),
                        button -> openAccountManage(group.key));
                account.setTrailingLabel(getAccountServerSummary(group));
                this.accountButtons.put(group.key, account);
                this.addButton(account);
                this.addButton(new MsmButtonWidget(
                        rowLeft + deleteWidth + accountGap + accountLabelWidth + accountGap,
                        rowY + 1, manageWidth, 18,
                        "MANAGE", button -> openAccountManage(group.key)));
            } else if (entry.server != null) {
                SessionManager.Session session = entry.server;
                MsmButtonWidget server = new MsmButtonWidget(
                        rowLeft, rowY, labelWidth, 20, getServerLabel(session, session.getId()),
                        button -> selectServer(session, entry.account.key));
                server.setSelected(session.getId() == this.selectedSessionId);
                this.rowButtons.put(session.getId(), server);
                this.addButton(server);
                MsmButtonWidget manage = new MsmButtonWidget(
                        rowLeft + labelWidth + 8, rowY + 1, actionWidth, 18,
                        "MANAGE", button -> openControlManageFor(session));
                this.serverManageButtons.put(session.getId(), manage);
                this.addButton(manage);
            }
            rowY += 24;
        }

        if (this.pageCount > 1) {
            MsmButtonWidget previous = new MsmButtonWidget(
                    this.scrollButtonX, panelTop + 6, 14, 14, "^",
                    button -> openPage(this.pageIndex - 1));
            MsmButtonWidget next = new MsmButtonWidget(
                    this.scrollButtonX, this.accountsPanelBottom - 19, 14, 14, "v",
                    button -> openPage(this.pageIndex + 1));
            previous.active = this.pageIndex > 0;
            next.active = this.pageIndex + 1 < this.pageCount;
            this.addButton(previous);
            this.addButton(next);
        }

        int footerY = this.height - 28;
        int gap = 5;
        if (this.manageView) {
            int groupWidth = Math.min(500, contentWidth);
            int left = this.width - 12 - groupWidth;
            int width = Math.max(1, (groupWidth - gap * 3) / 4);
            this.addButton(new MsmButtonWidget(left, footerY, width, 20, "ADD SERVER",
                    button -> openAddDialog()));
            this.connectionButton = this.addButton(new MsmButtonWidget(
                    left + width + gap, footerY, width, 20, "JOIN", button -> toggleConnection()));
            this.removeButton = this.addButton(new MsmButtonWidget(
                    left + (width + gap) * 2, footerY, width, 20, "REMOVE",
                    button -> requestRemoveServer()));
            this.removeButton.setDanger(true);
            this.addButton(new MsmButtonWidget(left + (width + gap) * 3, footerY, width, 20,
                    "BACK", button -> this.client.setScreen(this.parent)));
        } else {
            boolean showUpdates = MsmClient.getUpdateManager() != null;
            int updateWidth = showUpdates ? 82 : 0;
            int addWidth = 78;
            int groupWidth = updateWidth + (showUpdates ? gap : 0) + addWidth;
            int left = this.accountsPanelLeft + this.accountsPanelWidth - 12 - groupWidth;
            if (showUpdates) {
                this.addButton(new MsmButtonWidget(left, footerY, updateWidth, 20,
                        "UPDATES", button -> openUpdateScreen()));
            }
            int addLeft = left + updateWidth + (showUpdates ? gap : 0);
            this.addButton(new MsmButtonWidget(addLeft, footerY, addWidth, 20, "ADD",
                    button -> openAddDialog()));
        }
        refreshControls();
    }

    private String accountUsername(String key) {
        if (key == null) return this.usernameValue;
        for (AccountGroup group : this.accountGroups) {
            if (group.key.equals(key)) return group.username;
        }
        return this.usernameValue;
    }

    private void openAccountManage(String key) {
        this.snapshotFormValues();
        String username = accountUsername(key);
        this.client.setScreen(new SessionManagerScreen(
                this, "Manage account " + username + ".", 0, -1L, key,
                true, false, "", SessionAuthMode.OFFLINE_LOCAL, username, ""));
    }

    private void openAddDialog() {
        String username = this.manageView ? accountUsername(this.expandedAccountKey) : "";
        this.client.setScreen(new SessionManagerScreen(
                this, this.status, this.pageIndex, this.selectedSessionId, this.expandedAccountKey,
                this.manageView, true, this.searchValue,
                SessionAuthMode.OFFLINE_LOCAL, username, ""));
    }

    private void openControlManageFor(SessionManager.Session session) {
        if (session == null) return;
        this.selectedSessionId = session.getId();
        openControlManage();
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
        openAccountManage(key);
    }

        private void selectServer(SessionManager.Session session, String accountKey) {
        this.expandedAccountKey = accountKey;
        this.selectedSessionId = session.getId();
        this.lastObservedState = null;
        this.status = "Selected " + session.getProfile().getUsername()
                + " on " + session.getProfile().getServerAddress() + ".";
        this.usernameValue = session.getProfile().getUsername();
        this.serverValue = "";
        this.authMode = session.getProfile().getAuthMode();
        if (this.usernameField != null) this.usernameField.setText(this.usernameValue);
        if (this.serverField != null) this.serverField.setText("");
        if (this.authModeButton != null) {
            this.authModeButton.setMessage(getAuthModeLabel());
        }
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
            this.manageView = true;
            this.addDialog = false;
            this.searchValue = "";
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
            this.usernameValue = profile.getUsername();
            this.serverValue = "";
            if (this.usernameField != null) this.usernameField.setText(this.usernameValue);
            if (this.serverField != null) this.serverField.setText("");
            openScreen(message, targetPage, created.getId());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        } catch (IOException exception) {
            this.status = "Could not save server: " + exception.getMessage();
        }
    }

    private void requestDeleteAccount(String targetAccountKey) {
        for (AccountGroup group : this.accountGroups) {
            if (group.key.equals(targetAccountKey)) {
                final String username = group.username;
                final List<SessionManager.Session> targets = new ArrayList<>(group.servers);
                if (targets.isEmpty()) {
                    this.status = "This account has no saved servers.";
                    return;
                }
                this.client.setScreen(new ConfirmRemoveAccountScreen(
                        this, username, targets.size(),
                        () -> beginDeleteAccount(username, targets)));
                return;
            }
        }
        this.status = "The selected account is no longer available.";
    }

    private void beginDeleteAccount(String username, List<SessionManager.Session> targets) {
        this.client.setScreen(this);
        this.status = "Disconnecting and deleting all servers for " + username + "...";
        SessionAutomationScheduler scheduler = MsmClient.getAutomationScheduler();
        if (scheduler == null) {
            this.status = "Account deletion is unavailable because automation is not initialized.";
            return;
        }
        removeNextAccountServer(username, targets, 0, 0, null);
    }

    private void removeNextAccountServer(String username, List<SessionManager.Session> targets,
                                         int index, int removed, Throwable firstFailure) {
        if (index >= targets.size()) {
            if (removed == targets.size() && firstFailure == null) {
                String message = "Deleted account " + username + " and all "
                        + targets.size() + " saved servers.";
                this.openScreen(message, this.pageIndex, -1L);
            } else {
                String message = "Account deletion incomplete: removed " + removed
                        + " of " + targets.size() + " servers";
                if (firstFailure != null && firstFailure.getMessage() != null) {
                    message += ". " + firstFailure.getMessage();
                }
                this.status = message + ".";
                this.client.setScreen(this);
            }
            return;
        }
        long sessionId = targets.get(index).getId();
        MsmClient.removeProfile(sessionId, failure ->
                removeNextAccountServer(username, targets, index + 1,
                        failure == null ? removed + 1 : removed,
                        firstFailure == null ? failure : firstFailure));
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
                this.manageView, false, this.searchValue, this.authMode,
                this.usernameValue, this.serverValue));
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
        if (!this.manageView && !this.addDialog) {
            context.fill(0, this.height - 36, this.width, this.height, 0x68101822);
            int searchLeft = this.accountsPanelLeft + 12;
            int searchRight = this.accountsPanelLeft + this.accountsPanelWidth - 12;
            context.fill(searchLeft, 34, searchRight, 60, 0x50101822);
            int iconX = searchLeft + 12;
            int iconY = 41;
            int iconColor = 0xFFD5E0EE;
            context.fill(iconX + 2, iconY, iconX + 8, iconY + 2, iconColor);
            context.fill(iconX, iconY + 2, iconX + 2, iconY + 8, iconColor);
            context.fill(iconX + 2, iconY + 8, iconX + 8, iconY + 10, iconColor);
            context.fill(iconX + 8, iconY + 2, iconX + 10, iconY + 8, iconColor);
            context.fill(iconX + 9, iconY + 9, iconX + 13, iconY + 13, iconColor);
        } else {
        }

        if (this.addDialog) {
            int dialogWidth = Math.min(380, Math.max(250, this.width - 32));
            int left = (this.width - dialogWidth) / 2;
            int top = Math.max(34, (this.height - 154) / 2);
            this.drawGlassPanel(context, left, top, left + dialogWidth, top + 142);
            this.text(context, "OFFLINE ACCOUNT", left + 14, top + 12, 0xFF9EADBD);
            this.text(context, "Microsoft sign-in is postponed.", left + 14, top + 126, 0xFF9EADBD);
        } else {
            int top = this.manageView ? 42 : 66;
            int bottom = this.height - 58;
            if (this.manageView) {
                this.drawGlassPanel(context, this.accountsPanelLeft, top,
                        this.accountsPanelLeft + this.accountsPanelWidth, bottom);
            } else {
                context.fill(8, top, this.width - 8, bottom, 0x52141D28);
            }
            if (this.pageCount > 1) {
                int trackHeight = Math.max(1, this.scrollTrackBottom - this.scrollTrackTop);
                int thumbHeight = Math.max(9,
                        trackHeight * this.pageSize / Math.max(1, this.accountRows.size()));
                int thumbTravel = Math.max(0, trackHeight - thumbHeight);
                int thumbTop = this.scrollTrackTop + thumbTravel * this.pageIndex
                        / Math.max(1, this.pageCount - 1);
                context.fill(this.scrollButtonX + 6, this.scrollTrackTop,
                        this.scrollButtonX + 8, this.scrollTrackBottom, 0xFF435061);
                context.fill(this.scrollButtonX + 4, thumbTop,
                        this.scrollButtonX + 10, thumbTop + thumbHeight, 0xFF6FC4DE);
            }
            String sectionTitle = this.manageView
                    ? "SERVERS  (" + this.accountRows.size() + ")"
                    : "ACCOUNTS  (" + this.accountRows.size() + ")";
            if (this.manageView) {
                this.text(context, sectionTitle, this.accountsPanelLeft + 12,
                        top + 8, 0xFF9EADBD);
            } else {
                int sectionWidth = this.textRenderer.getWidth(sectionTitle);
                int center = this.accountsPanelLeft + this.accountsPanelWidth / 2;
                this.drawGlassDivider(context, this.accountsPanelLeft + 14,
                        top + 12, center - sectionWidth / 2 - 10);
                this.drawGlassDivider(context, center + sectionWidth / 2 + 10,
                        top + 12, this.accountsPanelLeft + this.accountsPanelWidth - 28);
                this.centered(context, sectionTitle, center, top + 8, 0xFF9EADBD);
            }
            if (this.manageView) {
                this.text(context, accountUsername(this.expandedAccountKey),
                        this.accountsPanelLeft + 12, top - 12, 0xFFE7EDF5);
            }
            if (this.accountRows.isEmpty()) {
                String empty = this.manageView ? "No servers added to this account yet."
                        : this.searchValue.isEmpty() ? "No accounts added yet."
                        : "No accounts match your search.";
                context.drawCenteredTextWithShadow(this.textRenderer,
                        Text.literal(empty), this.width / 2, top + 38, 0xFF9EADBD);
            }
            this.text(context, fitText(this.status, this.width - 32),
                    16, this.height - 48, 0xFF9EADBD);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.manageView && !this.addDialog && keyCode == 257
                && this.searchField != null && this.searchField.isFocused()) {
            String filter = this.searchField.getText();
            this.client.setScreen(new SessionManagerScreen(
                    this.parent, this.status, 0, -1L, null, false, false,
                    filter, this.authMode, this.usernameValue, ""));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
