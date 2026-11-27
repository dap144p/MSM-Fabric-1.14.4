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
    private MsmButtonWidget confirmButton;
    private MsmButtonWidget acknowledgmentButton;
    private boolean acknowledged;
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
        if (this.accountRemoval) {
            int panelWidth = Math.max(1, Math.min(380, this.width - 24));
            int left = (this.width - panelWidth) / 2;
            int top = this.height / 2 - 125;
            int buttonGap = 8;
            int buttonWidth = Math.max(1, (panelWidth - 24 - buttonGap) / 2);
            this.acknowledgmentButton = this.addAction(
                    left + 12, top + 177, panelWidth - 24, 20,
                    getAcknowledgementLabel(), button -> toggleAcknowledgement());
            this.confirmButton = this.addAction(
                    left + 12, top + 211, buttonWidth, 20,
                    "CONFIRM", button -> remove());
            this.confirmButton.setDanger(true);
            this.confirmButton.active = false;
            this.addAction(left + 12 + buttonWidth + buttonGap, top + 211,
                    buttonWidth, 20, "CANCEL", button -> close());
            return;
        }
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

    @Override
    public void tick() {
        super.tick();
        if (this.accountRemoval && this.confirmButton != null) {
            this.confirmButton.active = !this.removing && this.acknowledged;
        }
    }

    private String getAcknowledgementLabel() {
        return (this.acknowledged ? "[✓] " : "[ ] ")
                + "I UNDERSTAND - DELETE ALL ACCOUNT DATA";
    }

    private void toggleAcknowledgement() {
        this.acknowledged = !this.acknowledged;
        if (this.acknowledgmentButton != null) {
            this.acknowledgmentButton.setMessage(getAcknowledgementLabel());
        }
        if (this.confirmButton != null) {
            this.confirmButton.active = !this.removing && this.acknowledged;
        }
    }

    private void remove() {
        if (this.removing || (this.accountRemoval && !this.acknowledged)) return;
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

    private void drawTrashCan(DrawContext context, int center, int top) {
        int color = 0xFFFF6675;
        context.fill(center - 3, top, center + 3, top + 2, color);
        context.fill(center - 8, top + 3, center + 8, top + 5, color);
        context.fill(center - 6, top + 6, center - 4, top + 19, color);
        context.fill(center + 4, top + 6, center + 6, top + 19, color);
        context.fill(center - 4, top + 19, center + 4, top + 21, color);
        for (int offset = 0; offset < 10; offset++) {
            context.fill(center - 4 + offset, top + 7 + offset,
                    center - 2 + offset, top + 9 + offset, color);
            context.fill(center + 2 - offset, top + 7 + offset,
                    center + 4 - offset, top + 9 + offset, color);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelWidth;
        int left;
        int top;
        if (this.accountRemoval) {
            if (this.parent instanceof SessionManagerScreen) {
                ((SessionManagerScreen) this.parent).render(context, mouseX, mouseY, delta);
            } else {
                this.renderMsmBackground(context, mouseX, mouseY, delta);
            }
            context.fill(0, 0, this.width, this.height, 0x99070B12);
            panelWidth = Math.max(1, Math.min(380, this.width - 24));
            left = (this.width - panelWidth) / 2;
            top = this.height / 2 - 125;
            drawGlassPanel(context, left, top, left + panelWidth, top + 250);
            drawTrashCan(context, this.width / 2, top + 10);
            centered(context, "DELETE ACCOUNT", this.width / 2, top + 40, 0xFFFF8792);
            centered(context, this.username, this.width / 2, top + 66, 0xFFEAF0F8);
            centered(context, "Permanently delete this account and its",
                    this.width / 2, top + 90, 0xFFE1E7EF);
            centered(context, this.accountServerCount + " saved servers and their data.",
                    this.width / 2, top + 105, 0xFFE1E7EF);
            centered(context, "Includes AutoChat / AutoReconnect settings.",
                    this.width / 2, top + 132, 0xFF9EADBD);
        } else {
            this.renderMsmBackground(context, mouseX, mouseY, delta);
            panelWidth = Math.min(340, this.width - 24);
            left = (this.width - panelWidth) / 2;
            top = this.height / 2 - 66;
            drawGlassPanel(context, left, top, left + panelWidth, this.height / 2 + 66);
            centered(context, "REMOVE SERVER?", this.width / 2, top + 15, 0xFFFFD6D6);
            centered(context, this.username, this.width / 2, top + 37, 0xFF6FC4DE);
            centered(context, fit(this.serverAddress, panelWidth - 24),
                    this.width / 2, top + 52, 0xFFB3BFCD);
            centered(context, "Only this server's automation settings",
                    this.width / 2, top + 75, 0xFF9EADBD);
            centered(context, "will be deleted. Other servers stay saved.",
                    this.width / 2, top + 87, 0xFF9EADBD);
            centered(context, fit(this.status, panelWidth - 24),
                    this.width / 2, top + 103, 0xFF9EADBD);
        }
        super.render(context, mouseX, mouseY, delta);
    }
}
