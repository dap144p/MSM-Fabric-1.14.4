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

    private TextFieldWidget usernameField;
    private TextFieldWidget serverField;
    private ButtonWidget authModeButton;
    private ButtonWidget serverListButton;
    private ButtonWidget connectionButton;
    private ButtonWidget controlButton;
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
            }
        }
        refreshControls();
    }

    private String getAccountLabel(SessionManager.Session session, long id) {
        String prefix = id == this.selectedSessionId ? "> " : "";
        String label = prefix + session.getProfile().getUsername()
                + "  [" + session.getState().name() + "]";
        int maxCharacters = Math.max(8, (this.width - 42) / 6);
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
        int contentWidth = Math.max(1, this.width - 24);
        int contentLeft = (this.width - contentWidth) / 2;
        int formWidth = Math.min(340, contentWidth);
        int formLeft = (this.width - formWidth) / 2;
        boolean compactForm = formWidth < 230;

        this.rowButtons.clear();

        int usernameY = 46;
        int serverY = 75;
        int authY = 103;
        int addY = 131;
        int serverButtonWidth = compactForm ? formWidth : Math.min(84, Math.max(64, formWidth / 4));
        int serverFieldWidth = compactForm ? formWidth : Math.max(1, formWidth - serverButtonWidth - 4);

        if (compactForm) {
            usernameY = 42;
            serverY = 70;
            authY = 126;
            addY = 154;
            this.accountsLabelY = 184;
        } else {
            this.accountsLabelY = 162;
        }
        this.rowStartY = this.accountsLabelY + 21;

        this.usernameField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer,
                formLeft, usernameY, formWidth, 20, "Account username"));
        this.usernameField.setMaxLength(16);
        this.usernameField.setText(this.usernameValue);

        this.serverField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer,
                formLeft, serverY, serverFieldWidth, 20, "Server address"));
        this.serverField.setMaxLength(255);
        if (this.serverValue.isEmpty()) {
            applyLastSelectedServer();
        }
        this.serverField.setText(this.serverValue);

        this.serverListButton = this.addButton(new MsmButtonWidget(
                compactForm ? formLeft : formLeft + serverFieldWidth + 4,
                compactForm ? serverY + 28 : serverY,
                serverButtonWidth, 20, compactForm ? "SERVER LIST" : "SERVERS",
                button -> openServerPicker()));
        this.serverListButton.active = this.serverTemplateRepository != null;

        this.authModeButton = this.addButton(new MsmButtonWidget(
                formLeft, authY, formWidth, 20,
                getAuthModeLabel(), button -> cycleAuthMode()));
        this.addButton(new MsmButtonWidget(
                formLeft, addY, formWidth, 20, "ADD ACCOUNT",
                button -> addAccount()));

        int reservedBottom = this.height - 96;
        int availableRows = Math.max(1,
                (reservedBottom - this.rowStartY - 20) / 23 + 1);
        this.pageSize = Math.min(PAGE_SIZE, availableRows);

        int pageCount = Math.max(1, (this.sessions.size() + this.pageSize - 1) / this.pageSize);
        this.pageIndex = Math.min(this.requestedPage, pageCount - 1);
        int start = this.pageIndex * this.pageSize;
        int end = Math.min(start + this.pageSize, this.sessions.size());
        int rowY = this.rowStartY;

        for (int index = start; index < end; index++) {
            final SessionManager.Session session = this.sessions.get(index);
            ButtonWidget row = new MsmButtonWidget(
                    contentLeft, rowY, contentWidth, 20,
                    getAccountLabel(session, session.getId()),
                    button -> selectAccount(session));
            this.rowButtons.put(session.getId(), row);
            this.addButton(row);
            rowY += 23;
        }

        if (pageCount > 1) {
            int pageButtonWidth = Math.min(100, Math.max(1, (contentWidth - 8) / 2));
            int pageLeft = (this.width - (pageButtonWidth * 2 + 8)) / 2;
            ButtonWidget previous = new MsmButtonWidget(
                    pageLeft, this.height - 50, pageButtonWidth, 18,
                    "PREVIOUS", button -> openPage(this.pageIndex - 1));
            ButtonWidget next = new MsmButtonWidget(
                    pageLeft + pageButtonWidth + 8, this.height - 50,
                    pageButtonWidth, 18, "NEXT", button -> openPage(this.pageIndex + 1));
            previous.active = this.pageIndex > 0;
            next.active = this.pageIndex + 1 < pageCount;
            this.addButton(previous);
            this.addButton(next);
        }

        int gap = 4;
        int actionWidth = Math.max(1, (contentWidth - gap * 2) / 3);
        int actionY = this.height - 24;
        this.connectionButton = this.addButton(new MsmButtonWidget(
                contentLeft, actionY, actionWidth, 20,
                "JOIN / CONNECT", button -> toggleConnection()));
        this.controlButton = this.addButton(new MsmButtonWidget(
                contentLeft + actionWidth + gap, actionY, actionWidth, 20,
                "CONTROL MANAGE", button -> openControlManage()));
        this.addButton(new MsmButtonWidget(
                contentLeft + (actionWidth + gap) * 2, actionY,
                actionWidth, 20, "CLOSE", button -> onClose()));

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
        int contentWidth = Math.max(1, this.width - 24);
        int contentLeft = (this.width - contentWidth) / 2;
        int formWidth = Math.min(340, contentWidth);
        int formLeft = (this.width - formWidth) / 2;
        drawGlassPanel(formLeft - 8, 20, formLeft + formWidth + 8,
                this.accountsLabelY - 10);
        drawGlassPanel(contentLeft, this.accountsLabelY - 7,
                contentLeft + contentWidth, this.height - 96);
        drawGlassPanel(contentLeft, this.height - 91,
                contentLeft + contentWidth, this.height - 57);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                this.title.asFormattedString(), this.width / 2, 10, 0xFFFFFF);

        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                "ADD ACCOUNT", this.width / 2, 25, 0xA0A0A0);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                "ACCOUNTS (" + this.sessions.size() + ")", this.width / 2,
                this.accountsLabelY, 0xA0A0A0);
        if (this.sessions.isEmpty()) {
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    "No accounts yet. Add one above.", this.width / 2,
                    this.rowStartY + 12, 0xA0A0A0);
        }

        Optional<SessionManager.Session> selected = getSelectedSession();
        String selectedText = selected.isPresent()
                ? "SELECTED: " + selected.get().getProfile().getUsername()
                    + " | " + selected.get().getState().name()
                : "SELECT AN ACCOUNT";
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                fitText(selectedText, this.width - 24), this.width / 2, this.height - 77, 0xFFFFFF);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                fitText(this.status, this.width - 24), this.width / 2, this.height - 64, 0xA0A0A0);

        super.render(mouseX, mouseY, delta);
    }

    private String fitText(String value, int pixelWidth) {
        if (value == null) return "";
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }
}
