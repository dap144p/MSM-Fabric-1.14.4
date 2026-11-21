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
import java.util.Locale;
import java.util.Optional;

public final class SessionManagerScreen extends Screen {
    private static final int PAGE_SIZE = 5;
    private static final String DEFAULT_STATUS =
            "Profiles are local only. Secondary Minecraft connections are not available.";

    private final Screen parent;
    private final SessionProfileStore store;
    private final SessionManager sessionManager;
    private final List<SessionProfile> profiles = new ArrayList<SessionProfile>();
    private final List<SessionManager.Session> sessions = new ArrayList<SessionManager.Session>();
    private final int requestedPage;
    private final String requestedSearch;
    private final boolean editing;
    private final boolean confirmRemoval;

    private TextFieldWidget usernameField;
    private TextFieldWidget serverField;
    private TextFieldWidget searchField;
    private String status = DEFAULT_STATUS;
    private long selectedSessionId = -1L;
    private int pageIndex;
    private int pageSize = PAGE_SIZE;

    public SessionManagerScreen(Screen parent, SessionProfileStore store) {
        this(parent, store, new SessionManager());
    }

    public SessionManagerScreen(Screen parent, SessionProfileStore store, SessionManager sessionManager) {
        this(parent, store, sessionManager, DEFAULT_STATUS, 0, -1L, "", false, false);
    }

