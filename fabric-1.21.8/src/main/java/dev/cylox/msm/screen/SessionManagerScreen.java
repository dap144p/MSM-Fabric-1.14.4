package dev.cylox.msm.screen;

import dev.cylox.msm.MsmClient;
import dev.cylox.msm.automation.SessionAutomationScheduler;
import dev.cylox.msm.profile.AccountIdentity;
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
        return this.manageView
                ? (this.addDialog ? "ADD SERVER" : "MANAGE ACCOUNT")
                : "ACCOUNT MANAGER";
    }

    @Override
    protected int getMsmHeaderLeft() {
        return this.floatingOverlay && !this.manageView ? this.overlayLeft : 0;
    }

    @Override
    protected int getMsmHeaderRight() {
        return this.floatingOverlay && !this.manageView ? this.overlayRight : this.width;
    }

    @Override
    protected int getMsmHeaderTop() {
        return this.floatingOverlay && !this.manageView ? this.overlayTop : 0;
    }

    private static final int PAGE_SIZE = 6;
    private static final long DOUBLE_CLICK_WINDOW_MS = 350L;
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
    private int accountGridColumns = 1;
    private boolean compactLayout;
    private String expandedAccountKey;
    private final boolean floatingOverlay;
    private int overlayLeft;
    private int overlayTop;
    private int overlayRight;
    private int overlayBottom;
    private int microsoftCardY = 202;
    private int microsoftCardHeight = 76;

    private TextFieldWidget searchField;
    private TextFieldWidget usernameField;
    private TextFieldWidget serverField;
    private MsmButtonWidget authModeButton;
    private MsmButtonWidget offlineAccountButton;
    private MsmButtonWidget microsoftAccountButton;
    private MsmButtonWidget addAccountButton;
    private MsmButtonWidget deleteAccountButton;
    private MsmButtonWidget manageButton;
    private String lastClickedAccountKey;
    private long lastAccountClickTime;
    private long lastClickedServerId = -1L;
    private long lastServerClickTime;
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
                SessionAuthMode.OFFLINE_LOCAL, "", "", false);
    }

    private SessionManagerScreen(Screen parent, String status, int page,
                                 long selectedSessionId, String expandedAccountKey,
                                 boolean manageView, boolean addDialog, String searchValue,
                                 SessionAuthMode authMode,
                                 String usernameValue, String serverValue,
                                 boolean floatingOverlay) {
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
        this.floatingOverlay = floatingOverlay;
        refreshSessions();
    }

    private void refreshSessions() {
        this.sessions.clear();
        this.accountGroups.clear();
        this.accountRows.clear();

        List<SessionProfile> profiles = MsmClient.getProfiles();
        Map<String, AccountGroup> grouped = new LinkedHashMap<>();
        for (AccountIdentity identity : MsmClient.getAccountIdentities()) {
            String key = identity.getAuthMode().name() + ":" + identity.getUsername();
            grouped.put(key, new AccountGroup(key, identity.getUsername(), identity.getAuthMode()));
        }
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
                entry.getValue().setTrailingLabel(getAccountServerCount(group));
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

    private static String accountKey(AccountIdentity identity) {
        return identity.getAuthMode().name() + ":" + identity.getUsername();
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

    private String getAccountServerCount(AccountGroup group) {
        int online = 0;
        for (SessionManager.Session server : group.servers) {
            if (server.getState() == SessionState.ONLINE) online++;
        }
        return online + "/" + group.servers.size();
    }

    private String getAccountServerSummary(AccountGroup group) {
        return "[SERVER " + getAccountServerCount(group) + "]";
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

    private boolean hasSelectedAccount() {
        if (this.expandedAccountKey == null) return false;
        for (AccountGroup group : this.accountGroups) {
            if (group.key.equals(this.expandedAccountKey)) return true;
        }
        return false;
    }

    private void refreshControls() {
        if (this.deleteAccountButton != null) {
            this.deleteAccountButton.active = hasSelectedAccount();
        }
        Optional<SessionManager.Session> selected = getSelectedSession();
        if (this.manageButton != null) {
            this.manageButton.active = this.manageView
                    ? selected.isPresent() : hasSelectedAccount();
        }
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
        this.offlineAccountButton = null;
        this.microsoftAccountButton = null;
        this.addAccountButton = null;
        this.deleteAccountButton = null;
        this.manageButton = null;
        this.searchField = null;
        this.usernameField = null;
        this.serverField = null;
        this.rowButtons.clear();
        this.accountButtons.clear();
        this.serverManageButtons.clear();
        this.connectionButton = null;
        this.removeButton = null;
        this.controlButton = null;
        refreshSessions();

        this.overlayLeft = 0;
        this.overlayTop = 0;
        this.overlayRight = this.width;
        this.overlayBottom = this.height;

        int contentLeft = 14;
        int contentWidth = Math.max(1, this.width - 28);
        int panelTop = this.manageView ? 74 : 94;
        this.formPanelLeft = contentLeft;
        this.formPanelWidth = 0;
        this.accountsPanelLeft = contentLeft;
        this.accountsPanelWidth = contentWidth;
        this.rowStartY = this.manageView ? 96 : 104;
        this.accountsPanelBottom = this.height - (this.manageView ? 58 : 44);

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
            if (this.manageView) {
                this.serverField = this.addField(innerLeft, top + 64, innerWidth, 20, "Server address");
                this.serverField.setMaxLength(255);
                this.serverField.setPlaceholder(Text.literal("server.example"));
                this.serverField.setText(this.serverValue);
            }
            this.addAccountButton = this.addButton(new MsmButtonWidget(
                    innerLeft, top + (this.manageView ? 98 : 66), innerWidth, 20,
                    this.manageView ? "ADD SERVER" : "CREATE ACCOUNT", button -> addAccount()));
            this.addButton(new MsmButtonWidget(
                    left + dialogWidth - 80, top + 8, 66, 18, "CANCEL",
                    button -> this.client.setScreen(this.parent)));
            return;
        }

        if (!this.manageView) {
            int addWidth = this.width < 420 ? 94 : 112;
            int deleteWidth = 20;
            int manageWidth = 84;
            int actionGap = 5;
            int actionsRight = contentLeft + contentWidth;
            int addLeft = actionsRight - addWidth;
            int manageLeft = actionsRight - manageWidth;
            int deleteLeft = manageLeft - actionGap - deleteWidth;
            int searchWidth = Math.max(1, Math.min(280,
                    deleteLeft - actionGap - contentLeft));
            this.offlineAccountButton = this.addButton(new MsmButtonWidget(
                    addLeft, 37, addWidth, 20, "CREATE ACCOUNT", button -> openAddDialog()));
            this.deleteAccountButton = this.addButton(new MsmButtonWidget(
                    deleteLeft, 62, deleteWidth, 20, "",
                    button -> requestDeleteAccount(this.expandedAccountKey)));
            this.deleteAccountButton.setTrashIcon(true);
            this.manageButton = this.addButton(new MsmButtonWidget(
                    manageLeft, 62, manageWidth, 20, "MANAGE",
                    button -> openAccountManage(this.expandedAccountKey)));

            this.searchField = this.addField(
                    contentLeft, 72, searchWidth, 20, "Search accounts...");
            this.searchField.setMaxLength(64);
            this.searchField.setText(this.searchValue);
        }

        this.scrollButtonX = this.accountsPanelLeft + this.accountsPanelWidth - 18;
        this.scrollTrackTop = panelTop + 8;
        this.scrollTrackBottom = this.accountsPanelBottom - 10;
        int rowWidth = Math.max(1, this.accountsPanelWidth - 28);
        this.accountGridColumns = this.manageView ? 1
                : rowWidth >= 360 ? 3 : rowWidth >= 220 ? 2 : 1;
        int accountRowStep = 32;
        int rowStep = this.manageView ? 32 : accountRowStep;
        int accountCardHeight = 20;
        int listBottom = this.accountsPanelBottom - 10;
        int availableRows = this.manageView
                ? Math.max(1, (listBottom - this.rowStartY) / 32 + 1)
                : Math.max(1, (listBottom - this.rowStartY - accountCardHeight)
                    / accountRowStep + 1);
        int rowCapacity = this.manageView ? availableRows
                : availableRows * this.accountGridColumns;
        this.pageSize = Math.min(PAGE_SIZE, rowCapacity);
        this.pageCount = Math.max(1, (this.accountRows.size() + this.pageSize - 1) / this.pageSize);
        this.pageIndex = Math.min(this.requestedPage, this.pageCount - 1);

        int start = this.pageIndex * this.pageSize;
        int end = Math.min(start + this.pageSize, this.accountRows.size());
        int rowY = this.rowStartY;
        int rowLeft = this.accountsPanelLeft;
        int cardGap = 8;
        int cardWidth = Math.max(1,
                (rowWidth - cardGap * (this.accountGridColumns - 1)) / this.accountGridColumns);
        int labelWidth = rowWidth;

        for (int index = start; index < end; index++) {
            AccountListEntry entry = this.accountRows.get(index);
            if (!this.manageView && entry.server == null) {
                AccountGroup group = entry.account;
                int accountIndex = index - start;
                int cardColumn = accountIndex % this.accountGridColumns;
                int cardRow = accountIndex / this.accountGridColumns;
                int cardLeft = rowLeft + cardColumn * (cardWidth + cardGap);
                int cardTop = this.rowStartY + cardRow * accountRowStep;
                MsmButtonWidget account = new MsmButtonWidget(
                        cardLeft, cardTop, cardWidth, 20,
                        getAccountGroupLabel(group),
                        button -> selectAccountGroup(group.key));
                account.setFlat(true);
                account.setTrailingLabel(getAccountServerCount(group));
                account.setSelected(group.key.equals(this.expandedAccountKey));
                this.accountButtons.put(group.key, account);
                this.addButton(account);
            } else if (entry.server != null) {
                SessionManager.Session session = entry.server;
                MsmButtonWidget server = new MsmButtonWidget(
                        rowLeft, rowY, labelWidth, 22, getServerLabel(session, session.getId()),
                        button -> selectServer(session, entry.account.key));
                server.setSelected(session.getId() == this.selectedSessionId);
                this.rowButtons.put(session.getId(), server);
                this.addButton(server);
            }
            rowY += rowStep;
        }

        if (this.pageCount > 1) {
            MsmButtonWidget previous = new MsmButtonWidget(
                    this.scrollButtonX, panelTop + 2, 14, 14, "^",
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
            int groupWidth = Math.min(540, contentWidth);
            int left = this.width - 12 - groupWidth;
            int width = Math.max(1, (groupWidth - gap * 4) / 5);
            this.addButton(new MsmButtonWidget(left, footerY, width, 20, "ADD SERVER",
                    button -> openAddDialog()));
            this.connectionButton = this.addButton(new MsmButtonWidget(
                    left + width + gap, footerY, width, 20, "JOIN", button -> toggleConnection()));
            this.removeButton = this.addButton(new MsmButtonWidget(
                    left + (width + gap) * 2, footerY, width, 20, "REMOVE SERVER",
                    button -> requestRemoveServer()));
            this.removeButton.setDanger(true);
            this.addButton(new MsmButtonWidget(left + (width + gap) * 3, footerY, width, 20,
                    "BACK", button -> this.client.setScreen(this.parent)));
            this.manageButton = this.addButton(new MsmButtonWidget(
                    left + (width + gap) * 4, footerY, width, 20,
                    "MANAGE", button -> openControlManage()));
        } else {
            boolean showUpdates = MsmClient.getUpdateManager() != null;
            int updateWidth = showUpdates ? 82 : 0;
            int backWidth = 78;
            int groupWidth = updateWidth + (showUpdates ? gap : 0) + backWidth;
            int left = this.width - 12 - groupWidth;
            if (showUpdates) {
                this.addButton(new MsmButtonWidget(left, footerY, updateWidth, 20,
                        "UPDATES", button -> openUpdateScreen()));
            }
            int backLeft = left + updateWidth + (showUpdates ? gap : 0);
            this.addButton(new MsmButtonWidget(backLeft, footerY, backWidth, 20,
                    "BACK", button -> this.client.setScreen(this.parent)));
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

    private void selectAccountGroup(String key) {
        if (key == null) return;
        long now = System.currentTimeMillis();
        if (key.equals(this.lastClickedAccountKey)
                && now - this.lastAccountClickTime <= DOUBLE_CLICK_WINDOW_MS) {
            this.lastClickedAccountKey = null;
            this.lastAccountClickTime = 0L;
            openAccountManage(key);
            return;
        }
        this.lastClickedAccountKey = key;
        this.lastAccountClickTime = now;
        this.expandedAccountKey = key;
        this.status = "Selected account " + accountUsername(key) + ".";
        refreshSessions();
    }

    private void openAccountManage(String key) {
        this.snapshotFormValues();
        this.expandedAccountKey = key;
        String username = accountUsername(key);
        this.client.setScreen(new SessionManagerScreen(
                this, "Manage account " + username + ".", 0, -1L, key,
                true, false, "", SessionAuthMode.OFFLINE_LOCAL, username, "", false));
    }

    private void openAddDialog() {
        if (!this.manageView) {
            this.addDialog = true;
            this.usernameValue = "";
            int dialogWidth = Math.min(380, Math.max(250, this.width - 32));
            int left = (this.width - dialogWidth) / 2;
            int innerLeft = left + 14;
            int innerWidth = dialogWidth - 28;
            int top = Math.max(34, (this.height - 154) / 2);
            this.usernameField = this.addField(innerLeft, top + 42, innerWidth, 20, "Account username");
            this.usernameField.setMaxLength(16);
            this.addButton(new MsmButtonWidget(innerLeft, top + 78, innerWidth, 20,
                    "CREATE ACCOUNT", button -> addAccount()));
            this.addButton(new MsmButtonWidget(left + dialogWidth - 80, top + 8, 66, 18,
                    "CANCEL", button -> openScreen(this.status, this.pageIndex, this.selectedSessionId)));
            return;
        }
        String username = accountUsername(this.expandedAccountKey);
        this.client.setScreen(new SessionManagerScreen(
                this, this.status, this.pageIndex, this.selectedSessionId, this.expandedAccountKey,
                true, true, this.searchValue, SessionAuthMode.OFFLINE_LOCAL, username, "", false));
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
                ? "CREATE ACCOUNT" : "ADD SERVER";
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
        long now = System.currentTimeMillis();
        if (session.getId() == this.lastClickedServerId
                && now - this.lastServerClickTime <= DOUBLE_CLICK_WINDOW_MS) {
            this.lastClickedServerId = -1L;
            this.lastServerClickTime = 0L;
            openControlManageFor(session);
            return;
        }
        this.lastClickedServerId = session.getId();
        this.lastServerClickTime = now;
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
            if (!this.manageView) {
                AccountIdentity identity = new AccountIdentity(this.usernameValue, SessionAuthMode.OFFLINE_LOCAL);
                MsmClient.addAccountIdentity(identity);
                this.expandedAccountKey = accountKey(identity);
                this.manageView = true;
                this.addDialog = false;
                this.searchValue = "";
                this.usernameValue = identity.getUsername();
                refreshSessions();
                this.client.setScreen(new SessionManagerScreen(this, "Account "
                        + identity.getUsername() + " created. Add servers whenever you're ready.",
                        0, -1L, this.expandedAccountKey, true, false, "",
                        identity.getAuthMode(), identity.getUsername(), "", false));
                return;
            }

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
        if (targetAccountKey == null || targetAccountKey.trim().isEmpty()) {
            this.status = "Select an account first.";
            return;
        }
        for (AccountGroup group : this.accountGroups) {
            if (group.key.equals(targetAccountKey)) {
                final String username = group.username;
                final List<SessionManager.Session> targets = new ArrayList<>(group.servers);
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
        if (targets.isEmpty()) {
            removeNextAccountServer(username, targets, 0, 0, null);
            return;
        }
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
                try {
                    MsmClient.removeAccountIdentity(username, SessionAuthMode.OFFLINE_LOCAL);
                } catch (IOException exception) {
                    this.status = "Servers removed, but account identity could not be deleted: "
                            + exception.getMessage();
                    this.client.setScreen(this);
                    return;
                }
                this.expandedAccountKey = null;
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
                this.usernameValue, this.serverValue, this.floatingOverlay));
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
        if (this.floatingOverlay && !this.manageView) {
            if (this.parent != null) {
                this.parent.render(context, mouseX, mouseY, delta);
            } else {
                this.renderMsmBackground(context, mouseX, mouseY, delta);
            }
            context.fill(0, 0, this.width, this.height, 0x99070B12);
            this.drawGlassPanel(context, this.overlayLeft, this.overlayTop,
                    this.overlayRight, this.overlayBottom);
        } else {
            this.renderMsmBackground(context, mouseX, mouseY, delta);
        }

        int left = this.accountsPanelLeft;
        int right = left + this.accountsPanelWidth;
        if (this.manageView) {
            this.text(context, accountUsername(this.expandedAccountKey),
                    left, 40, 0xFFE7EDF5);
            String accountType = this.expandedAccountKey != null
                    && this.expandedAccountKey.startsWith(SessionAuthMode.OFFLINE_LOCAL.name() + ":")
                    ? "OFFLINE ACCOUNT" : "MICROSOFT ACCOUNT";
            this.text(context, accountType, left, 53, 0xFF8492A3);
            this.drawSectionTitle(context,
                    "SERVERS  (" + this.accountRows.size() + ")", left, 74);
            this.drawGlassDivider(context, left, 88, right);
        } else {
            this.drawSectionTitle(context,
                    "ACCOUNTS  (" + this.accountGroups.size() + ")", left, 40);
            this.drawGlassDivider(context, left, 96, right);
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

        if (this.accountRows.isEmpty()) {
            String empty = this.manageView ? "No servers added to this account yet."
                    : this.searchValue.isEmpty() ? "No accounts added yet."
                    : "No accounts match your search.";
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(empty),
                    this.width / 2, this.rowStartY + 10, 0xFF9EADBD);
        }

        int start = this.pageIndex * this.pageSize;
        int end = Math.min(start + this.pageSize, this.accountRows.size());
        int rowY = this.rowStartY;
        int rowStep = this.manageView ? 32 : 64;
        for (int index = start; index < end; index++) {
            if (this.manageView) {
                this.drawGlassDivider(context, left, rowY + rowStep - 2, right - 22);
            }
            rowY += rowStep;
        }

        if (this.addDialog) {
            int dialogWidth = Math.min(380, Math.max(250, this.width - 32));
            int dialogLeft = (this.width - dialogWidth) / 2;
            int top = Math.max(34, (this.height - 154) / 2);
            this.drawGlassPanel(context, dialogLeft, top, dialogLeft + dialogWidth, top + 112);
            this.text(context, this.manageView ? "ADD SERVER" : "OFFLINE ACCOUNT",
                    dialogLeft + 14, top + 12, 0xFF9EADBD);
        }
        if (!this.addDialog) {
            renderMsmFooter(context, this.status,
                    this.manageView ? Math.min(500, this.width - 24) : 100);
        }

        super.render(context, mouseX, mouseY, delta);

        
    }


    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.manageView && !this.addDialog && keyCode == 257
                && this.searchField != null && this.searchField.isFocused()) {
            String filter = this.searchField.getText();
            this.client.setScreen(new SessionManagerScreen(
                    this.parent, this.status, 0, -1L, this.expandedAccountKey, false, false,
                    filter, this.authMode, this.usernameValue, "", this.floatingOverlay));
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
