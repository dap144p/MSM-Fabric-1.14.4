package dev.cylox.msm;

import dev.cylox.msm.net.SessionNetworkManager;
import dev.cylox.msm.profile.SessionProfileStore;
import dev.cylox.msm.screen.SessionManagerScreen;
import dev.cylox.msm.session.SessionManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

public final class MsmClient implements ClientModInitializer {
    private static SessionProfileStore profileStore;
    private static SessionManager sessionManager;
    private static SessionNetworkManager networkManager;

    @Override
    public void onInitializeClient() {
        profileStore = new SessionProfileStore(
                FabricLoader.getInstance().getConfigDir().resolve("msm-fabric.properties"));
        sessionManager = new SessionManager();
        networkManager = new SessionNetworkManager(sessionManager);
    }

    public static void openManager(Screen parent) {
        MinecraftClient.getInstance().openScreen(
                new SessionManagerScreen(parent, profileStore, sessionManager));
    }

    public static SessionNetworkManager getNetworkManager() {
        if (networkManager == null) throw new IllegalStateException("MSM has not been initialized.");
        return networkManager;
    }
}