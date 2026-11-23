package dev.cylox.msm.automation;

import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SessionAutomationStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("msm-automation-test");
        Path file = directory.resolve("automation.properties");
        SessionAutomationStore store = new SessionAutomationStore(file);

        SessionProfile first = new SessionProfile(
                "dap7000", "play.example.net", SessionAuthMode.OFFLINE_LOCAL);
        SessionProfile second = new SessionProfile(
                "account02", "play.example.net", SessionAuthMode.OFFLINE_LOCAL);

        SessionAutomationSettings firstSettings = new SessionAutomationSettings();
        firstSettings.setAutoReconnect(true);
        firstSettings.setDesiredOnline(true);
        firstSettings.setReconnectDelaySeconds(12);
        firstSettings.setMaxReconnectAttempts(7);
        firstSettings.setRetryBackoff(true);
        firstSettings.setMaxReconnectDelaySeconds(90);
        firstSettings.setAutoChatEnabled(true);
        firstSettings.setInitialChatDelaySeconds(6);
        firstSettings.setGlobalChatIntervalSeconds(4);
        firstSettings.addRule(new SessionAutomationSettings.AutoChatRule(
                "/move tycoon",
                SessionAutomationSettings.AutoChatRule.Mode.ONCE,
                5,
                true));
        firstSettings.addRule(new SessionAutomationSettings.AutoChatRule(
                "/is",
                SessionAutomationSettings.AutoChatRule.Mode.REPEAT,
                60,
                false));

        store.save(first, firstSettings);
        store.save(second, new SessionAutomationSettings());

        SessionAutomationSettings loaded = store.load(first);
        check(loaded.isAutoReconnect(), "AutoReconnect must round-trip");
        check(loaded.isDesiredOnline(), "Desired-online flag must round-trip");
        check(loaded.getReconnectDelaySeconds() == 12, "Reconnect delay must round-trip");
        check(loaded.getMaxReconnectAttempts() == 7, "Reconnect attempts must round-trip");
        check(loaded.getMaxReconnectDelaySeconds() == 90, "Maximum reconnect delay must round-trip");
        check(loaded.isAutoChatEnabled(), "AutoChat enabled flag must round-trip");
        check(loaded.getInitialChatDelaySeconds() == 6, "Initial AutoChat delay must round-trip");
        check(loaded.getGlobalChatIntervalSeconds() == 4, "Global chat interval must round-trip");

        List<SessionAutomationSettings.AutoChatRule> rules = loaded.getRules();
        check(rules.size() == 2, "Both AutoChat rules must round-trip");
        check("/move tycoon".equals(rules.get(0).getMessage()), "First rule order must be preserved");
        check(rules.get(0).getMode() == SessionAutomationSettings.AutoChatRule.Mode.ONCE,
                "Once mode must round-trip");
        check(rules.get(0).getDelaySeconds() == 5, "Once delay must round-trip");
        check("/is".equals(rules.get(1).getMessage()), "Second rule order must be preserved");
        check(rules.get(1).getMode() == SessionAutomationSettings.AutoChatRule.Mode.REPEAT,
                "Repeat mode must round-trip");
        check(!rules.get(1).isEnabled(), "Disabled rule state must round-trip");

        SessionAutomationSettings secondLoaded = store.load(second);
        check(!secondLoaded.isAutoReconnect(), "Account settings must not leak to another profile");
        check(secondLoaded.getRules().isEmpty(), "AutoChat rules must remain isolated per profile");

        store.delete(first);
        SessionAutomationSettings removedSettings = store.load(first);
        check(!removedSettings.isAutoReconnect(), "Removed account must not retain AutoReconnect");
        check(!removedSettings.isDesiredOnline(), "Removed account must not retain desired-online state");
        check(!removedSettings.isAutoChatEnabled(), "Removed account must not retain AutoChat");
        check(removedSettings.getRules().isEmpty(), "Removed account must not retain AutoChat rules");
        check(store.load(second).getRules().isEmpty(),
                "Removing one account must preserve other accounts' settings");

        store.save(first, firstSettings);

        loaded.removeRule(0);
        check(store.load(first).getRules().size() == 2,
                "A loaded settings copy must not mutate persisted data without save");

        store.save(first, loaded);
        check(store.load(first).getRules().size() == 1, "Explicit save must persist rule changes");

        System.out.println("SessionAutomationStoreTest: PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
