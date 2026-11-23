package dev.cylox.msm.screen;

import dev.cylox.msm.network.SecondarySessionController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.LiteralText;

import java.util.List;

public final class SessionChatScreen extends Screen {
    private final Screen parent;
    private final SecondarySessionController controller;
    private final long sessionId;
    private final String username;

    private TextFieldWidget messageField;
    private ButtonWidget sendButton;
    private String status = "Messages are logged in latest.log. Use UP/DOWN to scroll; END returns to newest.";
    private int scrollOffset;

    public SessionChatScreen(Screen parent, SecondarySessionController controller,
                             long sessionId, String username) {
        super(new LiteralText("MSM Chat - " + username));
        if (controller == null) throw new IllegalArgumentException("Session controller is required");
        this.parent = parent;
        this.controller = controller;
        this.sessionId = sessionId;
        this.username = username;
    }

    @Override
    protected void init() {
        int left = 12;
        int width = Math.max(80, this.width - 24);
        int sendWidth = Math.min(54, Math.max(42, width / 5));
        int fieldWidth = Math.max(30, width - sendWidth - 4);

        this.messageField = this.addButton(new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer,
                left, this.height - 48, fieldWidth, 20, "Message"));
        this.messageField.setMaxLength(256);
        this.sendButton = this.addButton(new ButtonWidget(
                left + fieldWidth + 4, this.height - 48, sendWidth, 20,
                "SEND", button -> sendMessage()));
        this.addButton(new ButtonWidget(
                left, this.height - 23, width, 20, "BACK", button -> onClose()));
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

    private void updateControls() {
        if (this.sendButton != null) {
            this.sendButton.active = this.controller.isOnline(this.sessionId);
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
        int maxLines = Math.max(1, (this.height - 105) / 11);
        return Math.max(0, messages.size() - maxLines);
    }

    private List<String> getWrappedMessages() {
        List<String> wrapped = new java.util.ArrayList<String>();
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
                Math.max(0, wrapped.size() - Math.max(1, (this.height - 105) / 11)));
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
        this.renderBackground();
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                this.title.asFormattedString(), this.width / 2, 10, 0xFFFFFF);

        List<String> messages = getWrappedMessages();
        int lineHeight = 11;
        int maxLines = Math.max(1, (this.height - 105) / lineHeight);
        int end = Math.max(0, messages.size() - this.scrollOffset);
        int start = Math.max(0, end - maxLines);
        int y = 34;

        for (int index = start; index < end; index++) {
            drawString(MinecraftClient.getInstance().textRenderer,
                    messages.get(index), 12, y, 0xE0E0E0);
            y += lineHeight;
        }

        String scrollStatus = this.scrollOffset == 0
                ? this.status
                : "Viewing older messages (" + this.scrollOffset + " lines back). Press END for newest.";
        drawString(MinecraftClient.getInstance().textRenderer,
                fitText(scrollStatus, this.width - 24), 12, this.height - 62, 0xA0A0A0);
        super.render(mouseX, mouseY, delta);
    }
}
