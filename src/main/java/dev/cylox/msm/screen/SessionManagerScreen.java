package dev.cylox.msm.screen;

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
    private static final String DEFAULT_STATUS = "Add an account, select it, then CONNECT or CONTROL MANAGE.";

    private final Screen parent;
    private final SessionProfileStore store;
    private final SessionManager sessionManager;
    private final SecondarySessionController secondarySessions;
    private final SessionAutomationScheduler automationScheduler;
    private final ServerTemplateRepository serverTemplateRepository;
    private final List<SessionProfile> profiles = new ArrayList<SessionProfile>();
    private final List<SessionManager.Session> sessions = new ArrayList<SessionManager.Session>();
    private final Map<Long, ButtonWidget> rowButtons = new LinkedHashMap<Long, ButtonWidget>();

    private final int requestedPage;
    private int accountsLabelY;
    private int rowStartY;
    private int accountsPanelBottom;
    private int formPanelLeft;
    private int formPanelWidth;
    private int accountsPanelLeft;
    private int accountsPanelWidth;
    private boolean compactLayout;

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
                SessionAuthMode.OFFLINE_LOCAL, "", "", "");
    }

    private SessionManagerScreen(Screen parent, SessionProfileStore store,
                                 SessionManager sessionManager,
                                 SecondarySessionController secondarySessions,
                                 SessionAutomationScheduler automationScheduler,
                                 ServerTemplateRepository serverTemplateRepository,
                                 String status, int page, long selectedSessionId,
                                 SessionAuthMode authMode, String usernameValue,
                                 String serverValue, String selectedServerName) {
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
        for (SessionManager.Session session : this.sessionManager.getSessions()) {
            if (this.profiles.contains(session.getProfile())) {
                this.sessions.add(session);
            }
        }

        for (Map.Entry<Long, ButtonWidget> entry : this.rowButtons.entrySet()) {
            Optional<SessionManager.Session> session = this.sessionManager.find(entry.getKey());
            if (session.isPresent() && this.profiles.contains(session.get().getProfile())) {
                entry.getValue().setMessage(getAccountLabel(session.get(), entry.getKey()));
                if (entry.getValue() instanceof MsmButtonWidget) {
                    ((MsmButtonWidget) entry.getValue())
                            .setSelected(entry.getKey() == this.selectedSessionId);
                }
            }
        }
        refreshControls();
    }

    private String getAccountLabel(SessionManager.Session session, long id) {
        String prefix = id == this.selectedSessionId ? "> " : "";
        String label = prefix + session.getProfile().getUsername()
                + "  [" + session.getState().name() + "]";
        int availableWidth = this.accountsPanelWidth > 0
                ? this.accountsPanelWidth - 34 : this.width - 42;
        int maxCharacters = Math.max(8, availableWidth / 6);
        return label.length() > maxCharacters
                ? label.substring(0, maxCharacters - 3) + "..." : label;
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
            int serverButtonWidth = Math.min(88, Math.max(64, innerWidth / 4));
            int serverFieldWidth = Math.max(1, innerWidth - serverButtonWidth - fieldGap);

            this.usernameField = this.addButton(new TextFieldWidget(
                    MinecraftClient.getInstance().textRenderer,
                    innerLeft, 54, innerWidth, 20, "Account username"));
            this.usernameField.setMaxLength(16);
            this.usernameField.setText(this.usernameValue);

            if (this.serverValue.isEmpty()) applyLastSelectedServer();
            this.serverField = this.addButton(new TextFieldWidget(
                    MinecraftClient.getInstance().textRenderer,
                    innerLeft, 94, serverFieldWidth, 20, "Server address"));
            this.serverField.setMaxLength(255);
            this.serverField.setText(this.serverValue);

            this.serverListButton = this.addButton(new MsmButtonWidget(
                    innerLeft + serverFieldWidth + fieldGap, 94,
                    serverButtonWidth, 20, "SERVERS",
                    button -> openServerPicker()));
            this.serverListButton.active = this.serverTemplateRepository != null;

            int halfWidth = Math.max(1, (innerWidth - fieldGap) / 2);
            this.authModeButton = this.addButton(new MsmButtonWidget(
                    innerLeft, 126, halfWidth, 20,
                    getAuthModeLabel(), button -> cycleAuthMode()));
            this.authModeButton.active = false;
            this.addButton(new MsmButtonWidget(
                    innerLeft + halfWidth + fieldGap, 126,
                    innerWidth - halfWidth - fieldGap, 20, "ADD ACCOUNT",
                    button -> addAccount()));
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
            int serverButtonWidth = Math.min(88, Math.max(66, innerWidth / 4));
            int serverFieldWidth = Math.max(1, innerWidth - serverButtonWidth - fieldGap);

            this.usernameField = this.addButton(new TextFieldWidget(
                    MinecraftClient.getInstance().textRenderer,
                    innerLeft, 88, innerWidth, 20, "Account username"));
            this.usernameField.setMaxLength(16);
            this.usernameField.setText(this.usernameValue);

            if (this.serverValue.isEmpty()) applyLastSelectedServer();
            this.serverField = this.addButton(new TextFieldWidget(
                    MinecraftClient.getInstance().textRenderer,
                    innerLeft, 132, serverFieldWidth, 20, "Server address"));
            this.serverField.setMaxLength(255);
            this.serverField.setText(this.serverValue);

            this.serverListButton = this.addButton(new MsmButtonWidget(
                    innerLeft + serverFieldWidth + fieldGap, 132,
                    serverButtonWidth, 20, "SERVERS",
                    button -> openServerPicker()));
            this.serverListButton.active = this.serverTemplateRepository != null;

            int halfWidth = Math.max(1, (innerWidth - fieldGap) / 2);
            this.authModeButton = this.addButton(new MsmButtonWidget(
                    innerLeft, 174, halfWidth, 20,
                    getAuthModeLabel(), button -> cycleAuthMode()));
            this.authModeButton.active = false;
            this.addButton(new MsmButtonWidget(
                    innerLeft + halfWidth + fieldGap, 174,
                    innerWidth - halfWidth - fieldGap, 20, "ADD ACCOUNT",
                    button -> addAccount()));
        }

        int pageButtonY = this.compactLayout
                ? this.accountsPanelBottom - 25 : this.height - 122;
        int availableRows = Math.max(1, (pageButtonY - this.rowStartY - 4) / 23 + 1);
        this.pageSize = Math.min(PAGE_SIZE, availableRows);

        int pageCount = Math.max(1, (this.sessions.size() + this.pageSize - 1) / this.pageSize);
        this.pageIndex = Math.min(this.requestedPage, pageCount - 1);
        int start = this.pageIndex * this.pageSize;
        int end = Math.min(start + this.pageSize, this.sessions.size());
        int rowY = this.rowStartY;

        for (int index = start; index < end; index++) {
            final SessionManager.Session session = this.sessions.get(index);
            MsmButtonWidget row = new MsmButtonWidget(
                    this.accountsPanelLeft + 10, rowY,
                    Math.max(1, this.accountsPanelWidth - 20),
                    20, getAccountLabel(session, session.getId()),
                    button -> selectAccount(session));
            row.setSelected(session.getId() == this.selectedSessionId);
            this.rowButtons.put(session.getId(), row);
            this.addButton(row);
            rowY += 23;
        }

        if (pageCount > 1) {
            int pageWidth = Math.min(100, Math.max(1, (this.accountsPanelWidth - 30) / 2));
            int pageLeft = this.accountsPanelLeft
                    + (this.accountsPanelWidth - pageWidth * 2 - 8) / 2;
            ButtonWidget previous = new MsmButtonWidget(
                    pageLeft, pageButtonY, pageWidth, 18,
                    "PREVIOUS", button -> openPage(this.pageIndex - 1));
            ButtonWidget next = new MsmButtonWidget(
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
                    (contentWidth < 540 ? "JOIN" : "JOIN / CONNECT"), button -> toggleConnection()));
            this.controlButton = this.addButton(new MsmButtonWidget(
                    contentLeft + actionWidth + actionGap, firstRowY,
                    contentWidth - actionWidth - actionGap, 20,
                    (contentWidth < 540 ? "CONTROL" : "CONTROL MANAGE"), button -> openControlManage()));
            this.removeButton = this.addButton(new MsmButtonWidget(
                    contentLeft, secondRowY, actionWidth, 20,
                    "REMOVE ACCOUNT", button -> requestRemoveAccount()));
            ((MsmButtonWidget) this.removeButton).setDanger(true);
            this.addButton(new MsmButtonWidget(
                    contentLeft + actionWidth + actionGap, secondRowY,
                    contentWidth - actionWidth - actionGap, 20,
                    "CLOSE", button -> onClose()));
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
                    actionWidth, 20, (contentWidth < 540 ? "REMOVE" : "REMOVE ACCOUNT"),
                    button -> requestRemoveAccount()));
            ((MsmButtonWidget) this.removeButton).setDanger(true);
            this.addButton(new MsmButtonWidget(
                    contentLeft + (actionWidth + actionGap) * 3, actionY,
                    actionWidth, 20, "CLOSE", button -> onClose()));
        }

        refreshControls();
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
                    this.authMode);

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

            int selectedIndex = this.sessionManager.getSessions().indexOf(created);
            int targetPage = Math.max(0, selectedIndex / this.pageSize);
            openScreen("Account " + profile.getUsername() + " added.",
                    targetPage, created.getId());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        } catch (IOException exception) {
            this.status = "Could not save account: " + exception.getMessage();
        }
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
        this.lastObservedState = null;
        this.status = "Selected " + session.getProfile().getUsername() + ".";
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
            this.status = "Select an account first.";
            return;
        }
        if (this.automationScheduler == null) {
            this.status = "Automation scheduler is unavailable.";
            return;
        }

        MinecraftClient.getInstance().openScreen(new SessionControlScreen(
                this, this.secondarySessions, this.automationScheduler,
                selected.get().getId(), selected.get().getProfile().getUsername()));
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
        MinecraftClient.getInstance().openScreen(new SessionManagerScreen(
                this.parent, this.store, this.sessionManager, this.secondarySessions,
                this.automationScheduler, this.serverTemplateRepository,
                newStatus, page, selectedId, this.authMode,
                this.usernameValue, this.serverValue, this.selectedServerName));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.usernameField != null) this.usernameField.tick();
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
                "MULTI SESSION MANAGER", this.width / 2, 11, 0xFFF1F5FA);

        int formBottom = this.compactLayout ? 174 : 212;
        int accountsTop = this.compactLayout ? 181 : 36;
        drawGlassPanel(this.formPanelLeft, 36,
                this.formPanelLeft + this.formPanelWidth, formBottom);
        drawGlassPanel(this.accountsPanelLeft, accountsTop,
                this.accountsPanelLeft + this.accountsPanelWidth, this.accountsPanelBottom);

        if (this.compactLayout) {
            drawSectionTitle("ADD ACCOUNT", this.formPanelLeft + 12, 40);
            drawSectionTitle("SERVER ADDRESS", this.formPanelLeft + 12, 82);
            drawSectionTitle("ACCOUNTS  (" + this.sessions.size() + ")",
                    this.accountsPanelLeft + 12, this.accountsLabelY);

            if (this.sessions.isEmpty()) {
                drawCenteredString(MinecraftClient.getInstance().textRenderer,
                        "No accounts added yet.", this.width / 2,
                        this.rowStartY + 14, 0xFF9EADBD);
            }

            drawGlassPanel(10, this.height - 130, this.width - 10, this.height - 82);
        } else {
            drawSectionTitle("ADD ACCOUNT", this.formPanelLeft + 12, 51);
            drawSectionTitle("USERNAME", this.formPanelLeft + 12, 73);
            drawSectionTitle("SERVER ADDRESS", this.formPanelLeft + 12, 117);
            drawSectionTitle("ACCOUNTS  (" + this.sessions.size() + ")",
                    this.accountsPanelLeft + 12, this.accountsLabelY);

            if (this.sessions.isEmpty()) {
                drawCenteredString(MinecraftClient.getInstance().textRenderer,
                        "No accounts added yet.", this.accountsPanelLeft + this.accountsPanelWidth / 2,
                        this.rowStartY + 14, 0xFF9EADBD);
            }

            drawGlassPanel(16, this.height - 88, this.width - 16, this.height - 54);
        }

        Optional<SessionManager.Session> selected = getSelectedSession();
        String selectedText = selected.isPresent()
                ? "SELECTED  " + selected.get().getProfile().getUsername()
                    + "  |  " + selected.get().getState().name()
                : "NO ACCOUNT SELECTED";
        int selectedY = this.compactLayout ? this.height - 119 : this.height - 79;
        int statusY = this.compactLayout ? this.height - 102 : this.height - 65;
        drawString(MinecraftClient.getInstance().textRenderer,
                fitText(selectedText, this.width - 32), 24, selectedY, 0xFFE7EDF5);
        drawString(MinecraftClient.getInstance().textRenderer,
                fitText(this.status, this.width - 32), 24, statusY, 0xFF9EADBD);

        super.render(mouseX, mouseY, delta);
    }

    private String fitText(String value, int pixelWidth) {
        if (value == null) return "";
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }
}
