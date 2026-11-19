package dev.cylox.msm.screen;

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
import java.util.List;
import java.util.Optional;

public final class SessionManagerScreen extends Screen {
    private static final int PAGE_SIZE = 5;
    private static final String DEFAULT_STATUS =
            "Slots are local only. No secondary connection is active.";

    private final Screen parent;
    private final SessionProfileStore store;
    private final SessionManager sessionManager;
    private final List<SessionProfile> profiles = new ArrayList<SessionProfile>();
    private final List<SessionManager.Session> sessions = new ArrayList<SessionManager.Session>();
    private final int requestedPage;
    private TextFieldWidget usernameField;
    private TextFieldWidget serverField;
    private String status = DEFAULT_STATUS;
    private long selectedSessionId = -1L;
    private int pageIndex;

    public SessionManagerScreen(Screen parent, SessionProfileStore store) {
        this(parent, store, new SessionManager());
    }

    public SessionManagerScreen(Screen parent, SessionProfileStore store, SessionManager sessionManager) {
        this(parent, store, sessionManager, DEFAULT_STATUS, 0);
    }

    private SessionManagerScreen(Screen parent, SessionProfileStore store,
                                 SessionManager sessionManager, String status, int page) {
        super(new LiteralText("Cylox Multi Session Manager"));
        if (store == null || sessionManager == null) {
            throw new IllegalArgumentException("Profile store and session manager are required");
        }
        this.parent = parent;
        this.store = store;
        this.sessionManager = sessionManager;
        this.status = status;
        this.requestedPage = Math.max(0, page);
        try {
            this.profiles.addAll(store.load());
        } catch (IOException exception) {
            this.status = "Could not load profiles: " + exception.getMessage();
        }
        synchronizeSessions();
    }

