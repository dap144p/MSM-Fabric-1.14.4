package dev.cylox.msm.screen;

import dev.cylox.msm.MsmClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class ConfirmRemoveAccountScreen extends MsmScreen {
    private final long sessionId;
    private final String username;
    private final String serverAddress;
    private final boolean accountRemoval;
    private final int accountServerCount;
    private final Runnable accountRemovalAction;
    private boolean removing;
    private String status = "Only this server and its automation settings will be removed.";

    public ConfirmRemoveAccountScreen(Screen parent, long sessionId,
                                      String username, String serverAddress) {
        super(Text.literal("Remove Server"), parent);
        this.sessionId = sessionId;
        this.username = username == null ? "" : username;
        this.serverAddress = serverAddress == null ? "" : serverAddress;
        this.accountRemoval = false;
        this.accountServerCount = 1;
        this.accountRemovalAction = null;
    }

    public ConfirmRemoveAccountScreen(Screen parent, String username,
                                      int serverCount, Runnable accountRemovalAction) {
        super(Text.literal("Remove Account"), parent);
        if (accountRemovalAction == null) {
            throw new IllegalArgumentException("Account removal action is required");
        }
        this.sessionId = -1L;
        this.username = username == null ? "" : username;
        this.serverAddress = serverCount + " saved servers";
        this.accountRemoval = true;
        this.accountServerCount = Math.max(0, serverCount);
        this.accountRemovalAction = accountRemovalAction;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(340, this.width - 24);
        int left = (this.width - panelWidth) / 2;
        int buttonWidth = Math.max(1, (panelWidth - 34) / 2);
        MsmButtonWidget removeButton = this.addAction(left + 10, this.height / 2 + 34, buttonWidth, 20,
                this.removing ? "REMOVING..." : this.accountRemoval ? "DELETE ACCOUNT" : "REMOVE",
                button -> remove());
        removeButton.setDanger(true);
        removeButton.active = !this.removing;
        this.addAction(left + 24 + buttonWidth, this.height / 2 + 34,
                buttonWidth, 20, "CANCEL", button -> close());
    }

    private void remove() {
        if (this.removing) return;
        this.removing = true;
        if (this.accountRemoval) {
            this.status = "Deleting the account and all " + this.accountServerCount + " servers...";
            this.accountRemovalAction.run();
            return;
        }
        this.status = "Removing this server and its saved AutoChat / AutoReconnect settings...";
        this.init(this.client, this.width, this.height);
        MsmClient.removeProfile(this.sessionId, failure -> {
            if (failure != null) {
                this.removing = false;
                this.status = "Removal failed: " + failure.getMessage();
                this.client.setScreen(this.parent);
                if (this.parent instanceof SessionManagerScreen) {
                    ((SessionManagerScreen) this.parent).setStatus(this.status);
                }
                return;
            }
            this.client.setScreen(this.parent);
            if (this.parent instanceof SessionManagerScreen) {
                ((SessionManagerScreen) this.parent).setStatus("Server removed. Other servers on this account are unchanged.");
            }
        });
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderMsmBackground(context, mouseX, mouseY, delta);
        int panelWidth = Math.min(340, this.width - 24);
        int left = (this.width - panelWidth) / 2;
        int top = this.height / 2 - 66;
        drawGlassPanel(context, left, top, left + panelWidth, this.height / 2 + 66);
        centered(context, this.accountRemoval ? "DELETE ACCOUNT?" : "REMOVE SERVER?",
                this.width / 2, top + 15, 0xFFFFD6D6);
        centered(context, this.username, this.width / 2, top + 37, 0xFF6FC4DE);
        centered(context, fit(this.serverAddress, panelWidth - 24),
                this.width / 2, top + 52, 0xFFB3BFCD);
        centered(context, this.accountRemoval ? "All saved servers will be disconnected"
                        : "Only this server's automation settings",
                this.width / 2, top + 75, 0xFF9EADBD);
        centered(context, this.accountRemoval ? "and removed with their saved settings."
                        : "will be deleted. Other servers stay saved.",
                this.width / 2, top + 87, 0xFF9EADBD);
        centered(context, fit(this.status, panelWidth - 24),
                this.width / 2, top + 103, 0xFF9EADBD);
        super.render(context, mouseX, mouseY, delta);
    }
}
