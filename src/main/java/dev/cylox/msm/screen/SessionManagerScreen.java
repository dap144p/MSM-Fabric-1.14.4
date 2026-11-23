package dev.cylox.msm.screen;

import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.profile.SessionProfileStore;
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

public final class SessionManagerScreen extends Screen {
    private static final int PAGE_SIZE = 6;
    private static final String DEFAULT_STATUS = "Add an account, select it, then JOIN / CONNECT.";

    private final Screen parent;
    private final SessionProfileStore store;
    private final SessionManager sessionManager;
    private final SecondarySessionController secondarySessions;
    private final List<SessionProfile> profiles = new ArrayList<SessionProfile>();
    private final List<SessionManager.Session> sessions = new ArrayList<SessionManager.Session>();
    private final Map<Long, ButtonWidget> rowButtons = new LinkedHashMap<Long, ButtonWidget>();

    private final int requestedPage;
    private final long requestedSessionId;
    private final String initialStatus;

    private TextFieldWidget usernameField;
    private TextFieldWidget serverField;
    private ButtonWidget authModeButton;
    private ButtonWidget connectionButton;
    private ButtonWidget chatButton;
    private volatile String status;
    private long selectedSessionId;
    private int pageIndex;
    private int pageSize;
    private SessionAuthMode authMode;

    public SessionManagerScreen(Screen parent, SessionProfileStore store) {
        this(parent, store, new SessionManager());
    }

    public SessionManagerScreen(Screen parent, SessionProfileStore store, SessionManager sessionManager) {
        this(parent, store, sessionManager, new SecondarySessionController(sessionManager));
    }

    public SessionManagerScreen(Screen parent, SessionProfileStore store,
                                SessionManager sessionManager,
                                SecondarySessionController secondarySessions) {
        this(parent, store, sessionManager, secondarySessions,
                DEFAULT_STATUS, 0, -1L, SessionAuthMode.OFFLINE_LOCAL);
    }

    private SessionManagerScreen(Screen parent, SessionProfileStore store,
                                 SessionManager sessionManager,
                                 SecondarySessionController secondarySessions,
                                 String status, int page, long selectedSessionId,
                                 SessionAuthMode authMode) {
        super(new LiteralText("Multi Session Manager"));
        if (store == null || sessionManager == null || secondarySessions == null) {
            throw new IllegalArgumentException("Profile store, session manager, and network controller are required");
        }

        this.parent = parent;
        this.store = store;
        this.sessionManager = sessionManager;
        this.secondarySessions = secondarySessions;
        this.initialStatus = status == null ? DEFAULT_STATUS : status;
        this.status = this.initialStatus;
        this.requestedPage = Math.max(0, page);
        this.requestedSessionId = selectedSessionId;
        this.selectedSessionId = selectedSessionId;
        this.authMode = authMode == null ? SessionAuthMode.OFFLINE_LOCAL : authMode;

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
    }

    private void refreshSessions() {
        this.sessions.clear();
        this.sessions.addAll(this.sessionManager.getSessions());

        for (Map.Entry<Long, ButtonWidget> entry : this.rowButtons.entrySet()) {
            Optional<SessionManager.Session> session = this.sessionManager.find(entry.getKey());
            if (session.isPresent()) {
                entry.getValue().setMessage(getAccountLabel(session.get(), entry.getKey()));
            }
        }
        refreshControls();
    }

    private String getAccountLabel(SessionManager.Session session, long id) {
        String prefix = id == this.selectedSessionId ? "> " : "";
        return prefix + session.getProfile().getUsername()
                + "  [" + session.getState().name() + "]";
    }

    private Optional<SessionManager.Session> getSelectedSession() {
        return this.selectedSessionId < 0L
                ? Optional.<SessionManager.Session>empty()
                : this.sessionManager.find(this.selectedSessionId);
    }

    private void refreshControls() {
        Optional<SessionManager.Session> selected = getSelectedSession();
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
        if (this.chatButton != null) {
            this.chatButton.active = selected.isPresent();
        }
    }

