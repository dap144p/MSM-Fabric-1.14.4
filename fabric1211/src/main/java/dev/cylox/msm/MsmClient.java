package dev.cylox.msm;

import dev.cylox.msm.automation.SessionAutomationScheduler;
import dev.cylox.msm.automation.SessionAutomationStore;
import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.profile.SessionProfileStore;
import dev.cylox.msm.screen.SessionManagerScreen;
import dev.cylox.msm.server.ServerTemplateRepository;
import dev.cylox.msm.session.SessionManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public final class MsmClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("MSM");
    private static SessionProfileStore profileStore;
    private static SessionManager sessionManager;
    private static SecondarySessionController secondarySessions;
    private static SessionAutomationScheduler automationScheduler;
    private static ServerTemplateRepository serverTemplateRepository;

    @Override
    public void onInitializeClient() {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
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

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(
                        Text.literal("MSM"),
                        button -> openManager(screen))
                        .dimensions(8, 8, 88, 20)
                        .build());
            }
        });
    }

    public static void openManager(Screen parent) {
        MinecraftClient.getInstance().setScreen(
                new SessionManagerScreen(parent, profileStore, sessionManager,
                        secondarySessions, automationScheduler, serverTemplateRepository));
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
