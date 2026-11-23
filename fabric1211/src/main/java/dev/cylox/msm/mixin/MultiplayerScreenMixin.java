package dev.cylox.msm.mixin;

import dev.cylox.msm.MsmClient;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.network.ServerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin {
    @Inject(method = "select", at = @At("TAIL"))
    private void msm$rememberLastSelectedServer(
            MultiplayerServerListWidget.Entry entry,
            CallbackInfo callbackInfo) {
        ServerInfo selected = ((MultiplayerScreenAccessor) this).msm$getSelectedEntry();
        if (selected != null) {
            MsmClient.rememberLastMultiplayerServer(selected.name, selected.address);
        }
    }
}
