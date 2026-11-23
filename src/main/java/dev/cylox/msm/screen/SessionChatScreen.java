package dev.cylox.msm.screen;

import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.session.SessionState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.LiteralText;

import java.util.ArrayList;
import java.util.List;

public final class SessionChatScreen extends MsmScreen {
    private final Screen parent;
    private final SecondarySessionController controller;
    private final long sessionId;
    private final String username;

    private TextFieldWidget messageField;
    private ButtonWidget sendButton;
    private ButtonWidget connectionButton;
    private volatile String status = "Connect the account to start chatting.";
    private int scrollOffset;

    public SessionChatScreen(Screen parent, SecondarySessionController controller,
                             long sessionId, String username) {
        super(new LiteralText("Chat - " + username));
        if (controller == null) throw new IllegalArgumentException("Session controller is required");
        this.parent = parent;
        this.controller = controller;
        this.sessionId = sessionId;
        this.username = username;
    }

    @Override
    protected void init() {
        int left = 12;
        int width = Math.max(1, this.width - 24);
        int gap = 4;
        int sendWidth = Math.min(64, Math.max(40, width / 5));
        sendWidth = Math.min(sendWidth, Math.max(1, width - gap - 1));
        int fieldWidth = Math.max(1, width - sendWidth - gap);
        int actionWidth = Math.max(1, (width - gap) / 2);

        this.messageField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer,
                left, this.height - 76, fieldWidth, 20, "Message"));
        this.messageField.setMaxLength(256);
        this.sendButton = this.addButton(new MsmButtonWidget(
                left + fieldWidth + gap, this.height - 76, sendWidth, 20,
                "SEND", button -> sendMessage()));

        this.connectionButton = this.addButton(new ButtonWidget(
                left, this.height - 24, actionWidth, 20,
                "JOIN / CONNECT", button -> toggleConnection()));
        this.addButton(new ButtonWidget(
                left + actionWidth + gap, this.height - 24,
                actionWidth, 20, "BACK", button -> onClose()));

        updateControls();
    }

    private void sendMessage() {
        if (this.messageField == null) return;
        String message = this.messageField.getText();
        if (message == null || message.trim().isEmpty()) return;

        try {
            this.controller.sendChat(this.sessionId, message);
            this.messageField.setText("");
            this.status = "Message sent.";
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        }
        updateControls();
    }

    private void toggleConnection() {
        try {
            SessionState state = this.controller.getState(this.sessionId);
            if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
                this.controller.disconnect(this.sessionId);
                this.status = "Disconnect requested for " + this.username + ".";
            } else {
                this.controller.connect(this.sessionId);
                this.status = "Joining as " + this.username + "...";
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.status = exception.getMessage();
        }
        updateControls();
    }

    private void updateControls() {
        SessionState state;
        try {
            state = this.controller.getState(this.sessionId);
        } catch (IllegalArgumentException exception) {
            if (this.sendButton != null) this.sendButton.active = false;
            if (this.connectionButton != null) this.connectionButton.active = false;
            this.status = "This account is no longer available.";
            return;
        }

        if (this.sendButton != null) {
            this.sendButton.active = state == SessionState.ONLINE;
        }
        if (this.connectionButton != null) {
            this.connectionButton.setMessage(
                    state == SessionState.CONNECTING ? "CANCEL"
                            : state == SessionState.ONLINE ? "DISCONNECT" : "JOIN / CONNECT");
            this.connectionButton.active = state != null;
        }

        if (state == SessionState.CONNECTING) {
            this.status = "CONNECTING as " + this.username + "...";
        } else if (state == SessionState.ONLINE) {
            this.status = "ONLINE as " + this.username + ". Chat is ready.";
        } else {
            List<String> messages = this.controller.getMessages(this.sessionId);
            String latestConnectionMessage = null;
            for (int index = messages.size() - 1; index >= 0; index--) {
                String message = messages.get(index);
                if (message.startsWith("[MSM] Disconnected during ")
                        || message.startsWith("[MSM] Login rejected:")
                        || message.startsWith("[MSM] Server disconnected:")) {
                    latestConnectionMessage = message;
                    break;
                }
            }
            this.status = latestConnectionMessage == null
                    ? "OFFLINE. Press JOIN / CONNECT to start this account."
                    : latestConnectionMessage;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.messageField != null) this.messageField.tick();
        updateControls();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 && this.controller.isOnline(this.sessionId)) {
            sendMessage();
            return true;
        }
        if (keyCode == 265) {
            this.scrollOffset = Math.min(this.scrollOffset + 3, getMaxScrollOffset());
            return true;
        }
        if (keyCode == 264) {
            this.scrollOffset = Math.max(0, this.scrollOffset - 3);
            return true;
        }
        if (keyCode == 269) {
            this.scrollOffset = 0;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        MinecraftClient.getInstance().openScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int getMaxScrollOffset() {
        List<String> messages = getWrappedMessages();
        int maxLines = Math.max(1, (this.height - 130) / 11);
        return Math.max(0, messages.size() - maxLines);
    }

    private List<String> getWrappedMessages() {
        List<String> wrapped = new ArrayList<String>();
        List<String> messages = this.controller.getMessages(this.sessionId);
        int maxCharacters = Math.max(8, (this.width - 24) / 6);

        for (String message : messages) {
            String[] paragraphs = (message == null ? "" : message).split("\\n", -1);
            for (String paragraph : paragraphs) {
                if (paragraph.isEmpty()) {
                    wrapped.add("");
                    continue;
                }
                for (int offset = 0; offset < paragraph.length(); offset += maxCharacters) {
                    wrapped.add(paragraph.substring(offset,
                            Math.min(paragraph.length(), offset + maxCharacters)));
                }
            }
        }

        this.scrollOffset = Math.min(this.scrollOffset,
                Math.max(0, wrapped.size() - Math.max(1, (this.height - 130) / 11)));
        return wrapped;
    }

    private String fitText(String value, int pixelWidth) {
        if (value == null) return "";
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderMsmBackground();
        drawGlassPanel(8, 6, this.width - 8, this.height - 8);
        drawGlassPanel(16, 28, this.width - 16, this.height - 101);
        drawGlassPanel(8, this.height - 100, this.width - 8, this.height - 52);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                this.title.asFormattedString(), this.width / 2, 10, 0xFFFFFF);

        List<String> messages = getWrappedMessages();
        int lineHeight = 11;
        int maxLines = Math.max(1, (this.height - 130) / lineHeight);
        int end = Math.max(0, messages.size() - this.scrollOffset);
        int start = Math.max(0, end - maxLines);
        int y = 34;

        for (int index = start; index < end; index++) {
            drawString(MinecraftClient.getInstance().textRenderer,
                    messages.get(index), 12, y, 0xE0E0E0);
            y += lineHeight;
        }

        drawString(MinecraftClient.getInstance().textRenderer,
                fitText(this.status, this.width - 24), 12, this.height - 93, 0xA0A0A0);
        super.render(mouseX, mouseY, delta);
    }
}
