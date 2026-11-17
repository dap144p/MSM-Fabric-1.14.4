package dev.cylox.msm.screen;

import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.profile.SessionProfileStore;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.LiteralText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class SessionManagerScreen extends Screen {
    private final Screen parent;
    private final SessionProfileStore store;
    private final List<SessionProfile> profiles = new ArrayList<SessionProfile>();
    private TextFieldWidget usernameField;
    private TextFieldWidget serverField;
    private String status = "Profiles are saved locally. No secondary connection is active.";
    private int selectedIndex = -1;

    public SessionManagerScreen(Screen parent, SessionProfileStore store) {
        this(parent, store, "Profiles are saved locally. No secondary connection is active.");
    }

    private SessionManagerScreen(Screen parent, SessionProfileStore store, String status) {
        super(new LiteralText("Cylox Multi Session Manager"));
        this.parent = parent;
        this.store = store;
        this.status = status;
        try {
            this.profiles.addAll(store.load());
        } catch (IOException exception) {
            this.status = "Could not load profiles: " + exception.getMessage();
        }
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(220, this.width - 24);
        int left = (this.width - fieldWidth) / 2;
        int buttonWidth = Math.min(220, this.width - 24);
        int buttonLeft = (this.width - buttonWidth) / 2;

        this.usernameField = this.addButton(new TextFieldWidget(
                this.textRenderer, left, 47, fieldWidth, 20, new LiteralText("Username")));
        this.usernameField.setMaxLength(16);
        this.serverField = this.addButton(new TextFieldWidget(
                this.textRenderer, left, 87, fieldWidth, 20, new LiteralText("Server address")));
        this.serverField.setMaxLength(255);

        this.addButton(new ButtonWidget(buttonLeft, 116, buttonWidth, 20,
                new LiteralText("SAVE PROFILE"), button -> saveProfile()));

        int visibleProfiles = Math.min(this.profiles.size(), Math.max(0, (this.height - 207) / 21));
        int rowY = 158;
        for (int index = 0; index < visibleProfiles; index++) {
            final int profileIndex = index;
            SessionProfile profile = this.profiles.get(index);
            String label = profile.getUsername() + "  |  " + profile.getServerAddress();
            if (label.length() > 34) label = label.substring(0, 31) + "...";
            this.addButton(new ButtonWidget(buttonLeft, rowY, buttonWidth, 18,
                    new LiteralText(label), button -> {
                this.selectedIndex = profileIndex;
                this.status = profile.getUsername() + " is saved locally and OFFLINE.";
            }));
            rowY += 21;
        }

        this.addButton(new ButtonWidget(buttonLeft, this.height - 25, buttonWidth / 2 - 2, 20,
                new LiteralText("REMOVE"), button -> removeSelected()));
        this.addButton(new ButtonWidget(buttonLeft + buttonWidth / 2 + 2, this.height - 25,
                buttonWidth / 2 - 2, 20, new LiteralText("CLOSE"), button -> onClose()));
    }

    private void saveProfile() {
        try {
            SessionProfile profile = new SessionProfile(this.usernameField.getText(), this.serverField.getText());
            for (SessionProfile existing : this.profiles) {
                if (existing.equals(profile)) {
                    this.status = "That username and server are already saved.";
                    return;
                }
            }
            if (this.profiles.size() >= 100) {
                this.status = "Profile limit reached (100).";
                return;
            }
            this.profiles.add(profile);
            this.store.save(this.profiles);
            this.client.openScreen(new SessionManagerScreen(this.parent, this.store,
                    "Profile saved. Status: OFFLINE (not connected)."));
        } catch (IllegalArgumentException exception) {
            this.status = exception.getMessage();
        } catch (IOException exception) {
            this.status = "Could not save profile: " + exception.getMessage();
        }
    }

    private void removeSelected() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.profiles.size()) {
            this.status = "Select a saved profile first.";
            return;
        }
        SessionProfile removed = this.profiles.remove(this.selectedIndex);
        int removedIndex = this.selectedIndex;
        this.selectedIndex = -1;
        try {
            this.store.save(this.profiles);
            this.client.openScreen(new SessionManagerScreen(this.parent, this.store, "Profile removed."));
        } catch (IOException exception) {
            this.profiles.add(removedIndex, removed);
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
        this.client.openScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        drawCenteredString(this.textRenderer, this.title.asFormattedString(), this.width / 2, 12, 0xFFFFFF);
        drawString(this.textRenderer, "SECONDARY SESSION PROFILES", 12, 32, 0x8CCBFF);
        drawString(this.textRenderer, "USERNAME", 12, 37, 0xA0A0A0);
        drawString(this.textRenderer, "SERVER ADDRESS", 12, 77, 0xA0A0A0);
        drawString(this.textRenderer, "SAVED PROFILES (OFFLINE)", 12, 143, 0x8CCBFF);
        drawString(this.textRenderer, this.status, 12, this.height - 45, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
    }
}
