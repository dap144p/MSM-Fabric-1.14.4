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
    private static final String DEFAULT_STATUS =
            "Session slots are local only. No secondary connection is active.";

    private final Screen parent;
    private final SessionProfileStore store;
    private final SessionManager sessionManager;
    private final List<SessionProfile> profiles = new ArrayList<SessionProfile>();
    private final List<SessionManager.Session> sessions = new ArrayList<SessionManager.Session>();
    private TextFieldWidget usernameField;
    private TextFieldWidget serverField;
    private String status = DEFAULT_STATUS;
    private int selectedIndex = -1;

    public SessionManagerScreen(Screen parent, SessionProfileStore store) {
        this(parent, store, new SessionManager());
    }

    public SessionManagerScreen(Screen parent, SessionProfileStore store, SessionManager sessionManager) {
        this(parent, store, sessionManager, DEFAULT_STATUS);
    }

    private SessionManagerScreen(Screen parent, SessionProfileStore store,
                                 SessionManager sessionManager, String status) {
        super(new LiteralText("Cylox Multi Session Manager"));
        this.parent = parent;
        this.store = store;
        this.sessionManager = sessionManager;
        this.status = status;
        try {
            this.profiles.addAll(store.load());
        } catch (IOException exception) {
            this.status = "Could not load profiles: " + exception.getMessage();
        }
        synchronizeSessions();
    }

    private void synchronizeSessions() {
        for (SessionProfile profile : this.profiles) {
            Optional<SessionManager.Session> session = this.sessionManager.findByProfile(profile);
            if (!session.isPresent() && this.sessionManager.remainingCapacity() > 0) {
                session = Optional.of(this.sessionManager.create(profile));
            }
            if (session.isPresent()) this.sessions.add(session.get());
        }
        if (this.profiles.size() > SessionManager.MAX_SESSIONS) {
            this.status = "Only 20 saved profiles can occupy session slots at once.";
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

        int visibleSessions = Math.min(this.sessions.size(), Math.max(0, (this.height - 207) / 21));
        int rowY = 158;
        for (int index = 0; index < visibleSessions; index++) {
            final int sessionIndex = index;
            SessionManager.Session session = this.sessions.get(index);
            SessionProfile profile = session.getProfile();
            String label = "S" + (index + 1) + "  " + profile.getUsername() + "  "
                    + session.getState().name();
            if (label.length() > 34) label = label.substring(0, 31) + "...";
            this.addButton(new ButtonWidget(buttonLeft, rowY, buttonWidth, 18,
                    label, button -> {
                this.selectedIndex = sessionIndex;
                this.status = profile.getUsername() + " | " + profile.getServerAddress()
                        + " | " + session.getState().name();
            }));
            rowY += 21;
        }

        this.addButton(new ButtonWidget(buttonLeft, this.height - 25, buttonWidth / 2 - 2, 20,
                "REMOVE", button -> removeSelected()));
        this.addButton(new ButtonWidget(buttonLeft + buttonWidth / 2 + 2, this.height - 25,
                buttonWidth / 2 - 2, 20, "CLOSE", button -> onClose()));
    }

    private void addSession() {
        try {
            SessionProfile profile = new SessionProfile(this.usernameField.getText(), this.serverField.getText());
            for (SessionProfile existing : this.profiles) {
                if (existing.equals(profile)) {
                    this.status = "That username and server are already saved.";
                    return;
                }
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

            MinecraftClient.getInstance().openScreen(new SessionManagerScreen(
                    this.parent, this.store, this.sessionManager,
                    "Session slot added. Status: OFFLINE (not connected)."));
        } catch (IllegalArgumentException exception) {
            this.status = exception.getMessage();
        } catch (IllegalStateException exception) {
            this.status = exception.getMessage();
        } catch (IOException exception) {
            this.status = "Could not save session: " + exception.getMessage();
        }
    }

    private void removeSelected() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.sessions.size()) {
            this.status = "Select a session slot first.";
            return;
        }

        SessionManager.Session selected = this.sessions.get(this.selectedIndex);
        SessionState state = selected.getState();
        if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
            this.status = "Disconnect the session before removing it.";
            return;
        }

        SessionProfile profile = selected.getProfile();
        int profileIndex = this.profiles.indexOf(profile);
        if (profileIndex < 0) {
            this.status = "The selected profile is no longer saved.";
            return;
        }

        SessionProfile removed = this.profiles.remove(profileIndex);
        try {
            this.store.save(this.profiles);
            this.sessionManager.remove(selected.getId());
            MinecraftClient.getInstance().openScreen(new SessionManagerScreen(
                    this.parent, this.store, this.sessionManager, "Session slot removed."));
        } catch (IOException exception) {
            this.profiles.add(profileIndex, removed);
            this.status = "Could not save profile changes: " + exception.getMessage();
        } catch (IllegalStateException exception) {
            this.profiles.add(profileIndex, removed);
            this.status = exception.getMessage();
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
                "SESSIONS (" + this.sessions.size() + "/20)", 12, 143, 0x8CCBFF);
        drawString(MinecraftClient.getInstance().textRenderer, this.status, 12, this.height - 45, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
    }
}
