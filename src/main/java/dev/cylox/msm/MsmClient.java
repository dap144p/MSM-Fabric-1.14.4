package dev.cylox.msm;

import dev.cylox.msm.automation.SessionAutomationScheduler;
import dev.cylox.msm.automation.SessionAutomationStore;
import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.profile.SessionProfileStore;
import dev.cylox.msm.screen.SessionManagerScreen;
import dev.cylox.msm.server.ServerTemplateRepository;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.update.MsmUpdateManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

public final class MsmClient implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("MSM");
    private static SessionProfileStore profileStore;
    private static SessionManager sessionManager;
    private static SecondarySessionController secondarySessions;
    private static SessionAutomationScheduler automationScheduler;
    private static ServerTemplateRepository serverTemplateRepository;
    private static MsmUpdateManager updateManager;

    @Override
    public void onInitializeClient() {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        updateManager = new MsmUpdateManager(configDirectory, "1.14.4",
                FabricLoader.getInstance().getModContainer("msm_fabric")
                        .map(container -> container.getMetadata().getVersion().getFriendlyString())
                        .orElse("unknown"));
        profileStore = new SessionProfileStore(configDirectory.resolve("msm-fabric.properties"));
        sessionManager = new SessionManager();
        secondarySessions = new SecondarySessionController(sessionManager);
        automationScheduler = new SessionAutomationScheduler(
                sessionManager,
                secondarySessions,
                new SessionAutomationStore(configDirectory.resolve("msm-automation.properties")));
        serverTemplateRepository = new ServerTemplateRepository(
                FabricLoader.getInstance().getGameDir().resolve("servers.dat"),
                configDirectory.resolve("msm-last-server.properties"));
    }

    public static void openManager(Screen parent) {
        MinecraftClient.getInstance().openScreen(
                new SessionManagerScreen(parent, profileStore, sessionManager,
                        secondarySessions, automationScheduler, serverTemplateRepository));
    }

    public static MsmUpdateManager getUpdateManager() {
        return updateManager;
    }

    public static void rememberLastMultiplayerServer(String name, String address) {
        ServerTemplateRepository repository = serverTemplateRepository;
        if (repository == null) return;
        try {
            repository.rememberLastSelected(name, address);
        } catch (IOException exception) {
            LOGGER.warn("Could not save the last selected Multiplayer server: {}",
                    exception.getMessage());
        }
    }
}