    @Override
    protected void init() {
        int contentWidth = Math.max(120, this.width - 24);
        int contentLeft = (this.width - contentWidth) / 2;
        int formWidth = Math.min(340, contentWidth);
        int formLeft = (this.width - formWidth) / 2;
        int authWidth = Math.min(100, Math.max(82, formWidth / 3));
        int serverWidth = formWidth - authWidth - 4;

        this.rowButtons.clear();
        this.pageSize = Math.max(1, Math.min(PAGE_SIZE, (this.height - 245) / 22));
        this.pageIndex = this.requestedPage;

        this.usernameField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer,
                formLeft, 48, formWidth, 20, "Account username"));
        this.usernameField.setMaxLength(16);

        this.serverField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer,
                formLeft, 78, serverWidth, 20, "Server address"));
        this.serverField.setMaxLength(255);

        this.authModeButton = this.addButton(new ButtonWidget(
                formLeft + serverWidth + 4, 78, authWidth, 20,
                getAuthModeLabel(), button -> cycleAuthMode()));
        this.addButton(new ButtonWidget(
                formLeft, 108, formWidth, 20, "ADD ACCOUNT",
                button -> addAccount()));

        int pageCount = Math.max(1, (this.sessions.size() + this.pageSize - 1) / this.pageSize);
        this.pageIndex = Math.min(this.pageIndex, pageCount - 1);
        int start = this.pageIndex * this.pageSize;
        int end = Math.min(start + this.pageSize, this.sessions.size());
        int rowY = 158;

        for (int index = start; index < end; index++) {
            final SessionManager.Session session = this.sessions.get(index);
            ButtonWidget row = new ButtonWidget(
                    contentLeft, rowY, contentWidth, 20,
                    getAccountLabel(session, session.getId()),
                    button -> selectAccount(session));
            this.rowButtons.put(session.getId(), row);
            this.addButton(row);
            rowY += 23;
        }

        int pageButtonWidth = Math.min(100, (contentWidth - 8) / 2);
        int pageLeft = (this.width - (pageButtonWidth * 2 + 8)) / 2;
        ButtonWidget previous = new ButtonWidget(
                pageLeft, this.height - 50, pageButtonWidth, 18,
                "PREVIOUS", button -> openPage(this.pageIndex - 1));
        ButtonWidget next = new ButtonWidget(
                pageLeft + pageButtonWidth + 8, this.height - 50,
                pageButtonWidth, 18, "NEXT", button -> openPage(this.pageIndex + 1));
        previous.active = this.pageIndex > 0;
        next.active = this.pageIndex + 1 < pageCount;
        this.addButton(previous);
        this.addButton(next);

        int gap = 4;
        int actionWidth = (contentWidth - gap * 2) / 3;
        int actionY = this.height - 24;
        this.connectionButton = this.addButton(new ButtonWidget(
                contentLeft, actionY, actionWidth, 20,
                "JOIN / CONNECT", button -> toggleConnection()));
        this.chatButton = this.addButton(new ButtonWidget(
                contentLeft + actionWidth + gap, actionY, actionWidth, 20,
                "OPEN CHAT", button -> openChat()));
        this.addButton(new ButtonWidget(
                contentLeft + (actionWidth + gap) * 2, actionY,
                actionWidth, 20, "CLOSE", button -> onClose()));

        refreshControls();
    }

    private void addAccount() {
        SessionManager.Session created = null;
        try {
            SessionProfile profile = new SessionProfile(
                    this.usernameField.getText(),
                    this.serverField.getText(),
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
        openScreen("Selected " + session.getProfile().getUsername() + ".",
                this.pageIndex, session.getId());
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
                this.secondarySessions.disconnect(session.getId());
                this.status = "Disconnect requested for " + session.getProfile().getUsername() + ".";
            } else {
                this.secondarySessions.connect(session.getId());
                this.status = "Joining as " + session.getProfile().getUsername() + "...";
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        }
        refreshSessions();
    }

    private void openChat() {
        Optional<SessionManager.Session> selected = getSelectedSession();
        if (!selected.isPresent()) {
            this.status = "Select an account first.";
            return;
        }

        MinecraftClient.getInstance().openScreen(new SessionChatScreen(
                this, this.secondarySessions, selected.get().getId(),
                selected.get().getProfile().getUsername()));
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
        openScreen(this.status, page, this.selectedSessionId);
    }

    private void openScreen(String newStatus, int page, long selectedId) {
        MinecraftClient.getInstance().openScreen(new SessionManagerScreen(
                this.parent, this.store, this.sessionManager, this.secondarySessions,
                newStatus, page, selectedId, this.authMode));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.usernameField != null) this.usernameField.tick();
        if (this.serverField != null) this.serverField.tick();
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
        this.renderBackground();
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                this.title.asFormattedString(), this.width / 2, 10, 0xFFFFFF);

        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                "ADD ACCOUNT", this.width / 2, 31, 0xA0A0A0);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                "ACCOUNTS (" + this.sessions.size() + ")", this.width / 2, 137, 0xA0A0A0);

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
