package dev.cylox.msm.automation;

import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class SessionAutomationStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("msm-reconnect-test");
        Path file = directory.resolve("automation.properties");
        SessionAutomationStore store = new SessionAutomationStore(file);

        SessionProfile first = new SessionProfile(
                "dap7000", "play.example.net", SessionAuthMode.OFFLINE_LOCAL);
        SessionProfile second = new SessionProfile(
                "account02", "play.example.net", SessionAuthMode.OFFLINE_LOCAL);

        SessionAutomationSettings firstSettings = new SessionAutomationSettings();
        firstSettings.setAutoReconnect(true);
        firstSettings.setDesiredOnline(true);
        firstSettings.setReconnectDelaySeconds(25);
        firstSettings.setMaxReconnectAttempts(4);
        firstSettings.setRetryBackoff(true);
        firstSettings.setMaxReconnectDelaySeconds(240);
        store.save(first, firstSettings);
        store.save(second, new SessionAutomationSettings());

        SessionAutomationSettings loaded = store.load(first);
        check(loaded.isAutoReconnect(), "AutoReconnect must round-trip");
        check(loaded.isDesiredOnline(), "Desired-online flag must round-trip");
        check(loaded.getReconnectDelaySeconds() == 25, "Reconnect delay must round-trip");
        check(loaded.getMaxReconnectAttempts() == 4, "Reconnect attempts must round-trip");
        check(loaded.isRetryBackoff(), "Retry backoff must round-trip");
        check(loaded.getMaxReconnectDelaySeconds() == 240, "Maximum reconnect delay must round-trip");

        SessionAutomationSettings secondLoaded = store.load(second);
        check(!secondLoaded.isAutoReconnect(), "Account settings must not leak to another profile");
        check(!secondLoaded.isDesiredOnline(), "Desired-online state must remain per-account");

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        String firstPrefix = null;
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("account.") && key.endsWith(".autoReconnect")) {
                firstPrefix = key.substring(0, key.length() - "autoReconnect".length());
                break;
            }
        }
        check(firstPrefix != null, "First account storage key must exist");
        properties.setProperty(firstPrefix + "autoChatEnabled", "true");
        properties.setProperty(firstPrefix + "initialChatDelaySeconds", "4");
        properties.setProperty(firstPrefix + "globalChatIntervalSeconds", "2");
        properties.setProperty(firstPrefix + "rule.count", "1");
        properties.setProperty(firstPrefix + "rule.0.message", "/legacy");
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "legacy AutoChat migration fixture");
        }

        loaded = store.load(first);
        check(loaded.isAutoReconnect(), "Removing legacy AutoChat keys must preserve reconnect settings");
        try (InputStream input = Files.newInputStream(file)) {
            properties.clear();
            properties.load(input);
        }
        for (String key : properties.stringPropertyNames()) {
            check(!key.startsWith(firstPrefix + "rule.")
                            && !key.equals(firstPrefix + "autoChatEnabled")
                            && !key.equals(firstPrefix + "initialChatDelaySeconds")
                            && !key.equals(firstPrefix + "globalChatIntervalSeconds"),
                    "Legacy AutoChat data must be removed during migration");
        }

        store.delete(first);
        SessionAutomationSettings removedSettings = store.load(first);
        check(!removedSettings.isAutoReconnect(), "Removed account must not retain AutoReconnect");
        check(!removedSettings.isDesiredOnline(), "Removed account must not retain desired-online state");
        check(store.load(second).getMaxReconnectAttempts() == 5,
                "Removing one account must preserve another account's settings");

        SessionAutomationSettings clamped = new SessionAutomationSettings();
        clamped.setReconnectDelaySeconds(1);
        check(clamped.getReconnectDelaySeconds() == 10,
                "Reconnect delay must enforce the safe minimum");
        clamped.setMaxReconnectAttempts(100);
        check(clamped.getMaxReconnectAttempts() == 10,
                "Reconnect attempts must enforce the safe maximum");
        clamped.setMaxReconnectDelaySeconds(5);
        check(clamped.getMaxReconnectDelaySeconds() >= clamped.getReconnectDelaySeconds(),
                "Maximum retry delay must not be lower than the base delay");

        System.out.println("SessionAutomationStoreTest: PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
