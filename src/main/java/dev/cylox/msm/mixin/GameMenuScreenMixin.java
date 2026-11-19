package dev.cylox.msm.mixin;

import dev.cylox.msm.MsmClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void msm$addManagerButton(CallbackInfo callbackInfo) {
        ((ScreenAccessor) this).msm$invokeAddButton(new ButtonWidget(
                8, 8, 88, 20, "MSM",
                button -> MsmClient.openManager((Screen) (Object) this)));
    }
}
