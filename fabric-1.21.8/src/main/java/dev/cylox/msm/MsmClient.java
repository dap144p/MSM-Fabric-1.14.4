package dev.cylox.msm;

import dev.cylox.msm.automation.SessionAutomationScheduler;
import dev.cylox.msm.automation.SessionAutomationStore;
import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.profile.AccountIdentity;
import dev.cylox.msm.profile.AccountIdentityStore;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.profile.SessionProfileStore;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.update.MsmUpdateManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dev.cylox.msm.screen.SessionManagerScreen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class MsmClient implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("MSM");
    private static final List<SessionProfile> PROFILES = new ArrayList<>();

    private static SessionProfileStore profileStore;
    private static AccountIdentityStore accountIdentityStore;
    private static final List<AccountIdentity> ACCOUNT_IDENTITIES = new ArrayList<>();
    private static SessionManager sessionManager;
    private static SecondarySessionController secondarySessions;
    private static SessionAutomationScheduler automationScheduler;
    private static MsmUpdateManager updateManager;

    @Override
    public void onInitializeClient() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        updateManager = new MsmUpdateManager(configDir, "1.21.8",
                FabricLoader.getInstance().getModContainer("msm_fabric")
                        .map(container -> container.getMetadata().getVersion().getFriendlyString())
                        .orElse("unknown"));
        profileStore = new SessionProfileStore(configDir.resolve("msm-fabric.properties"));
        accountIdentityStore = new AccountIdentityStore(configDir.resolve("msm-accounts.properties"));
        sessionManager = new SessionManager();
        secondarySessions = new SecondarySessionController(sessionManager);
        automationScheduler = new SessionAutomationScheduler(
                sessionManager,
                secondarySessions,
                new SessionAutomationStore(configDir.resolve("msm-automation.properties")));

        try {
            PROFILES.addAll(profileStore.load());
            ACCOUNT_IDENTITIES.addAll(accountIdentityStore.load());
            for (SessionProfile profile : PROFILES) {
                AccountIdentity identity = new AccountIdentity(profile.getUsername(), profile.getAuthMode());
                if (!ACCOUNT_IDENTITIES.contains(identity)) ACCOUNT_IDENTITIES.add(identity);
            }
            accountIdentityStore.save(ACCOUNT_IDENTITIES);
            for (SessionProfile profile : PROFILES) {
                sessionManager.create(profile);
            }
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.error("Could not restore MSM profiles: {}", exception.getMessage(), exception);
        }

        automationScheduler.restoreDesiredSessions();
        LOGGER.info("MSM Fabric 1.21.8 initialized with {} saved profiles.", PROFILES.size());
    }

    public static void openManager(Screen parent) {
        MinecraftClient.getInstance().setScreen(new SessionManagerScreen(parent));
    }

    public static MsmUpdateManager getUpdateManager() {
        return updateManager;
    }

    public static SessionManager getSessionManager() {
        return sessionManager;
    }

    public static SecondarySessionController getSecondarySessions() {
        return secondarySessions;
    }

    public static SessionAutomationScheduler getAutomationScheduler() {
        return automationScheduler;
    }

    public static synchronized List<SessionProfile> getProfiles() {
        return Collections.unmodifiableList(new ArrayList<>(PROFILES));
    }

    public static synchronized List<AccountIdentity> getAccountIdentities() {
        return Collections.unmodifiableList(new ArrayList<>(ACCOUNT_IDENTITIES));
    }

    public static synchronized void addAccountIdentity(AccountIdentity identity) throws IOException {
        if (identity == null) throw new IllegalArgumentException("Account identity is required");
        if (ACCOUNT_IDENTITIES.contains(identity)) {
            throw new IllegalArgumentException("This account already exists.");
        }
        ACCOUNT_IDENTITIES.add(identity);
        try {
            accountIdentityStore.save(ACCOUNT_IDENTITIES);
        } catch (IOException exception) {
            ACCOUNT_IDENTITIES.remove(identity);
            throw exception;
        }
    }

    public static synchronized void removeAccountIdentity(String username, dev.cylox.msm.profile.SessionAuthMode authMode) throws IOException {
        AccountIdentity target = new AccountIdentity(username, authMode);
        if (!ACCOUNT_IDENTITIES.remove(target)) return;
        try {
            accountIdentityStore.save(ACCOUNT_IDENTITIES);
        } catch (IOException exception) {
            ACCOUNT_IDENTITIES.add(target);
            throw exception;
        }
    }

    public static synchronized SessionManager.Session addProfile(SessionProfile profile) throws IOException {
        if (profileStore == null || sessionManager == null) {
            throw new IllegalStateException("MSM has not finished initializing.");
        }

        SessionManager.Session created = sessionManager.create(profile);
        PROFILES.add(profile);
        try {
            profileStore.save(PROFILES);
            return created;
        } catch (IOException exception) {
            PROFILES.remove(profile);
            sessionManager.remove(created.getId());
            throw exception;
        }
    }

    public static void removeProfile(long sessionId, Consumer<Throwable> completion) {
        Optional<SessionManager.Session> optional = sessionManager.find(sessionId);
        if (!optional.isPresent()) {
            completion.accept(new IllegalArgumentException("This account is no longer available."));
            return;
        }

        SessionManager.Session managed = optional.get();
        automationScheduler.disconnectAndForgetSession(sessionId, failure ->
                MinecraftClient.getInstance().execute(() -> {
                    if (failure != null) {
                        completion.accept(failure);
                        return;
                    }

                    boolean removedFromProfiles = PROFILES.remove(managed.getProfile());
                    try {
                        profileStore.save(PROFILES);
                        secondarySessions.forgetSession(sessionId);
                        sessionManager.remove(sessionId);
                        completion.accept(null);
                    } catch (Throwable exception) {
                        if (removedFromProfiles && !PROFILES.contains(managed.getProfile())) {
                            PROFILES.add(managed.getProfile());
                            try {
                                profileStore.save(PROFILES);
                            } catch (IOException rollbackFailure) {
                                exception.addSuppressed(rollbackFailure);
                            }
                        }
                        completion.accept(exception);
                    }
                }));
    }
}