    private void synchronizeSessions() {
        for (SessionProfile profile : this.profiles) {
            if (!this.sessionManager.findByProfile(profile).isPresent()
                    && this.sessionManager.remainingCapacity() > 0) {
                this.sessionManager.create(profile);
            }
        }
        this.sessions.addAll(this.sessionManager.getSessions());
        if (this.profiles.size() > SessionManager.MAX_SESSIONS) {
            this.status = "Only the first 20 saved profiles can occupy session slots.";
        }
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(220, this.width - 24);
        int left = (this.width - fieldWidth) / 2;
        int buttonWidth = Math.min(220, this.width - 24);
        int buttonLeft = (this.width - buttonWidth) / 2;

        this.usernameField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer, left, 47, fieldWidth, 20, "Username"));
        this.usernameField.setMaxLength(16);
        this.serverField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer, left, 87, fieldWidth, 20, "Server address"));
        this.serverField.setMaxLength(255);

        this.addButton(new ButtonWidget(buttonLeft, 116, buttonWidth, 20,
                "ADD SESSION SLOT", button -> addSession()));

        int pageCount = Math.max(1, (this.sessions.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        this.pageIndex = Math.min(this.requestedPage, pageCount - 1);
        int startIndex = this.pageIndex * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, this.sessions.size());
        int rowY = 158;

        for (int index = startIndex; index < endIndex; index++) {
            final SessionManager.Session session = this.sessions.get(index);
            final int slotNumber = index + 1;
            SessionProfile profile = session.getProfile();
            String label = "S" + slotNumber + "  " + profile.getUsername()
                    + "  " + session.getState().name();
            if (label.length() > 34) label = label.substring(0, 31) + "...";
            this.addButton(new ButtonWidget(buttonLeft, rowY, buttonWidth, 18,
                    label, button -> {
                this.selectedSessionId = session.getId();
                this.status = profile.getUsername() + " | "
                        + session.getState().name() + " | " + profile.getServerAddress();
            }));
            rowY += 21;
        }

        int pageButtonWidth = Math.min(100, (buttonWidth - 8) / 2);
        int pageButtonLeft = (this.width - (pageButtonWidth * 2 + 8)) / 2;
        ButtonWidget previous = new ButtonWidget(pageButtonLeft, 275, pageButtonWidth, 20,
                "PREVIOUS", button -> openPage(this.pageIndex - 1));
        ButtonWidget next = new ButtonWidget(pageButtonLeft + pageButtonWidth + 8, 275,
                pageButtonWidth, 20, "NEXT", button -> openPage(this.pageIndex + 1));
        previous.active = this.pageIndex > 0;
        next.active = this.pageIndex + 1 < pageCount;
        this.addButton(previous);
        this.addButton(next);

        this.addButton(new ButtonWidget(buttonLeft, this.height - 25, buttonWidth / 2 - 2, 20,
                "REMOVE", button -> removeSelected()));
        this.addButton(new ButtonWidget(buttonLeft + buttonWidth / 2 + 2, this.height - 25,
                buttonWidth / 2 - 2, 20, "CLOSE", button -> onClose()));
    }

    private void openPage(int page) {
        MinecraftClient.getInstance().openScreen(new SessionManagerScreen(
                this.parent, this.store, this.sessionManager, this.status, page));
    }

    private void addSession() {
        try {
            SessionProfile profile = new SessionProfile(this.usernameField.getText(), this.serverField.getText());
            if (this.profiles.contains(profile)) {
                this.status = "That username and server are already saved.";
                return;
            }
            if (this.profiles.size() >= SessionManager.MAX_SESSIONS
                    || this.sessionManager.remainingCapacity() <= 0) {
                this.status = "Session limit reached (20).";
                return;
            }

            SessionManager.Session created = this.sessionManager.create(profile);
            this.profiles.add(profile);
            try {
                this.store.save(this.profiles);
            } catch (IOException exception) {
                this.profiles.remove(profile);
                this.sessionManager.remove(created.getId());
                throw exception;
            }

            int lastPage = Math.max(0, (this.profiles.size() - 1) / PAGE_SIZE);
            MinecraftClient.getInstance().openScreen(new SessionManagerScreen(
                    this.parent, this.store, this.sessionManager,
                    "Session slot added. Status: OFFLINE (not connected).", lastPage));
        } catch (IllegalArgumentException exception) {
            this.status = exception.getMessage();
        } catch (IllegalStateException exception) {
            this.status = exception.getMessage();
        } catch (IOException exception) {
            this.status = "Could not save session: " + exception.getMessage();
        }
    }

    private void removeSelected() {
        if (this.selectedSessionId < 0L) {
            this.status = "Select a session slot first.";
            return;
        }

        Optional<SessionManager.Session> selected =
                this.sessionManager.find(this.selectedSessionId);
        if (!selected.isPresent()) {
            this.status = "The selected session no longer exists.";
            return;
        }

        SessionManager.Session session = selected.get();
        SessionState previousState = session.getState();
        if (previousState == SessionState.CONNECTING || previousState == SessionState.ONLINE) {
            this.status = "Disconnect the session before removing it.";
            return;
        }

        SessionProfile profile = session.getProfile();
        int profileIndex = this.profiles.indexOf(profile);
        if (profileIndex < 0) {
            this.status = "The selected profile is no longer saved.";
            return;
        }

        if (!this.sessionManager.remove(session.getId())) {
            this.status = "The selected session could not be removed.";
            return;
        }

        this.profiles.remove(profileIndex);
        try {
            this.store.save(this.profiles);
            int page = Math.min(this.pageIndex,
                    Math.max(0, (this.profiles.size() - 1) / PAGE_SIZE));
            MinecraftClient.getInstance().openScreen(new SessionManagerScreen(
                    this.parent, this.store, this.sessionManager, "Session slot removed.", page));
        } catch (IOException exception) {
            this.profiles.add(profileIndex, profile);
            SessionManager.Session restored = this.sessionManager.create(profile);
            if (previousState == SessionState.DISCONNECTED || previousState == SessionState.FAILED) {
                this.sessionManager.updateState(restored.getId(), SessionState.CONNECTING);
                this.sessionManager.updateState(restored.getId(), previousState);
            }
            this.status = "Could not save profile changes: " + exception.getMessage();
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.usernameField.tick();
        this.serverField.tick();
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
                this.title.asFormattedString(), this.width / 2, 12, 0xFFFFFF);
        drawString(MinecraftClient.getInstance().textRenderer, "ADD PROFILE TO SESSION SLOT", 12, 32, 0x8CCBFF);
        drawString(MinecraftClient.getInstance().textRenderer, "USERNAME", 12, 37, 0xA0A0A0);
        drawString(MinecraftClient.getInstance().textRenderer, "SERVER ADDRESS", 12, 77, 0xA0A0A0);
        drawString(MinecraftClient.getInstance().textRenderer,
                "SESSION SLOTS (" + this.sessions.size() + "/20)", 12, 143, 0x8CCBFF);
        int pageCount = Math.max(1, (this.sessions.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                "PAGE " + (this.pageIndex + 1) + "/" + pageCount, this.width / 2, 300, 0xA0A0A0);
        drawString(MinecraftClient.getInstance().textRenderer, this.status, 12, this.height - 45, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
    }
}