    private SessionManagerScreen(Screen parent, SessionProfileStore store,
                                 SessionManager sessionManager, String status, int page,
                                 long selectedSessionId, String searchQuery,
                                 boolean editing, boolean confirmRemoval) {
        super(new LiteralText("Cylox Multi Session Manager"));
        if (store == null || sessionManager == null) {
            throw new IllegalArgumentException("Profile store and session manager are required");
        }
        this.parent = parent;
        this.store = store;
        this.sessionManager = sessionManager;
        this.status = status == null ? DEFAULT_STATUS : status;
        this.requestedPage = Math.max(0, page);
        this.selectedSessionId = selectedSessionId;
        this.requestedSearch = searchQuery == null ? "" : searchQuery.trim();
        this.editing = editing;
        this.confirmRemoval = confirmRemoval;

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

    private List<SessionManager.Session> getFilteredSessions() {
        String query = this.requestedSearch.toLowerCase(Locale.ROOT);
        List<SessionManager.Session> filtered = new ArrayList<SessionManager.Session>();
        for (SessionManager.Session session : this.sessions) {
            SessionProfile profile = session.getProfile();
            String searchable = (profile.getUsername() + " " + profile.getServerAddress())
                    .toLowerCase(Locale.ROOT);
            if (query.isEmpty() || searchable.contains(query)) {
                filtered.add(session);
            }
        }
        return filtered;
    }

    private Optional<SessionManager.Session> getSelectedSession() {
        return this.selectedSessionId < 0L
                ? Optional.<SessionManager.Session>empty()
                : this.sessionManager.find(this.selectedSessionId);
    }

    @Override
    protected void init() {
        int contentWidth = Math.max(120, this.width - 24);
        int contentLeft = (this.width - contentWidth) / 2;
        int fieldWidth = Math.min(240, contentWidth);
        int fieldLeft = (this.width - fieldWidth) / 2;

        this.pageSize = Math.max(1, Math.min(PAGE_SIZE, (this.height - 270) / 20 + 1));

        this.usernameField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer, fieldLeft, 48, fieldWidth, 20, "Username"));
        this.usernameField.setMaxLength(16);
        this.serverField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer, fieldLeft, 82, fieldWidth, 20, "Server address"));
        this.serverField.setMaxLength(255);

        if (this.editing) {
            Optional<SessionManager.Session> selected = getSelectedSession();
            if (selected.isPresent()) {
                this.usernameField.setText(selected.get().getProfile().getUsername());
                this.serverField.setText(selected.get().getProfile().getServerAddress());
            }
        }

        String saveLabel = this.editing ? "SAVE CHANGES" : "ADD SESSION SLOT";
        this.addButton(new ButtonWidget(contentLeft, 107, contentWidth, 20,
                saveLabel, button -> saveProfile()));

        int smallButtonWidth = Math.min(54, Math.max(44, contentWidth / 4));
        int searchFieldWidth = Math.max(50, contentWidth - (smallButtonWidth * 2) - 8);
        this.searchField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer, contentLeft, 132,
                searchFieldWidth, 20, "Search username or server"));
        this.searchField.setMaxLength(255);
        this.searchField.setText(this.requestedSearch);

        int findX = contentLeft + searchFieldWidth + 4;
        this.addButton(new ButtonWidget(findX, 132, smallButtonWidth, 20,
                "FIND", button -> applySearch()));
        this.addButton(new ButtonWidget(findX + smallButtonWidth + 4, 132,
                smallButtonWidth, 20, "CLEAR", button -> clearSearch()));

        List<SessionManager.Session> filtered = getFilteredSessions();
        int pageCount = Math.max(1, (filtered.size() + this.pageSize - 1) / this.pageSize);
        this.pageIndex = Math.min(this.requestedPage, pageCount - 1);
        int startIndex = this.pageIndex * this.pageSize;
        int endIndex = Math.min(startIndex + this.pageSize, filtered.size());
        int rowY = 168;

        for (int index = startIndex; index < endIndex; index++) {
            final SessionManager.Session session = filtered.get(index);
            final int slotNumber = this.sessions.indexOf(session) + 1;
            String label = String.format(Locale.ROOT, "S%02d  %s  [%s]",
                    slotNumber, session.getProfile().getUsername(), session.getState().name());
            int maxCharacters = Math.max(16, contentWidth / 6);
            if (label.length() > maxCharacters) {
                label = label.substring(0, maxCharacters - 3) + "...";
            }
            this.addButton(new ButtonWidget(contentLeft, rowY, contentWidth, 18,
                    label, button -> selectSession(session)));
            rowY += 20;
        }

        int pageButtonWidth = Math.min(100, (contentWidth - 8) / 2);
        int pageButtonLeft = (this.width - (pageButtonWidth * 2 + 8)) / 2;
        ButtonWidget previous = new ButtonWidget(pageButtonLeft, this.height - 46,
                pageButtonWidth, 18, "PREVIOUS", button -> openPage(this.pageIndex - 1));
        ButtonWidget next = new ButtonWidget(pageButtonLeft + pageButtonWidth + 8,
                this.height - 46, pageButtonWidth, 18,
                "NEXT", button -> openPage(this.pageIndex + 1));
        previous.active = this.pageIndex > 0;
        next.active = this.pageIndex + 1 < pageCount;
        this.addButton(previous);
        this.addButton(next);

        int actionGap = 4;
        int actionWidth = (contentWidth - actionGap * 2) / 3;
        int actionY = this.height - 23;
        Optional<SessionManager.Session> selected = getSelectedSession();
        boolean selectionCanBeEdited = selected.isPresent()
                && selected.get().getState() != SessionState.CONNECTING
                && selected.get().getState() != SessionState.ONLINE;

        ButtonWidget edit = new ButtonWidget(contentLeft, actionY, actionWidth, 20,
                this.editing ? "CANCEL" : "EDIT",
                button -> {
                    if (this.editing) cancelEdit();
                    else editSelected();
                });
        edit.active = this.editing || selectionCanBeEdited;
        this.addButton(edit);

        String removeLabel = this.confirmRemoval ? "CONFIRM" : "DELETE";
        ButtonWidget remove = new ButtonWidget(contentLeft + actionWidth + actionGap,
                actionY, actionWidth, 20, removeLabel, button -> requestOrRemoveSelected());
        remove.active = selected.isPresent();
        this.addButton(remove);

        this.addButton(new ButtonWidget(contentLeft + (actionWidth + actionGap) * 2,
                actionY, actionWidth, 20, "CLOSE", button -> onClose()));
    }

    private void saveProfile() {
        if (this.editing) updateSelectedProfile();
        else addSession();
    }

    private void addSession() {
        SessionManager.Session created = null;
        try {
            SessionProfile profile = new SessionProfile(
                    this.usernameField.getText(), this.serverField.getText());
            if (this.profiles.contains(profile)) {
                this.status = "That username and server are already saved.";
                return;
            }
            if (this.profiles.size() >= SessionManager.MAX_SESSIONS
                    || this.sessionManager.remainingCapacity() <= 0) {
                this.status = "Session limit reached (20).";
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

            this.sessions.add(created);
            List<SessionManager.Session> filtered = getFilteredSessions();
            int selectedIndex = filtered.indexOf(created);
            int targetPage = selectedIndex >= 0
                    ? selectedIndex / this.pageSize
                    : Math.max(0, (filtered.size() - 1) / this.pageSize);
            openScreen("Session slot added. Status: OFFLINE (not connected).",
                    targetPage, created.getId(), this.requestedSearch, false, false);
        } catch (IllegalArgumentException exception) {
            this.status = exception.getMessage();
        } catch (IllegalStateException exception) {
            this.status = exception.getMessage();
        } catch (IOException exception) {
            this.status = "Could not save session: " + exception.getMessage();
        }
    }

    private void editSelected() {
        Optional<SessionManager.Session> selected = getSelectedSession();
        if (!selected.isPresent()) {
            this.status = "Select a session slot first.";
            return;
        }
        SessionState state = selected.get().getState();
        if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
            this.status = "A connecting or online session cannot be edited.";
            return;
        }
        openScreen("Editing " + selected.get().getProfile().getUsername() + ".",
                this.pageIndex, this.selectedSessionId, this.requestedSearch, true, false);
    }

    private void updateSelectedProfile() {
        Optional<SessionManager.Session> selected = getSelectedSession();
        if (!selected.isPresent()) {
            this.status = "The selected session no longer exists.";
            return;
        }

        SessionManager.Session oldSession = selected.get();
        SessionProfile oldProfile = oldSession.getProfile();
        SessionState oldState = oldSession.getState();
        if (oldState == SessionState.CONNECTING || oldState == SessionState.ONLINE) {
            this.status = "A connecting or online session cannot be edited.";
            return;
        }

        try {
            SessionProfile replacement = new SessionProfile(
                    this.usernameField.getText(), this.serverField.getText());
            if (replacement.equals(oldProfile)) {
                openScreen("No profile changes were needed.",
                        this.pageIndex, oldSession.getId(), this.requestedSearch, false, false);
                return;
            }

            for (SessionProfile profile : this.profiles) {
                if (!profile.equals(oldProfile) && profile.equals(replacement)) {
                    this.status = "That username and server are already saved.";
                    return;
                }
            }

            int profileIndex = this.profiles.indexOf(oldProfile);
            if (profileIndex < 0) {
                this.status = "The selected profile is no longer saved.";
                return;
            }

            if (!this.sessionManager.remove(oldSession.getId())) {
                this.status = "The selected session could not be updated.";
                return;
            }

            SessionManager.Session replacementSession = null;
            try {
                replacementSession = this.sessionManager.create(replacement);
                List<SessionProfile> updatedProfiles = new ArrayList<SessionProfile>(this.profiles);
                updatedProfiles.set(profileIndex, replacement);
                this.store.save(updatedProfiles);
                this.profiles.clear();
                this.profiles.addAll(updatedProfiles);
                this.sessions.remove(oldSession);
                this.sessions.add(replacementSession);
                List<SessionManager.Session> filtered = getFilteredSessions();
                int selectedIndex = filtered.indexOf(replacementSession);
                int targetPage = selectedIndex >= 0
                        ? selectedIndex / this.pageSize
                        : Math.min(this.pageIndex,
                                Math.max(0, (filtered.size() - 1) / this.pageSize));
                openScreen("Profile updated. The slot is now OFFLINE.",
                        targetPage, replacementSession.getId(), this.requestedSearch, false, false);
            } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
                if (replacementSession != null) {
                    this.sessionManager.remove(replacementSession.getId());
                }
                SessionManager.Session restored = restoreSession(oldProfile, oldState);
                openScreen("Could not update profile: " + exception.getMessage(),
                        this.pageIndex, restored.getId(), this.requestedSearch, false, false);
            }
        } catch (IllegalArgumentException exception) {
            this.status = exception.getMessage();
        }
    }

    private void requestOrRemoveSelected() {
        Optional<SessionManager.Session> selected = getSelectedSession();
        if (!selected.isPresent()) {
            this.status = "Select a session slot first.";
            return;
        }

        SessionState state = selected.get().getState();
        if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
            this.status = "Disconnect the session before removing it.";
            return;
        }

        if (!this.confirmRemoval) {
            openScreen("Press CONFIRM to delete the selected local profile.",
                    this.pageIndex, this.selectedSessionId, this.requestedSearch,
                    this.editing, true);
            return;
        }
        removeSelected();
    }

    private void removeSelected() {
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
            this.sessions.remove(session);
            int page = Math.min(this.pageIndex,
                    Math.max(0, (getFilteredSessions().size() - 1) / this.pageSize));
            openScreen("Profile removed.", page, -1L,
                    this.requestedSearch, false, false);
        } catch (IOException exception) {
            this.profiles.add(profileIndex, profile);
            SessionManager.Session restored = restoreSession(profile, previousState);
            openScreen("Could not save profile changes: " + exception.getMessage(),
                    this.pageIndex, restored.getId(), this.requestedSearch, false, false);
        }
    }

    private SessionManager.Session restoreSession(SessionProfile profile, SessionState previousState) {
        SessionManager.Session restored = this.sessionManager.create(profile);
        if (previousState == SessionState.DISCONNECTED || previousState == SessionState.FAILED) {
            this.sessionManager.updateState(restored.getId(), SessionState.CONNECTING);
            this.sessionManager.updateState(restored.getId(), previousState);
        }
        return restored;
    }

    private void selectSession(SessionManager.Session session) {
        String selectedStatus = session.getProfile().getUsername() + " | "
                + session.getState().name() + " | " + session.getProfile().getServerAddress();
        openScreen(selectedStatus, this.pageIndex, session.getId(),
                this.requestedSearch, false, false);
    }

    private void applySearch() {
        openScreen("Search applied.", 0, this.selectedSessionId,
                this.searchField.getText(), false, false);
    }

    private void clearSearch() {
        openScreen("Search cleared.", 0, this.selectedSessionId,
                "", false, false);
    }

    private void openPage(int page) {
        openScreen(this.status, page, this.selectedSessionId,
                this.requestedSearch, this.editing, false);
    }

    private void cancelEdit() {
        openScreen("Edit cancelled.", this.pageIndex, this.selectedSessionId,
                this.requestedSearch, false, false);
    }

    private void openScreen(String newStatus, int page, long selection,
                            String search, boolean edit, boolean confirm) {
        MinecraftClient.getInstance().openScreen(new SessionManagerScreen(
                this.parent, this.store, this.sessionManager,
                newStatus, page, selection, search, edit, confirm));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.usernameField != null) this.usernameField.tick();
        if (this.serverField != null) this.serverField.tick();
        if (this.searchField != null) this.searchField.tick();
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

        int offline = 0;
        int connecting = 0;
        int online = 0;
        int disconnected = 0;
        int failed = 0;
        for (SessionManager.Session session : this.sessions) {
            switch (session.getState()) {
                case OFFLINE: offline++; break;
                case CONNECTING: connecting++; break;
                case ONLINE: online++; break;
                case DISCONNECTED: disconnected++; break;
                case FAILED: failed++; break;
                default: break;
            }
        }
        String summary = "SLOTS " + this.sessions.size() + "/20"
                + "  |  OFFLINE " + offline
                + "  CONNECTING " + connecting
                + "  ONLINE " + online
                + "  DISCONNECTED " + disconnected
                + "  FAILED " + failed;
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                fitText(summary, this.width - 20), this.width / 2, 23, 0xA0A0A0);

        drawString(MinecraftClient.getInstance().textRenderer,
                "USERNAME", 12, 39, 0xA0A0A0);
        drawString(MinecraftClient.getInstance().textRenderer,
                "SERVER ADDRESS", 12, 73, 0xA0A0A0);
        List<SessionManager.Session> filtered = getFilteredSessions();
        int pageCount = Math.max(1, (filtered.size() + this.pageSize - 1) / this.pageSize);
        drawString(MinecraftClient.getInstance().textRenderer,
                "SLOTS " + filtered.size() + "/" + this.sessions.size()
                        + "  |  PAGE " + (this.pageIndex + 1) + "/" + pageCount,
                12, 155, 0x8CCBFF);

        Optional<SessionManager.Session> selected = getSelectedSession();
        if (this.height >= 300 && selected.isPresent()) {
            SessionProfile profile = selected.get().getProfile();
            drawString(MinecraftClient.getInstance().textRenderer,
                    fitText("SELECTED: " + profile.getUsername()
                            + " [" + selected.get().getState().name() + "]", this.width - 24),
                    12, this.height - 82, 0xFFFFFF);
            drawString(MinecraftClient.getInstance().textRenderer,
                    fitText("SERVER: " + profile.getServerAddress(), this.width - 24),
                    12, this.height - 70, 0xA0A0A0);
        }
        drawString(MinecraftClient.getInstance().textRenderer,
                fitText(this.status, this.width - 24), 12, this.height - 57, 0xFFFFFF);

        super.render(mouseX, mouseY, delta);
    }

    private String fitText(String value, int pixelWidth) {
        if (value == null) return "";
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }
}
