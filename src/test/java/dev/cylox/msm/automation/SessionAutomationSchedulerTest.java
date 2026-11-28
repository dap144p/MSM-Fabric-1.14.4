package dev.cylox.msm.automation;

import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.session.SessionManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SessionAutomationSchedulerTest {
    public static void main(String[] args) {
        check(SessionAutomationScheduler.isServerRejection(
                "MSM OFFLINE_LOCAL cannot authenticate to online-mode servers."),
                "Offline sessions must stop reconnecting to online-mode servers.");
        check(SessionAutomationScheduler.isServerRejection(
                "Too many login attempts; connection temporarily blocked."),
                "Rate-limited login attempts must stop automatic retries.");
        check(SessionAutomationScheduler.isServerRejection(
                "You are logging in too fast."),
                "Login-rate throttling must stop automatic retries.");
        check(!SessionAutomationScheduler.isServerRejection("Connection reset by peer"),
                "A transient network failure must remain retryable.");
        check(!SessionAutomationScheduler.isServerRejection("Connection timed out"),
                "A transient timeout must remain retryable.");

        SessionAutomationSettings settings = new SessionAutomationSettings();
        check(SessionAutomationScheduler.reconnectDelay(settings, 1) == 15L,
                "First retry must use the 15-second base delay.");
        check(SessionAutomationScheduler.reconnectDelay(settings, 2) == 30L,
                "Second retry must back off exponentially.");
        check(SessionAutomationScheduler.reconnectDelay(settings, 3) == 60L,
                "Third retry must continue exponential backoff.");
        check(SessionAutomationScheduler.reconnectDelay(settings, 5) == 240L,
                "Fifth retry must preserve the expected backoff.");
        check(SessionAutomationScheduler.reconnectDelay(settings, 6) == 300L,
                "Retry delay must not exceed the configured cap.");
        settings.setRetryBackoff(false);
        check(SessionAutomationScheduler.reconnectDelay(settings, 5) == 15L,
                "Disabling exponential backoff must preserve the base delay.");

        SessionManager manager = new SessionManager();
        SessionManager.Session first = manager.create(new SessionProfile(
                "dap7000", "play.example.net", SessionAuthMode.OFFLINE_LOCAL));
        SessionManager.Session second = manager.create(new SessionProfile(
                "account02", "play.example.net", SessionAuthMode.OFFLINE_LOCAL));
        SessionManager.Session third = manager.create(new SessionProfile(
                "account03", "play.example.net", SessionAuthMode.OFFLINE_LOCAL));

        List<SessionManager.Session> orderedSessions = manager.getSessions();
        Set<Long> manualQueue = new HashSet<Long>();
        Set<Long> reconnectQueue = new HashSet<Long>();
        manualQueue.add(second.getId());
        manualQueue.add(first.getId());
        reconnectQueue.add(third.getId());

        expectQueuedSession(SessionAutomationScheduler.selectNextQueuedSession(
                orderedSessions, manualQueue, reconnectQueue),
                first.getId(), "first account across manual and reconnect queues");
        manualQueue.remove(first.getId());
        expectQueuedSession(SessionAutomationScheduler.selectNextQueuedSession(
                orderedSessions, manualQueue, reconnectQueue),
                second.getId(), "second account across manual and reconnect queues");
        manualQueue.remove(second.getId());
        expectQueuedSession(SessionAutomationScheduler.selectNextQueuedSession(
                orderedSessions, manualQueue, reconnectQueue),
                third.getId(), "reconnect account after manual connection requests");
        reconnectQueue.clear();
        expectQueuedSession(SessionAutomationScheduler.selectNextQueuedSession(
                orderedSessions, manualQueue, reconnectQueue),
                -1L, "empty connection queues");

        System.out.println("SessionAutomationSchedulerTest: PASS");
    }

    private static void expectQueuedSession(long actual, long expected, String description) {
        if (actual != expected) {
            throw new AssertionError("Unexpected connection order for " + description
                    + ": expected " + expected + " but got " + actual);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
