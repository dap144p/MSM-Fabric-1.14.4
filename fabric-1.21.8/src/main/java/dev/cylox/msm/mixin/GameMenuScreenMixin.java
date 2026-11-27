package dev.cylox.msm.mixin;

import dev.cylox.msm.MsmClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void msm$addManagerButton(CallbackInfo callbackInfo) {
        ButtonWidget managerButton = ButtonWidget.builder(
                Text.literal("MSM"),
                button -> MsmClient.openManager((Screen) (Object) this))
                .dimensions(8, 8, 72, 20)
                .build();
        ((ScreenAccessor) this).msm$addDrawableChild(managerButton);
    }
}
