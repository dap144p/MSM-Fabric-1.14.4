package dev.cylox.msm.screen;

import dev.cylox.msm.MsmClient;
import dev.cylox.msm.automation.SessionAutomationScheduler;
import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SessionManagerScreen extends MsmScreen {
    private static final int PAGE_SIZE = 5;

    private TextFieldWidget usernameField;
    private TextFieldWidget serverField;
    private ButtonWidget authModeButton;
    private ButtonWidget connectButton;
    private ButtonWidget controlButton;
    private ButtonWidget removeButton;
    private final List<ButtonWidget> accountButtons = new ArrayList<>();
    private SessionAuthMode authMode = SessionAuthMode.OFFLINE_LOCAL;
    private long selectedSessionId = -1L;
    private int pageIndex;
    private int pageSize = PAGE_SIZE;
    private String status = "Add an account, select it, then connect or open Control Manage.";

    public SessionManagerScreen(Screen parent) {
        super(Text.literal("Multi Session Manager"), parent);
    }

    @Override
    protected void init() {
        this.clearChildren();
        this.accountButtons.clear();

        int margin = 12;
        int contentWidth = Math.max(1, this.width - margin * 2);
        int inputWidth = Math.max(1, (contentWidth - 8) / 2);

        this.usernameField = this.addDrawableChild(new TextFieldWidget(
                this.textRenderer, margin, 38, inputWidth, 20, Text.literal("Username")));
        this.usernameField.setMaxLength(16);
        this.usernameField.setPlaceholder(Text.literal("Offline username"));

        this.serverField = this.addDrawableChild(new TextFieldWidget(
                this.textRenderer, margin + inputWidth + 8, 38,
                contentWidth - inputWidth - 8, 20, Text.literal("Server")));
        this.serverField.setMaxLength(255);
        this.serverField.setPlaceholder(Text.literal("server.example:25565"));

        int authWidth = Math.max(1, (contentWidth - 6) / 2);
        this.authModeButton = this.addAction(margin, 64, authWidth, 20,
                authModeLabel(), button -> cycleAuthMode());
        this.authModeButton.active = false;
        this.addAction(margin + authWidth + 6, 64,
                contentWidth - authWidth - 6, 20,
                "ADD ACCOUNT", button -> addAccount());

        int listTop = 108;
        int footerY = this.height - 52;
        int availableHeight = Math.max(23, footerY - listTop - 20);
        int rows = Math.max(1, Math.min(PAGE_SIZE, availableHeight / 23));
        this.pageSize = rows;
        List<SessionManager.Session> sessions = MsmClient.getSessionManager().getSessions();
        int pageCount = Math.max(1, (sessions.size() + rows - 1) / rows);
        this.pageIndex = Math.max(0, Math.min(this.pageIndex, pageCount - 1));
        int start = this.pageIndex * rows;
        int end = Math.min(sessions.size(), start + rows);

        int rowY = listTop;
        for (int i = start; i < end; i++) {
            SessionManager.Session session = sessions.get(i);
            ButtonWidget row = this.addAction(margin, rowY, contentWidth, 20,
                    accountLabel(session), button -> selectAccount(session.getId()));
            this.accountButtons.add(row);
            rowY += 23;
        }

        if (pageCount > 1) {
            int pageWidth = Math.min(100, Math.max(54, (contentWidth - 8) / 2));
            int pageLeft = (this.width - (pageWidth * 2 + 8)) / 2;
            ButtonWidget previous = this.addAction(pageLeft, footerY - 24, pageWidth, 18,
                    "PREVIOUS", button -> changePage(-1));
            ButtonWidget next = this.addAction(pageLeft + pageWidth + 8, footerY - 24,
                    pageWidth, 18, "NEXT", button -> changePage(1));
            previous.active = this.pageIndex > 0;
            next.active = this.pageIndex + 1 < pageCount;
        }

        int actionGap = 6;
        int actionWidth = Math.max(1, (contentWidth - actionGap * 3) / 4);
        int actionY = this.height - 26;
        this.connectButton = this.addAction(margin, actionY, actionWidth, 20,
                "JOIN / CONNECT", button -> toggleConnection());
        this.controlButton = this.addAction(margin + actionWidth + actionGap, actionY,
                actionWidth, 20, "CONTROL MANAGE", button -> openControlManage());
        this.removeButton = this.addAction(margin + (actionWidth + actionGap) * 2,
                actionY, actionWidth, 20, "REMOVE ACCOUNT", button -> requestRemove());
        this.addAction(margin + (actionWidth + actionGap) * 3, actionY,
                contentWidth - (actionWidth + actionGap) * 3, 20,
                "CLOSE", button -> close());

        refreshControls();
    }

    private String authModeLabel() {
        return this.authMode == SessionAuthMode.OFFLINE_LOCAL ? "AUTH: OFFLINE" : "AUTH: MICROSOFT";
    }

    private void cycleAuthMode() {
        this.authMode = this.authMode == SessionAuthMode.OFFLINE_LOCAL
                ? SessionAuthMode.MICROSOFT : SessionAuthMode.OFFLINE_LOCAL;
        this.authModeButton.setMessage(Text.literal(authModeLabel()));
    }

    private void addAccount() {
        String username = this.usernameField.getText().trim();
        String address = this.serverField.getText().trim();
        try {
            SessionProfile profile = new SessionProfile(username, address, this.authMode);
            SessionManager.Session created = MsmClient.addProfile(profile);
            this.selectedSessionId = created.getId();
            this.pageIndex = Math.max(0, (MsmClient.getSessionManager().getSessions().size() - 1) / PAGE_SIZE);
            this.status = "Account " + username + " added.";
            this.init(this.client, this.width, this.height);
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            this.status = "Could not add account: " + safeMessage(exception);
        }
    }

    private void selectAccount(long sessionId) {
        this.selectedSessionId = sessionId;
        this.init(this.client, this.width, this.height);
    }

    private Optional<SessionManager.Session> selectedSession() {
        return this.selectedSessionId < 0
                ? Optional.empty() : MsmClient.getSessionManager().find(this.selectedSessionId);
    }

    private void refreshControls() {
        Optional<SessionManager.Session> selected = selectedSession();
        if (!selected.isPresent()) {
            this.connectButton.active = false;
            this.controlButton.active = false;
            this.removeButton.active = false;
            this.connectButton.setMessage(Text.literal("JOIN / CONNECT"));
            return;
        }

        SessionManager.Session session = selected.get();
        SessionState state = session.getState();
        this.connectButton.active = true;
        this.controlButton.active = true;
        this.removeButton.active = state != SessionState.CONNECTING && state != SessionState.ONLINE;
        this.connectButton.setMessage(Text.literal(
                state == SessionState.CONNECTING ? "CANCEL"
                        : state == SessionState.ONLINE ? "DISCONNECT" : "JOIN / CONNECT"));

        if (state == SessionState.CONNECTING) {
            this.status = "CONNECTING as " + session.getProfile().getUsername() + "...";
        } else if (state == SessionState.ONLINE) {
            this.status = "ONLINE as " + session.getProfile().getUsername() + ".";
        }
    }

    private void toggleConnection() {
        Optional<SessionManager.Session> selected = selectedSession();
        if (!selected.isPresent()) return;
        SessionAutomationScheduler scheduler = MsmClient.getAutomationScheduler();
        try {
            SessionState state = selected.get().getState();
            if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
                scheduler.disconnectManually(this.selectedSessionId);
                this.status = "Manual disconnect requested.";
            } else {
                scheduler.connectManually(this.selectedSessionId);
                this.status = "Connection requested.";
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = safeMessage(exception);
        }
        refreshControls();
    }

    private void openControlManage() {
        Optional<SessionManager.Session> selected = selectedSession();
        if (!selected.isPresent()) return;
        this.client.setScreen(new SessionControlScreen(
                this, this.selectedSessionId, selected.get().getProfile().getUsername()));
    }

    private void requestRemove() {
        Optional<SessionManager.Session> selected = selectedSession();
        if (!selected.isPresent()) return;
        SessionManager.Session session = selected.get();
        if (session.getState() == SessionState.CONNECTING || session.getState() == SessionState.ONLINE) {
            this.status = "Disconnect this account before removing it.";
            return;
        }
        this.client.setScreen(new ConfirmRemoveAccountScreen(this, session.getId(),
                session.getProfile().getUsername(), session.getProfile().getServerAddress()));
    }

    private void changePage(int amount) {
        this.pageIndex = Math.max(0, this.pageIndex + amount);
        this.init(this.client, this.width, this.height);
    }

    private String accountLabel(SessionManager.Session session) {
        String selected = session.getId() == this.selectedSessionId ? "> " : "";
        return fit(selected + session.getProfile().getUsername() + "  |  "
                + session.getProfile().getServerAddress() + "  |  " + session.getState(),
                this.width - 42);
    }

    @Override
    public void tick() {
        super.tick();
        refreshControls();
        List<SessionManager.Session> sessions = MsmClient.getSessionManager().getSessions();
        for (int i = 0; i < this.accountButtons.size(); i++) {
            int index = this.pageIndex * this.pageSize + i;
            if (index < sessions.size()) {
                this.accountButtons.get(i).setMessage(Text.literal(accountLabel(sessions.get(index))));
            }
        }
    }

    public void setStatus(String status) {
        this.status = status == null ? "" : status;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        panel(context, 8, 8, this.width - 8, this.height - 8);
        centered(context, "MULTI SESSION MANAGER", this.width / 2, 17, 0xFFF1F5FA);
        text(context, "USERNAME", 12, 28, 0xFF9EADBD);
        text(context, "SERVER ADDRESS", Math.max(12, this.width / 2 + 4), 28, 0xFF9EADBD);
        text(context, "ACCOUNTS  (" + MsmClient.getSessionManager().size() + ")",
                12, 91, 0xFF9EADBD);
        int maxCharacters = Math.max(8, (this.width - 24) / 6);
        centered(context, fit(this.status, maxCharacters * 6),
                this.width / 2, this.height - 67, 0xFFAEBBCC);
        super.render(context, mouseX, mouseY, delta);
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
