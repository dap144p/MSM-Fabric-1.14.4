package dev.cylox.msm.screen;

import dev.cylox.msm.mixin.KeyBindingAccessor;
import dev.cylox.msm.mixin.ServerInfoAccessor;
import dev.cylox.msm.profile.SessionProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.LiteralText;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SessionControlScreen extends Screen {
    private final Screen parent;
    private final SessionProfile profile;
    private final Set<KeyBinding> heldKeys = new HashSet<KeyBinding>();

    private TextFieldWidget chatField;
    private TextFieldWidget commandField;
    private ButtonWidget sendChatButton;
    private ButtonWidget runCommandButton;
    private ButtonWidget forwardButton;
    private ButtonWidget leftButton;
    private ButtonWidget backButton;
    private ButtonWidget rightButton;
    private ButtonWidget jumpButton;
    private ButtonWidget sneakButton;
    private ButtonWidget sprintButton;
    private String status = "Control is available only for the matching active Minecraft session.";

    public SessionControlScreen(Screen parent, SessionProfile profile) {
        super(new LiteralText("MSM Session Control"));
        if (profile == null) throw new IllegalArgumentException("Session profile is required");
        this.parent = parent;
        this.profile = profile;
    }

    public static boolean matchesActiveProfile(SessionProfile profile) {
        if (profile == null) return false;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null
                || client.getSession() == null
                || !profile.getUsername().equalsIgnoreCase(client.getSession().getUsername())) {
            return false;
        }

        if (client.isInSingleplayer()) {
            return "singleplayer".equals(normalizeAddress(profile.getServerAddress()));
        }

        ServerInfo serverInfo = client.getCurrentServerEntry();
        if (serverInfo == null) return false;
        String activeAddress = ((ServerInfoAccessor) (Object) serverInfo).msm$getAddress();
        return normalizeAddress(profile.getServerAddress()).equals(normalizeAddress(activeAddress));
    }

    private static String normalizeAddress(String address) {
        String normalized = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(":25565")) {
            normalized = normalized.substring(0, normalized.length() - 6);
        }
        return normalized;
    }

    @Override
    protected void init() {
        int contentWidth = Math.max(120, this.width - 24);
        int left = (this.width - contentWidth) / 2;
        int sendWidth = Math.min(72, Math.max(56, contentWidth / 4));
        int fieldWidth = Math.max(40, contentWidth - sendWidth - 4);

        this.chatField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer, left, 46,
                fieldWidth, 20, "Chat message"));
        this.chatField.setMaxLength(256);
        this.sendChatButton = this.addButton(new ButtonWidget(
                left + fieldWidth + 4, 46, sendWidth, 20,
                "SEND", button -> sendChat()));

        this.commandField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer, left, 73,
                fieldWidth, 20, "Command without slash"));
        this.commandField.setMaxLength(256);
        this.runCommandButton = this.addButton(new ButtonWidget(
                left + fieldWidth + 4, 73, sendWidth, 20,
                "RUN", button -> runCommand()));

        int directionWidth = Math.min(72, Math.max(36, (contentWidth - 8) / 3));
        int directionGap = 4;
        int directionLeft = (this.width - (directionWidth * 3 + directionGap * 2)) / 2;
        int centerX = this.width / 2;

        this.forwardButton = addMovementButton(
                "W", MinecraftClient.getInstance().options.keyForward,
                centerX - directionWidth / 2, 112, directionWidth);
        this.leftButton = addMovementButton(
                "A", MinecraftClient.getInstance().options.keyLeft,
                directionLeft, 136, directionWidth);
        this.backButton = addMovementButton(
                "S", MinecraftClient.getInstance().options.keyBack,
                centerX - directionWidth / 2, 136, directionWidth);
        this.rightButton = addMovementButton(
                "D", MinecraftClient.getInstance().options.keyRight,
                directionLeft + (directionWidth + directionGap) * 2, 136, directionWidth);

        int actionGap = 4;
        int actionWidth = (contentWidth - actionGap * 2) / 3;
        int actionLeft = left;
        this.jumpButton = addMovementButton(
                "JUMP", MinecraftClient.getInstance().options.keyJump,
                actionLeft, 160, actionWidth);
        this.sneakButton = addMovementButton(
                "SNEAK", MinecraftClient.getInstance().options.keySneak,
                actionLeft + actionWidth + actionGap, 160, actionWidth);
        this.sprintButton = addMovementButton(
                "SPRINT", MinecraftClient.getInstance().options.keySprint,
                actionLeft + (actionWidth + actionGap) * 2, 160, actionWidth);

        int footerWidth = Math.min(120, (contentWidth - 4) / 2);
        int footerLeft = (this.width - (footerWidth * 2 + 4)) / 2;
        this.addButton(new ButtonWidget(footerLeft, this.height - 26,
                footerWidth, 20, "RELEASE KEYS", button -> releaseAllKeys()));
        this.addButton(new ButtonWidget(footerLeft + footerWidth + 4, this.height - 26,
                footerWidth, 20, "BACK", button -> onClose()));

        updateControlAvailability();
    }

    private ButtonWidget addMovementButton(String label, KeyBinding binding,
                                           int x, int y, int width) {
        return this.addButton(new ButtonWidget(x, y, width, 20,
                getMovementLabel(label, binding), button -> toggleKey(button, label, binding)));
    }

    private String getMovementLabel(String label, KeyBinding binding) {
        return label + (this.heldKeys.contains(binding) ? " *" : "");
    }

    private void toggleKey(ButtonWidget button, String label, KeyBinding binding) {
        if (!matchesActiveProfile(this.profile)) {
            this.status = "Selected profile is not the active Minecraft session.";
            releaseAllKeys();
            updateControlAvailability();
            return;
        }

        if (this.heldKeys.remove(binding)) {
            setKeyPressed(binding, false);
        } else {
            this.heldKeys.add(binding);
            setKeyPressed(binding, true);
        }
        button.setMessage(getMovementLabel(label, binding));
    }

    private static void setKeyPressed(KeyBinding binding, boolean pressed) {
        InputUtil.Key key = ((KeyBindingAccessor) (Object) binding).msm$getKeyCode();
        KeyBinding.setKeyPressed(key, pressed);
    }

    private void releaseAllKeys() {
        for (KeyBinding binding : this.heldKeys) {
            setKeyPressed(binding, false);
        }
        this.heldKeys.clear();
        refreshMovementLabels();
        this.status = "All movement controls released.";
    }

    private void refreshMovementLabels() {
        updateMovementButton(this.forwardButton, "W", MinecraftClient.getInstance().options.keyForward);
        updateMovementButton(this.leftButton, "A", MinecraftClient.getInstance().options.keyLeft);
        updateMovementButton(this.backButton, "S", MinecraftClient.getInstance().options.keyBack);
        updateMovementButton(this.rightButton, "D", MinecraftClient.getInstance().options.keyRight);
        updateMovementButton(this.jumpButton, "JUMP", MinecraftClient.getInstance().options.keyJump);
        updateMovementButton(this.sneakButton, "SNEAK", MinecraftClient.getInstance().options.keySneak);
        updateMovementButton(this.sprintButton, "SPRINT", MinecraftClient.getInstance().options.keySprint);
    }

    private void updateMovementButton(ButtonWidget button, String label, KeyBinding binding) {
        if (button != null) button.setMessage(getMovementLabel(label, binding));
    }

    private void updateControlAvailability() {
        boolean active = matchesActiveProfile(this.profile);
        if (this.sendChatButton != null) this.sendChatButton.active = active;
        if (this.runCommandButton != null) this.runCommandButton.active = active;

        setMovementButtonsActive(active);
        if (!active && !this.heldKeys.isEmpty()) releaseAllKeys();
        if (!active) this.status = "NOT ACTIVE: connect with this username and server in the main client.";
    }

    private void setMovementButtonsActive(boolean active) {
        if (this.forwardButton != null) this.forwardButton.active = active;
        if (this.leftButton != null) this.leftButton.active = active;
        if (this.backButton != null) this.backButton.active = active;
        if (this.rightButton != null) this.rightButton.active = active;
        if (this.jumpButton != null) this.jumpButton.active = active;
        if (this.sneakButton != null) this.sneakButton.active = active;
        if (this.sprintButton != null) this.sprintButton.active = active;
    }

    private void sendChat() {
        String message = this.chatField.getText().trim();
        if (message.isEmpty() || !matchesActiveProfile(this.profile)) return;
        MinecraftClient.getInstance().player.sendChatMessage(message);
        this.chatField.setText("");
        this.status = "Chat sent through the active Minecraft connection.";
    }

    private void runCommand() {
        String command = this.commandField.getText().trim();
        if (command.isEmpty() || !matchesActiveProfile(this.profile)) return;
        if (!command.startsWith("/")) command = "/" + command;
        MinecraftClient.getInstance().player.sendChatMessage(command);
        this.commandField.setText("");
        this.status = "Command sent through the active Minecraft connection.";
    }

    @Override
    public void tick() {
        super.tick();
        if (this.chatField != null) this.chatField.tick();
        if (this.commandField != null) this.commandField.tick();

        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isWindowFocused()) {
            releaseAllKeys();
        }
        updateControlAvailability();
    }

    @Override
    public void onClose() {
        releaseAllKeys();
        MinecraftClient.getInstance().openScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        MinecraftClient client = MinecraftClient.getInstance();
        boolean active = matchesActiveProfile(this.profile);

        drawCenteredString(client.textRenderer,
                this.title.asFormattedString(), this.width / 2, 10, 0xFFFFFF);
        drawCenteredString(client.textRenderer,
                fitText(this.profile.getUsername() + " @ " + this.profile.getServerAddress(),
                        this.width - 20), this.width / 2, 23, 0xA0A0A0);
        drawCenteredString(client.textRenderer,
                active ? "ACTIVE MAIN SESSION" : "PROFILE NOT ACTIVE",
                this.width / 2, 34, active ? 0x55FF55 : 0xFFAA55);

        drawString(client.textRenderer, "CHAT", 12, 38, 0xA0A0A0);
        drawString(client.textRenderer, "COMMAND", 12, 65, 0xA0A0A0);
        drawString(client.textRenderer, "MOVEMENT (toggle on/off)", 12, 101, 0xA0A0A0);

        if (client.player != null && active) {
            String coordinates = String.format(Locale.ROOT, "XYZ: %.1f / %.1f / %.1f",
                    client.player.getX(), client.player.getY(), client.player.getZ());
            String playerState = String.format(Locale.ROOT,
                    "HP: %.1f  YAW: %.1f  PITCH: %.1f  GROUNDED: %s",
                    client.player.getHealth(), client.player.getYaw(),
                    client.player.getPitch(), client.player.isOnGround() ? "YES" : "NO");
            drawString(client.textRenderer, fitText(coordinates, this.width - 24),
                    12, 188, 0xFFFFFF);
            drawString(client.textRenderer, fitText(playerState, this.width - 24),
                    12, 201, 0xA0A0A0);
        }

        drawString(client.textRenderer,
                fitText(this.status, this.width - 24), 12, this.height - 43, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
    }

    private String fitText(String value, int pixelWidth) {
        if (value == null) return "";
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }
}
