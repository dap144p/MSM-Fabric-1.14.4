package dev.cylox.msm;

import dev.cylox.msm.profile.SessionProfileStore;
import dev.cylox.msm.screen.SessionManagerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

public final class MsmClient implements ClientModInitializer {
    private static SessionProfileStore profileStore;

    @Override
    public void onInitializeClient() {
        profileStore = new SessionProfileStore(
                FabricLoader.getInstance().getConfigDir().resolve("msm-fabric.properties"));
    }

    public static void openManager(Screen parent) {
        MinecraftClient.getInstance().openScreen(new SessionManagerScreen(parent, profileStore));
    }
}
