package dev.cylox.msm.automation;

import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.session.SessionManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SessionAutomationSchedulerTest {
    public static void main(String[] args) {
        SessionManager manager = new SessionManager();
        SessionManager.Session first = manager.create(new SessionProfile(
                "dap7000", "play.example.net", SessionAuthMode.OFFLINE_LOCAL));
        SessionManager.Session second = manager.create(new SessionProfile(
                "account02", "play.example.net", SessionAuthMode.OFFLINE_LOCAL));
        SessionManager.Session third = manager.create(new SessionProfile(
                "account03", "play.example.net", SessionAuthMode.OFFLINE_LOCAL));

        List<SessionAutomationScheduler.PendingChat> pending =
                new ArrayList<SessionAutomationScheduler.PendingChat>();

        // Later-account Once rules and ready Repeat rules must not overtake the
        // earliest account's Once sequence, even when their due times are earlier.
        pending.add(new SessionAutomationScheduler.PendingChat(second.getId(), 0, false, 1_000L));
        pending.add(new SessionAutomationScheduler.PendingChat(first.getId(), 3, false, 5_000L));
        pending.add(new SessionAutomationScheduler.PendingChat(first.getId(), 0, false, 9_000L));
        pending.add(new SessionAutomationScheduler.PendingChat(third.getId(), 0, true, 100L));

        List<SessionManager.Session> orderedSessions = manager.getSessions();

        Set<Long> manualQueue = new HashSet<Long>();
        Set<Long> reconnectQueue = new HashSet<Long>();
        manualQueue.add(second.getId());
        manualQueue.add(first.getId());
        reconnectQueue.add(third.getId());
        expectQueuedSession(
                SessionAutomationScheduler.selectNextQueuedSession(
                        orderedSessions, manualQueue, reconnectQueue),
                first.getId(), "first account across manual and reconnect queues");
        manualQueue.remove(first.getId());
        expectQueuedSession(
                SessionAutomationScheduler.selectNextQueuedSession(
                        orderedSessions, manualQueue, reconnectQueue),
                second.getId(), "second account across manual and reconnect queues");
        manualQueue.remove(second.getId());
        expectQueuedSession(
                SessionAutomationScheduler.selectNextQueuedSession(
                        orderedSessions, manualQueue, reconnectQueue),
                third.getId(), "reconnect account after manual connection requests");
        reconnectQueue.clear();
        expectQueuedSession(
                SessionAutomationScheduler.selectNextQueuedSession(
                        orderedSessions, manualQueue, reconnectQueue),
                -1L, "empty connection queues");

        SessionAutomationScheduler.PendingChat selected =
                SessionAutomationScheduler.selectNextPendingChat(pending, orderedSessions, 500L);
        expect(selected, first.getId(), 0, false, "first account's first Once rule");
        pending.remove(selected);

        selected = SessionAutomationScheduler.selectNextPendingChat(pending, orderedSessions, 500L);
        expect(selected, first.getId(), 3, false, "first account's next Once rule");
        pending.remove(selected);

        selected = SessionAutomationScheduler.selectNextPendingChat(pending, orderedSessions, 500L);
        expect(selected, second.getId(), 0, false, "second account's Once rule");
        pending.remove(selected);

        // Start the Repeat phase with a clean fixture; the previous phase deliberately
        // left an old Repeat item pending to verify Once priority.
        pending.clear();
        pending.add(new SessionAutomationScheduler.PendingChat(first.getId(), 1, true, 3_000L));
        pending.add(new SessionAutomationScheduler.PendingChat(third.getId(), 2, true, 2_000L));
        selected = SessionAutomationScheduler.selectNextPendingChat(pending, orderedSessions, 3_500L);
        expect(selected, first.getId(), 1, true, "first account among ready Repeat rules");
        pending.remove(selected);

        selected = SessionAutomationScheduler.selectNextPendingChat(pending, orderedSessions, 3_500L);
        expect(selected, third.getId(), 2, true, "next account among ready Repeat rules");

        // When no task is due yet, the scheduler exposes the nearest future Repeat
        // task so the worker can sleep until its due time instead of polling.
        pending.clear();
        pending.add(new SessionAutomationScheduler.PendingChat(second.getId(), 0, true, 8_000L));
        pending.add(new SessionAutomationScheduler.PendingChat(first.getId(), 1, true, 6_000L));
        selected = SessionAutomationScheduler.selectNextPendingChat(pending, orderedSessions, 5_000L);
        expect(selected, first.getId(), 1, true, "nearest future Repeat rule");

        System.out.println("SessionAutomationSchedulerTest: PASS");
    }

    private static void expectQueuedSession(long actual, long expected, String description) {
        if (actual != expected) {
            throw new AssertionError("Unexpected connection order for " + description
                    + ": expected " + expected + " but got " + actual);
        }
    }

    private static void expect(SessionAutomationScheduler.PendingChat actual,
                               long sessionId, int ruleIndex, boolean repeat, String description) {
        if (actual == null
                || actual.sessionId != sessionId
                || actual.ruleIndex != ruleIndex
                || actual.repeat != repeat) {
            throw new AssertionError("Unexpected selection for " + description);
        }
    }
}
