package dev.cylox.msm.screen;

import dev.cylox.msm.MsmClient;
import dev.cylox.msm.automation.SessionAutomationScheduler;
import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.profile.SessionProfileStore;
import dev.cylox.msm.server.ServerTemplateRepository;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.LiteralText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SessionManagerScreen extends MsmScreen {
    private static final int PAGE_SIZE = 6;
    private static final String DEFAULT_STATUS = "Select an account and open MANAGE to view its servers.";

    private final Screen parent;
    private final SessionProfileStore store;
    private final SessionManager sessionManager;
    private final SecondarySessionController secondarySessions;
    private final SessionAutomationScheduler automationScheduler;
    private final ServerTemplateRepository serverTemplateRepository;
    private final List<SessionProfile> profiles = new ArrayList<SessionProfile>();
    private final List<SessionManager.Session> sessions = new ArrayList<SessionManager.Session>();
    private final Map<Long, ButtonWidget> rowButtons = new LinkedHashMap<Long, ButtonWidget>();
    private final Map<String, MsmButtonWidget> accountButtons = new LinkedHashMap<String, MsmButtonWidget>();
    private final Map<Long, MsmButtonWidget> serverManageButtons = new LinkedHashMap<Long, MsmButtonWidget>();
    private final List<AccountGroup> accountGroups = new ArrayList<AccountGroup>();
    private final List<AccountListEntry> accountRows = new ArrayList<AccountListEntry>();

    private final int requestedPage;
    private String managedAccountKey;
    private String searchValue;
    private boolean manageView;
    private boolean addDialog;
    private int accountsLabelY;
    private int rowStartY;
    private int accountsPanelBottom;
    private int formPanelLeft;
    private int formPanelWidth;
    private int accountsPanelLeft;
    private int accountsPanelWidth;
    private boolean compactLayout;

    private TextFieldWidget searchField;
    private TextFieldWidget usernameField;
    private TextFieldWidget serverField;
    private ButtonWidget authModeButton;
    private ButtonWidget serverListButton;
    private ButtonWidget connectionButton;
    private ButtonWidget controlButton;
    private ButtonWidget removeButton;
    private volatile String status;
    private SessionState lastObservedState;
    private long selectedSessionId;
    private int pageIndex;
    private int pageSize;
    private int pageCount;
    private int scrollButtonX;
    private int scrollTrackTop;
    private int scrollTrackBottom;
    private SessionAuthMode authMode;
    private String usernameValue;
    private String serverValue;
    private String selectedServerName;

    public SessionManagerScreen(Screen parent, SessionProfileStore store) {
        this(parent, store, new SessionManager());
    }

    public SessionManagerScreen(Screen parent, SessionProfileStore store, SessionManager sessionManager) {
        this(parent, store, sessionManager, new SecondarySessionController(sessionManager));
    }

    public SessionManagerScreen(Screen parent, SessionProfileStore store,
                                SessionManager sessionManager,
                                SecondarySessionController secondarySessions) {
        this(parent, store, sessionManager, secondarySessions, null, null);
    }

    public SessionManagerScreen(Screen parent, SessionProfileStore store,
                                SessionManager sessionManager,
                                SecondarySessionController secondarySessions,
                                SessionAutomationScheduler automationScheduler,
                                ServerTemplateRepository serverTemplateRepository) {
        this(parent, store, sessionManager, secondarySessions, automationScheduler,
                serverTemplateRepository, DEFAULT_STATUS, 0, -1L,
                SessionAuthMode.OFFLINE_LOCAL, "", "", "", "", null, false, false);
    }

    private SessionManagerScreen(Screen parent, SessionProfileStore store,
                                 SessionManager sessionManager,
                                 SecondarySessionController secondarySessions,
                                 SessionAutomationScheduler automationScheduler,
                                 ServerTemplateRepository serverTemplateRepository,
                                 String status, int page, long selectedSessionId,
                                 SessionAuthMode authMode, String usernameValue,
                                 String serverValue, String selectedServerName,
                                 String searchValue, String managedAccountKey,
                                 boolean manageView, boolean addDialog) {
        super(new LiteralText("Multi Session Manager"));
        if (store == null || sessionManager == null || secondarySessions == null) {
            throw new IllegalArgumentException("Profile store, session manager, and network controller are required");
        }

        this.parent = parent;
        this.store = store;
        this.sessionManager = sessionManager;
        this.secondarySessions = secondarySessions;
        this.automationScheduler = automationScheduler;
        this.serverTemplateRepository = serverTemplateRepository;
        this.status = status == null ? DEFAULT_STATUS : status;
        this.requestedPage = Math.max(0, page);
        this.selectedSessionId = selectedSessionId;
        this.authMode = authMode == null ? SessionAuthMode.OFFLINE_LOCAL : authMode;
        this.usernameValue = usernameValue == null ? "" : usernameValue;
        this.serverValue = serverValue == null ? "" : serverValue;
        this.selectedServerName = selectedServerName == null ? "" : selectedServerName;
        this.searchValue = searchValue == null ? "" : searchValue;
        this.managedAccountKey = managedAccountKey;
        this.manageView = manageView;
        this.addDialog = addDialog;

        try {
            this.profiles.addAll(this.store.load());
        } catch (IOException exception) {
            this.status = "Could not load accounts: " + exception.getMessage();
        }
        synchronizeSessions();
    }

    private void synchronizeSessions() {
        for (SessionProfile profile : this.profiles) {
            if (!this.sessionManager.findByProfile(profile).isPresent()) {
                this.sessionManager.create(profile);
            }
        }

        refreshSessions();
        if (this.selectedSessionId >= 0L
                && !this.sessionManager.find(this.selectedSessionId).isPresent()) {
            this.selectedSessionId = -1L;
        }
        if (this.automationScheduler != null) {
            this.automationScheduler.restoreDesiredSessions();
        }
    }

    private void refreshSessions() {
        this.sessions.clear();
        this.accountGroups.clear();
        this.accountRows.clear();
        Map<String, AccountGroup> grouped = new LinkedHashMap<String, AccountGroup>();

        for (SessionManager.Session session : this.sessionManager.getSessions()) {
            if (!this.profiles.contains(session.getProfile())) continue;
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
        if (this.manageView && (this.managedAccountKey == null
                || !grouped.containsKey(this.managedAccountKey))) {
            this.manageView = false;
            this.managedAccountKey = null;
        }
        String filter = this.searchValue == null ? "" : this.searchValue.trim().toLowerCase();
        for (AccountGroup group : this.accountGroups) {
            if (this.manageView) {
                if (group.key.equals(this.managedAccountKey)) {
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
            if (group != null) entry.getValue().setMessage(getAccountGroupLabel(group));
        }
        for (Map.Entry<Long, ButtonWidget> entry : this.rowButtons.entrySet()) {
            Optional<SessionManager.Session> session = this.sessionManager.find(entry.getKey());
            if (session.isPresent() && this.profiles.contains(session.get().getProfile())) {
                entry.getValue().setMessage(getServerLabel(session.get(), entry.getKey()));
                if (entry.getValue() instanceof MsmButtonWidget) {
                    ((MsmButtonWidget) entry.getValue()).setSelected(entry.getKey() == this.selectedSessionId);
                }
            }
        }
        for (Map.Entry<Long, MsmButtonWidget> entry : this.serverManageButtons.entrySet()) {
            entry.getValue().active = this.sessionManager.find(entry.getKey()).isPresent();
        }
        refreshControls();
    }

    // Group the saved server profiles by their account identity.
    private static String accountKey(SessionProfile profile) {
        return profile.getAuthMode().name() + ":" + profile.getUsername();
    }

    private String accountUsername(String key) {
        if (key == null) return this.usernameValue;
        for (AccountGroup group : this.accountGroups) {
            if (group.key.equals(key)) return group.username;
        }
        return this.usernameValue;
    }

    private String getAccountGroupLabel(AccountGroup group) {
        int online = 0;
        for (SessionManager.Session server : group.servers) {
            if (server.getState() == SessionState.ONLINE) online++;
        }
        return group.username + "   [SERVER " + online + "/" + group.servers.size() + "]";
    }

    private String getServerLabel(SessionManager.Session session, long id) {
        return fitText(session.getProfile().getServerAddress()
                + "   [" + session.getState().name() + "]",
                this.accountsPanelWidth > 0 ? this.accountsPanelWidth - 118 : this.width - 150);
    }

        private Optional<SessionManager.Session> getSelectedSession() {
        return this.selectedSessionId < 0L
                ? Optional.<SessionManager.Session>empty()
                : this.sessionManager.find(this.selectedSessionId);
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
        List<String> messages = this.secondarySessions.getMessages(sessionId);
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
        this.rowButtons.clear();
        this.accountButtons.clear();
        this.serverManageButtons.clear();
        this.searchField = null;
        this.usernameField = null;
        this.serverField = null;
        this.authModeButton = null;
        this.serverListButton = null;
        this.connectionButton = null;
        this.controlButton = null;
        refreshSessions();
        this.removeButton = null;

        int contentWidth = Math.max(1, Math.min(this.width - 24, 560));
        int contentLeft = Math.max(12, (this.width - contentWidth) / 2);
        this.accountsPanelLeft = contentLeft;
        this.accountsPanelWidth = contentWidth;
        this.rowStartY = 96;
        this.accountsPanelBottom = this.height - 58;

        if (this.addDialog) {
            int dialogWidth = Math.min(390, Math.max(260, this.width - 32));
            int left = (this.width - dialogWidth) / 2;
            int innerLeft = left + 14;
            int innerWidth = dialogWidth - 28;
            int top = Math.max(34, (this.height - 164) / 2);
            this.usernameField = this.addButton(new TextFieldWidget(
                    MinecraftClient.getInstance().textRenderer,
                    innerLeft, top + 34, innerWidth, 20, "Account username"));
            this.usernameField.setMaxLength(16);
            this.usernameField.setText(this.manageView
                    ? accountUsername(this.managedAccountKey) : this.usernameValue);
            this.usernameField.setEditable(!this.manageView);

            int serverListWidth = Math.min(88, Math.max(68, innerWidth / 4));
            int serverFieldWidth = Math.max(1, innerWidth - serverListWidth - 8);
            this.serverField = this.addButton(new TextFieldWidget(
                    MinecraftClient.getInstance().textRenderer,
                    innerLeft, top + 64, serverFieldWidth, 20, "Server address"));
            this.serverField.setMaxLength(255);
            this.serverField.setText(this.serverValue);
            this.serverListButton = this.addButton(new MsmButtonWidget(
                    innerLeft + serverFieldWidth + 8, top + 64,
                    serverListWidth, 20, "SERVERS", button -> openServerPicker()));
            this.serverListButton.active = this.serverTemplateRepository != null;

            int authWidth = 86;
            this.authModeButton = this.addButton(new MsmButtonWidget(
                    innerLeft, top + 98, authWidth, 20, "OFFLINE", button -> { }));
            this.authModeButton.active = false;
            this.addButton(new MsmButtonWidget(
                    innerLeft + authWidth + 8, top + 98,
                    innerWidth - authWidth - 8, 20,
                    this.manageView ? "ADD SERVER" : "ADD ACCOUNT",
                    button -> addAccount()));
            this.addButton(new MsmButtonWidget(
                    left + dialogWidth - 80, top + 8, 66, 18, "CANCEL",
                    button -> MinecraftClient.getInstance().openScreen(this.parent)));
            return;
        }

        if (!this.manageView) {
            int searchWidth = Math.min(220, Math.max(140, contentWidth - 40));
            this.searchField = this.addButton(new TextFieldWidget(
                    MinecraftClient.getInstance().textRenderer,
                    this.width / 2 - searchWidth / 2, 36,
                    searchWidth, 20, "Search accounts..."));
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
        int rowWidth = Math.max(1, Math.min(this.manageView ? 520 : 480, contentWidth - 44));
        int rowLeft = this.accountsPanelLeft + 12;
        int actionWidth = 82;
        int labelWidth = Math.max(1, rowWidth - actionWidth - 8);

        for (int index = start; index < end; index++) {
            AccountListEntry entry = this.accountRows.get(index);
            if (!this.manageView && entry.server == null) {
                AccountGroup group = entry.account;
                MsmButtonWidget account = new MsmButtonWidget(
                        rowLeft, rowY, labelWidth, 20, getAccountGroupLabel(group),
                        button -> openAccountManage(group.key));
                this.accountButtons.put(group.key, account);
                this.addButton(account);
                this.addButton(new MsmButtonWidget(
                        rowLeft + labelWidth + 8, rowY, actionWidth, 20,
                        "MANAGE", button -> openAccountManage(group.key)));
            } else if (entry.server != null) {
                SessionManager.Session session = entry.server;
                MsmButtonWidget server = new MsmButtonWidget(
                        rowLeft, rowY, labelWidth, 20, getServerLabel(session, session.getId()),
                        button -> selectAccount(session));
                server.setSelected(session.getId() == this.selectedSessionId);
                this.rowButtons.put(session.getId(), server);
                this.addButton(server);
                MsmButtonWidget manage = new MsmButtonWidget(
                        rowLeft + labelWidth + 8, rowY, actionWidth, 20,
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
            int width = Math.max(1, (groupWidth - gap * 4) / 5);
            this.addButton(new MsmButtonWidget(left, footerY, width, 20, "ADD SERVER",
                    button -> openAddDialog()));
            this.connectionButton = this.addButton(new MsmButtonWidget(
                    left + width + gap, footerY, width, 20, "JOIN", button -> toggleConnection()));
            this.removeButton = this.addButton(new MsmButtonWidget(
                    left + (width + gap) * 2, footerY, width, 20, "REMOVE",
                    button -> requestRemoveAccount()));
            ((MsmButtonWidget) this.removeButton).setDanger(true);
            MsmButtonWidget deleteAccount = this.addButton(new MsmButtonWidget(
                    left + (width + gap) * 3, footerY, width, 20, "DELETE",
                    button -> requestDeleteAccount()));
            deleteAccount.setDanger(true);
            this.addButton(new MsmButtonWidget(left + (width + gap) * 4, footerY, width, 20,
                    "BACK", button -> MinecraftClient.getInstance().openScreen(this.parent)));
        } else {
            int addWidth = 78;
            int closeWidth = 72;
            int groupWidth = addWidth + gap + closeWidth;
            int left = this.width - 12 - groupWidth;
            this.addButton(new MsmButtonWidget(left, footerY, addWidth, 20, "ADD",
                    button -> openAddDialog()));
            this.addButton(new MsmButtonWidget(left + addWidth + gap, footerY,
                    closeWidth, 20, "CLOSE", button -> onClose()));
        }
        refreshControls();
    }

    private void openAccountManage(String key) {
        String username = accountUsername(key);
        MinecraftClient.getInstance().openScreen(new SessionManagerScreen(
                this, this.store, this.sessionManager, this.secondarySessions,
                this.automationScheduler, this.serverTemplateRepository,
                "Manage account " + username + ".", 0, -1L,
                SessionAuthMode.OFFLINE_LOCAL, username, "", "",
                "", key, true, false));
    }

    private void openAddDialog() {
        String username = this.manageView ? accountUsername(this.managedAccountKey) : "";
        MinecraftClient.getInstance().openScreen(new SessionManagerScreen(
                this, this.store, this.sessionManager, this.secondarySessions,
                this.automationScheduler, this.serverTemplateRepository,
                this.status, this.pageIndex, this.selectedSessionId,
                SessionAuthMode.OFFLINE_LOCAL, username, "", "",
                this.searchValue, this.managedAccountKey, this.manageView, true));
    }

    private void openControlManageFor(SessionManager.Session session) {
        if (session == null) return;
        this.selectedSessionId = session.getId();
        openControlManage();
    }

        private void applyLastSelectedServer() {
        if (this.serverTemplateRepository == null) return;
        try {
            List<ServerTemplateRepository.ServerTemplate> servers =
                    this.serverTemplateRepository.loadServers();
            Optional<ServerTemplateRepository.ServerTemplate> last =
                    this.serverTemplateRepository.getLastSelected(servers);
            if (last.isPresent()) {
                this.serverValue = last.get().getAddress();
                this.selectedServerName = last.get().getName();
            }
        } catch (IOException exception) {
            this.status = "Could not read saved server list: " + exception.getMessage();
        }
    }

    private void snapshotFormValues() {
        if (this.usernameField != null) this.usernameValue = this.usernameField.getText();
        if (this.serverField != null) this.serverValue = this.serverField.getText();
    }

    private void openServerPicker() {
        if (this.serverTemplateRepository == null) {
            this.status = "Saved server list is unavailable.";
            return;
        }
        snapshotFormValues();
        MinecraftClient.getInstance().openScreen(new ServerTemplatePickerScreen(
                this, this.serverTemplateRepository, server -> {
                    this.serverValue = server.getAddress();
                    this.selectedServerName = server.getName();
                    this.status = "Selected server: " + server.getName();
                }));
    }

    private void addAccount() {
        SessionManager.Session created = null;
        snapshotFormValues();
        try {
            SessionProfile profile = new SessionProfile(
                    this.usernameValue,
                    this.serverValue,
                    SessionAuthMode.OFFLINE_LOCAL);

            if (this.profiles.contains(profile)) {
                this.status = "This account and server are already added.";
                return;
            }

            created = this.sessionManager.create(profile);
            this.profiles.add(profile);
            try {
                this.store.save(this.profiles);
            } catch (IOException exception) {
                this.profiles.remove(profile);
                this.sessionManager.remove(created.getId());
                throw exception;
            }

            this.managedAccountKey = accountKey(profile);
            this.manageView = true;
            this.addDialog = false;
            this.searchValue = "";
            openScreen("Account " + profile.getUsername() + " added.",
                    0, created.getId());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        } catch (IOException exception) {
            this.status = "Could not save account: " + exception.getMessage();
        }
    }

    private void requestDeleteAccount() {
        for (AccountGroup group : this.accountGroups) {
            if (group.key.equals(this.managedAccountKey)) {
                final String accountKey = group.key;
                final String username = group.username;
                final List<SessionManager.Session> targets =
                        new ArrayList<SessionManager.Session>(group.servers);
                if (targets.isEmpty()) {
                    this.status = "This account has no saved servers.";
                    return;
                }
                MinecraftClient.getInstance().openScreen(new ConfirmRemoveAccountScreen(
                        this, username, targets.size() + " saved servers",
                        () -> beginDeleteAccount(accountKey, username, targets), true));
                return;
            }
        }
        this.status = "The selected account is no longer available.";
    }

    private void beginDeleteAccount(String accountKey, String username,
                                    List<SessionManager.Session> targets) {
        this.status = "Disconnecting and deleting all servers for " + username + "...";
        MinecraftClient.getInstance().openScreen(this);
        if (this.automationScheduler == null) {
            for (SessionManager.Session session : targets) {
                this.secondarySessions.disconnect(session.getId());
            }
            finishDeleteAccount(accountKey, username, targets, null);
            return;
        }
        for (SessionManager.Session session : targets) {
            this.automationScheduler.disconnectManually(session.getId());
        }
        forgetAccountSession(accountKey, username, targets, 0, null);
    }

    private void forgetAccountSession(String accountKey, String username,
                                      List<SessionManager.Session> targets,
                                      int index, Throwable cleanupFailure) {
        if (index >= targets.size()) {
            finishDeleteAccount(accountKey, username, targets, cleanupFailure);
            return;
        }
        long sessionId = targets.get(index).getId();
        try {
            this.automationScheduler.forgetSession(sessionId, failure ->
                    MinecraftClient.getInstance().execute(() ->
                            forgetAccountSession(accountKey, username, targets, index + 1,
                                    failure == null ? cleanupFailure : failure)));
        } catch (Throwable failure) {
            forgetAccountSession(accountKey, username, targets, index + 1,
                    cleanupFailure == null ? failure : cleanupFailure);
        }
    }

    private void finishDeleteAccount(String accountKey, String username,
                                     List<SessionManager.Session> targets,
                                     Throwable cleanupFailure) {
        List<SessionProfile> updatedProfiles = new ArrayList<SessionProfile>(this.profiles);
        for (SessionManager.Session session : targets) {
            updatedProfiles.remove(session.getProfile());
        }
        try {
            this.store.save(updatedProfiles);
        } catch (IOException exception) {
            this.status = "Could not save account deletion: " + exception.getMessage();
            MinecraftClient.getInstance().openScreen(this);
            return;
        }
        for (SessionManager.Session session : targets) {
            try {
                this.secondarySessions.disconnect(session.getId());
                this.secondarySessions.forgetSession(session.getId());
                this.sessionManager.remove(session.getId());
            } catch (RuntimeException exception) {
                if (cleanupFailure == null) cleanupFailure = exception;
            }
        }
        this.profiles.clear();
        this.profiles.addAll(updatedProfiles);
        this.managedAccountKey = null;
        this.manageView = false;
        this.selectedSessionId = -1L;
        this.lastObservedState = null;
        refreshSessions();
        int targetPage = Math.min(this.pageIndex,
                Math.max(0, (this.accountRows.size() - 1) / Math.max(1, this.pageSize)));
        String message = "Deleted account " + username + " and all "
                + targets.size() + " saved servers.";
        if (cleanupFailure != null) {
            message += " Some session settings may need manual cleanup: " + safeMessage(cleanupFailure);
        }
        openScreen(message, targetPage, -1L);
    }

    private void requestRemoveAccount() {
        Optional<SessionManager.Session> selected = getSelectedSession();
        if (!selected.isPresent()) {
            this.status = "Select an account first.";
            return;
        }

        SessionManager.Session session = selected.get();
        SessionState state = session.getState();
        if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
            this.status = "Disconnect this account before removing it.";
            return;
        }

        SessionProfile profile = session.getProfile();
        MinecraftClient.getInstance().openScreen(new ConfirmRemoveAccountScreen(
                this, profile.getUsername(), profile.getServerAddress(),
                () -> removeAccount(session.getId())));
    }

    private void removeAccount(long sessionId) {
        if (this.automationScheduler == null) {
            finishRemoveAccount(sessionId, null);
            return;
        }

        this.status = "Removing account and saved automation settings...";
        this.automationScheduler.forgetSession(sessionId, failure ->
                MinecraftClient.getInstance().execute(
                        () -> finishRemoveAccount(sessionId, failure)));
    }

    private void finishRemoveAccount(long sessionId, Throwable failure) {
        if (failure != null) {
            this.status = "Account was not removed: " + safeMessage(failure);
            MinecraftClient.getInstance().openScreen(this);
            return;
        }

        Optional<SessionManager.Session> selected = this.sessionManager.find(sessionId);
        if (!selected.isPresent()) {
            this.status = "This account is no longer available.";
            MinecraftClient.getInstance().openScreen(this);
            return;
        }

        SessionManager.Session session = selected.get();
        SessionState state = session.getState();
        if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
            this.status = "Disconnect this account before removing it.";
            MinecraftClient.getInstance().openScreen(this);
            return;
        }

        SessionProfile profile = session.getProfile();
        List<SessionProfile> updatedProfiles = new ArrayList<SessionProfile>(this.profiles);
        if (!updatedProfiles.remove(profile)) {
            this.status = "The saved account profile could not be found.";
            MinecraftClient.getInstance().openScreen(this);
            return;
        }

        try {
            this.store.save(updatedProfiles);
        } catch (IOException exception) {
            this.status = "Could not save account removal: " + exception.getMessage();
            MinecraftClient.getInstance().openScreen(this);
            return;
        }

        try {
            this.sessionManager.remove(sessionId);
        } catch (IllegalStateException exception) {
            try {
                this.store.save(this.profiles);
            } catch (IOException rollbackException) {
                this.status = "Could not remove account or restore its profile: "
                        + rollbackException.getMessage();
                MinecraftClient.getInstance().openScreen(this);
                return;
            }
            this.status = exception.getMessage();
            MinecraftClient.getInstance().openScreen(this);
            return;
        }

        this.profiles.clear();
        this.profiles.addAll(updatedProfiles);
        this.selectedSessionId = -1L;
        this.lastObservedState = null;
        int pageCount = Math.max(1, (this.profiles.size() + this.pageSize - 1) / this.pageSize);
        int targetPage = Math.min(this.pageIndex, pageCount - 1);
        openScreen("Removed account " + profile.getUsername() + ".", targetPage, -1L);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    private void selectAccount(SessionManager.Session session) {
        this.selectedSessionId = session.getId();
        this.managedAccountKey = accountKey(session.getProfile());
        this.lastObservedState = null;
        this.status = "Selected " + session.getProfile().getUsername()
                + " on " + session.getProfile().getServerAddress() + ".";
        refreshSessions();
    }

        private void toggleConnection() {
        Optional<SessionManager.Session> selected = getSelectedSession();
        if (!selected.isPresent()) {
            this.status = "Select an account first.";
            return;
        }

        SessionManager.Session session = selected.get();
        try {
            SessionState state = session.getState();
            if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
                if (this.automationScheduler != null) {
                    this.automationScheduler.disconnectManually(session.getId());
                } else {
                    this.secondarySessions.disconnect(session.getId());
                }
                this.status = "Manual disconnect requested for " + session.getProfile().getUsername() + ".";
            } else {
                if (this.automationScheduler != null) {
                    this.automationScheduler.connectManually(session.getId());
                } else {
                    this.secondarySessions.connect(session.getId());
                }
                this.status = "Joining as " + session.getProfile().getUsername() + "...";
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
        if (this.automationScheduler == null) {
            this.status = "Automation scheduler is unavailable.";
            return;
        }
        SessionManager.Session session = selected.get();
        MinecraftClient.getInstance().openScreen(new SessionControlScreen(
                this, this.secondarySessions, this.automationScheduler,
                session.getId(), session.getProfile().getUsername()));
    }

        private void cycleAuthMode() {
        this.authMode = SessionAuthMode.OFFLINE_LOCAL;
        if (this.authModeButton != null) {
            this.authModeButton.setMessage("OFFLINE");
            this.authModeButton.active = false;
        }
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
        MinecraftClient.getInstance().openScreen(new SessionManagerScreen(
                this.parent, this.store, this.sessionManager, this.secondarySessions,
                this.automationScheduler, this.serverTemplateRepository,
                newStatus, page, selectedId, this.authMode,
                this.usernameValue, this.serverValue, this.selectedServerName,
                this.searchValue, this.managedAccountKey, this.manageView, false));
    }

        @Override
    public void tick() {
        super.tick();
        if (this.usernameField != null) this.usernameField.tick();
        if (this.searchField != null) this.searchField.tick();
        if (this.serverField != null) this.serverField.tick();
        snapshotFormValues();
        refreshSessions();
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
        drawGlassDivider(12, 28, this.width - 12);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                this.addDialog ? "ADD OFFLINE ACCOUNT"
                        : this.manageView ? "MANAGE ACCOUNT" : "MULTI SESSION MANAGER",
                this.width / 2, 11, 0xFFF1F5FA);

        if (this.addDialog) {
            int dialogWidth = Math.min(390, Math.max(260, this.width - 32));
            int left = (this.width - dialogWidth) / 2;
            int top = Math.max(34, (this.height - 164) / 2);
            drawGlassPanel(left, top, left + dialogWidth, top + 150);
            drawSectionTitle("OFFLINE ACCOUNT", left + 14, top + 12);
            drawString(MinecraftClient.getInstance().textRenderer,
                    "Microsoft sign-in is postponed.", left + 14, top + 132, 0xFF9EADBD);
        } else {
            int top = this.manageView ? 42 : 66;
            int bottom = this.height - 58;
            drawGlassPanel(this.accountsPanelLeft, top,
                    this.accountsPanelLeft + this.accountsPanelWidth, bottom);
            if (this.pageCount > 1) {
                int trackHeight = Math.max(1, this.scrollTrackBottom - this.scrollTrackTop);
                int thumbHeight = Math.max(9,
                        trackHeight * this.pageSize / Math.max(1, this.accountRows.size()));
                int thumbTravel = Math.max(0, trackHeight - thumbHeight);
                int thumbTop = this.scrollTrackTop + thumbTravel * this.pageIndex
                        / Math.max(1, this.pageCount - 1);
                fill(this.scrollButtonX + 6, this.scrollTrackTop,
                        this.scrollButtonX + 8, this.scrollTrackBottom, 0xFF435061);
                fill(this.scrollButtonX + 4, thumbTop,
                        this.scrollButtonX + 10, thumbTop + thumbHeight, 0xFF6FC4DE);
            }
            drawSectionTitle(this.manageView
                    ? "SERVERS  (" + this.accountRows.size() + ")"
                    : "ACCOUNTS  (" + this.accountRows.size() + ")",
                    this.accountsPanelLeft + 12, top + 8);
            if (this.manageView) {
                drawString(MinecraftClient.getInstance().textRenderer,
                        accountUsername(this.managedAccountKey),
                        this.accountsPanelLeft + 12, top - 12, 0xFFE7EDF5);
            }
            if (this.accountRows.isEmpty()) {
                String empty = this.manageView ? "No servers added to this account yet."
                        : this.searchValue.isEmpty() ? "No accounts added yet."
                        : "No accounts match your search.";
                drawCenteredString(MinecraftClient.getInstance().textRenderer,
                        empty, this.width / 2, top + 38, 0xFF9EADBD);
            }
            drawString(MinecraftClient.getInstance().textRenderer,
                    fitText(this.status, this.width - 32),
                    16, this.height - 48, 0xFF9EADBD);
        }

        super.render(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.manageView && !this.addDialog && keyCode == 257
                && this.searchField != null && this.searchField.isFocused()) {
            String filter = this.searchField.getText();
            MinecraftClient.getInstance().openScreen(new SessionManagerScreen(
                    this.parent, this.store, this.sessionManager, this.secondarySessions,
                    this.automationScheduler, this.serverTemplateRepository,
                    this.status, 0, -1L, SessionAuthMode.OFFLINE_LOCAL,
                    this.usernameValue, "", "", filter, null, false, false));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

        private String fitText(String value, int pixelWidth) {
        if (value == null) return "";
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }

    private static final class AccountGroup {
        private final String key;
        private final String username;
        private final SessionAuthMode authMode;
        private final List<SessionManager.Session> servers = new ArrayList<SessionManager.Session>();

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
