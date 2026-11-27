package dev.cylox.msm.screen;

import dev.cylox.msm.server.ServerTemplateRepository;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.LiteralText;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public final class ServerTemplatePickerScreen extends MsmScreen {
    private static final int MAX_PAGE_SIZE = 7;

    private final Screen parent;
    private final ServerTemplateRepository repository;
    private final Consumer<ServerTemplateRepository.ServerTemplate> selection;
    private List<ServerTemplateRepository.ServerTemplate> servers = Collections.emptyList();

    private String status = "Choose a saved Multiplayer server.";
    private int pageIndex;
    private int pageSize = 1;

    public ServerTemplatePickerScreen(
            Screen parent,
            ServerTemplateRepository repository,
            Consumer<ServerTemplateRepository.ServerTemplate> selection) {
        this(parent, repository, selection, 0);
    }

    private ServerTemplatePickerScreen(
            Screen parent,
            ServerTemplateRepository repository,
            Consumer<ServerTemplateRepository.ServerTemplate> selection,
            int requestedPage) {
        super(new LiteralText("Select Server"));
        if (repository == null || selection == null) {
            throw new IllegalArgumentException("Server repository and selection callback are required");
        }
        this.parent = parent;
        this.repository = repository;
        this.selection = selection;
        this.pageIndex = Math.max(0, requestedPage);
        try {
            this.servers = this.repository.loadServers();
        } catch (IOException exception) {
            this.status = "Could not read servers.dat: " + safeMessage(exception);
        }
    }

    @Override
    protected void init() {
        int contentWidth = Math.max(1, this.width - 24);
        int left = (this.width - contentWidth) / 2;
        this.pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, (this.height - 110) / 24));

        int pageCount = Math.max(1, (this.servers.size() + this.pageSize - 1) / this.pageSize);
        this.pageIndex = Math.max(0, Math.min(this.pageIndex, pageCount - 1));
        int start = this.pageIndex * this.pageSize;
        int end = Math.min(start + this.pageSize, this.servers.size());

        int rowY = 48;
        for (int index = start; index < end; index++) {
            final ServerTemplateRepository.ServerTemplate server = this.servers.get(index);
            String label = fitText(server.getName() + "  |  " + server.getAddress(), contentWidth - 12);
            this.addButton(new MsmButtonWidget(
                    left, rowY, contentWidth, 20, label,
                    button -> choose(server)));
            rowY += 23;
        }

        if (pageCount > 1) {
            int pageWidth = Math.min(100, Math.max(1, (contentWidth - 8) / 2));
            int pageLeft = (this.width - (pageWidth * 2 + 8)) / 2;
            ButtonWidget previous = new MsmButtonWidget(
                    pageLeft, this.height - 50, pageWidth, 18,
                    "PREVIOUS", button -> changePage(-1));
            ButtonWidget next = new MsmButtonWidget(
                    pageLeft + pageWidth + 8, this.height - 50, pageWidth, 18,
                    "NEXT", button -> changePage(1));
            previous.active = this.pageIndex > 0;
            next.active = this.pageIndex + 1 < pageCount;
            this.addButton(previous);
            this.addButton(next);
        }

        int backWidth = Math.min(96, contentWidth);
        this.addButton(new MsmButtonWidget(
                this.width - 12 - backWidth, this.height - 28, backWidth, 20,
                "BACK", button -> onClose()));
    }

    private void choose(ServerTemplateRepository.ServerTemplate server) {
        this.selection.accept(server);
        MinecraftClient.getInstance().openScreen(this.parent);
    }

    private void changePage(int delta) {
        int nextPage = Math.max(0, this.pageIndex + delta);
        MinecraftClient.getInstance().openScreen(
                new ServerTemplatePickerScreen(
                        this.parent, this.repository, this.selection, nextPage));
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
        drawGlassPanel(16, 27, this.width - 16, this.height - 58);
        drawCenteredString(MinecraftClient.getInstance().textRenderer,
                this.title.asFormattedString(), this.width / 2, 14, 0xFFFFFF);
        if (this.servers.isEmpty()) {
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    "No saved servers found in Multiplayer.", this.width / 2, 64, 0xA0A0A0);
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    "Add a server in Minecraft Multiplayer first.", this.width / 2, 78, 0xA0A0A0);
        } else {
            drawCenteredString(MinecraftClient.getInstance().textRenderer,
                    "SAVED MULTIPLAYER SERVERS", this.width / 2, 31, 0xA0A0A0);
        }
        String footer = this.status == null ? "" : this.status;
        if (!this.servers.isEmpty()) {
            String page = "PAGE " + (this.pageIndex + 1) + "/"
                    + Math.max(1, (this.servers.size() + this.pageSize - 1) / this.pageSize);
            footer = page + (footer.isEmpty() ? "" : "  |  " + footer);
        }
        renderMsmFooter(footer, 108);
        super.render(mouseX, mouseY, delta);
    }

    private String fitText(String value, int pixelWidth) {
        if (value == null) return "";
        int maxCharacters = Math.max(8, pixelWidth / 6);
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 3) + "...";
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
