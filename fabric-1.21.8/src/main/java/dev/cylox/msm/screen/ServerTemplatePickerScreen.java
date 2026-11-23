package dev.cylox.msm.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ServerTemplatePickerScreen extends MsmScreen {
    private static final int PAGE_SIZE = 7;
    private final Consumer<String> selection;
    private final List<ServerInfo> servers = new ArrayList<>();
    private int pageIndex;

    public ServerTemplatePickerScreen(Screen parent, Consumer<String> selection) {
        super(Text.literal("Select Saved Server"), parent);
        this.selection = selection;
        try {
            ServerList list = new ServerList(MinecraftClient.getInstance());
            list.loadFile();
            for (int i = 0; i < list.size(); i++) {
                this.servers.add(list.get(i));
            }
        } catch (RuntimeException exception) {
            // The picker remains usable and reports an empty saved-server list.
        }
    }

    @Override
    protected void init() {
        int left = 12;
        int width = Math.max(1, this.width - 24);
        int pageCount = Math.max(1, (this.servers.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        this.pageIndex = Math.max(0, Math.min(this.pageIndex, pageCount - 1));
        int start = this.pageIndex * PAGE_SIZE;
        int end = Math.min(this.servers.size(), start + PAGE_SIZE);

        int y = 42;
        for (int i = start; i < end; i++) {
            ServerInfo server = this.servers.get(i);
            this.addAction(left, y, width, 20,
                    fit(server.name + " | " + server.address, width - 12),
                    button -> choose(server.address));
            y += 24;
        }

        if (pageCount > 1) {
            int half = Math.max(1, (width - 8) / 2);
            this.addAction(left, this.height - 48, half, 20,
                    "PREVIOUS", button -> page(-1)).active = this.pageIndex > 0;
            this.addAction(left + half + 8, this.height - 48,
                    width - half - 8, 20,
                    "NEXT", button -> page(1)).active = this.pageIndex + 1 < pageCount;
        }
        this.addAction(left, this.height - 24, width, 20, "BACK", button -> close());
    }

    private void choose(String address) {
        this.selection.accept(address);
        close();
    }

    private void page(int delta) {
        this.pageIndex += delta;
        this.init(this.client, this.width, this.height);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        panel(context, 8, 8, this.width - 8, this.height - 8);
        centered(context, "SAVED MULTIPLAYER SERVERS", this.width / 2, 18, 0xFFF1F5FA);
        if (this.servers.isEmpty()) {
            centered(context, "No saved servers found.", this.width / 2, 58, 0xFFAEBBCC);
            centered(context, "Add servers in Multiplayer first.", this.width / 2, 73, 0xFFAEBBCC);
        } else {
            centered(context, "PAGE " + (this.pageIndex + 1) + "/"
                    + Math.max(1, (this.servers.size() + PAGE_SIZE - 1) / PAGE_SIZE),
                    this.width / 2, this.height - 65, 0xFF9EADBD);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
