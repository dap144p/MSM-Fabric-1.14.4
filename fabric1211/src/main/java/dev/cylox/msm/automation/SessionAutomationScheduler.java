package dev.cylox.msm.automation;

import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Serializes reconnect attempts and automated chat across all managed accounts.
 * All queue mutation happens on one daemon worker to avoid overlapping actions.
 */
public final class SessionAutomationScheduler
        implements SecondarySessionController.SessionLifecycleListener {
    private static final Logger LOGGER = LoggerFactory.getLogger("MSM-Automation");

    private final SessionManager sessionManager;
    private final SecondarySessionController controller;
    private final SessionAutomationStore store;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MSM-Automation");
                thread.setDaemon(true);
                return thread;
            });

    private final ConcurrentMap<Long, SessionAutomationSettings> settingsBySession =
            new ConcurrentHashMap<Long, SessionAutomationSettings>();
    private final Set<Long> reconnectQueue = new HashSet<Long>();
    private final Map<Long, Integer> reconnectAttempts = new HashMap<Long, Integer>();
    private final List<PendingChat> pendingChats = new ArrayList<PendingChat>();

    private volatile boolean accountsRestored;
    private long activeReconnectSession = -1L;
    private long lastGlobalChatAt;
    private int lastGlobalChatIntervalSeconds = 3;
    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> chatTask;

    public SessionAutomationScheduler(SessionManager sessionManager,
                                      SecondarySessionController controller,
                                      SessionAutomationStore store) {
        if (sessionManager == null || controller == null || store == null) {
            throw new IllegalArgumentException("Session manager, network controller, and settings store are required");
        }
        this.sessionManager = sessionManager;
        this.controller = controller;
        this.store = store;
        this.controller.setLifecycleListener(this);
    }

    public SessionAutomationSettings getSettings(long sessionId) {
        SessionManager.Session session = requireSession(sessionId);
        SessionAutomationSettings cached = this.settingsBySession.get(sessionId);
        if (cached != null) return cached.copy();

        synchronized (this.settingsBySession) {
            cached = this.settingsBySession.get(sessionId);
            if (cached == null) {
                try {
                    cached = this.store.load(session.getProfile());
                } catch (IOException exception) {
                    throw new IllegalStateException("Could not load automation settings: "
                            + exception.getMessage(), exception);
                }
                this.settingsBySession.put(sessionId, cached);
            }
            return cached.copy();
        }
    }

    public void saveSettings(long sessionId, SessionAutomationSettings updated) {
        SessionManager.Session session = requireSession(sessionId);
        if (updated == null) throw new IllegalArgumentException("Automation settings are required");

        SessionAutomationSettings snapshot = updated.copy();
        try {
            this.store.save(session.getProfile(), snapshot);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save automation settings: "
                    + exception.getMessage(), exception);
        }
        this.settingsBySession.put(sessionId, snapshot);

        this.worker.execute(() -> {
            if (!snapshot.isAutoChatEnabled()) {
                cancelChatTasks(sessionId);
            } else if (this.controller.isOnline(sessionId)) {
                scheduleRepeatRules(sessionId, snapshot);
            }

            if (!snapshot.isAutoReconnect() || !snapshot.isDesiredOnline()) {
                this.reconnectQueue.remove(sessionId);
            } else if (isOffline(sessionId)) {
                this.reconnectQueue.add(sessionId);
                pumpReconnectQueue();
            }
        });
    }

    /**
     * Called after saved account profiles have been restored into SessionManager.
     * Only accounts previously marked as desired-online are resumed.
     */
    public void restoreDesiredSessions() {
        if (this.accountsRestored) return;
        this.accountsRestored = true;
        this.worker.execute(() -> {
            for (SessionManager.Session session : this.sessionManager.getSessions()) {
                SessionAutomationSettings settings = getSettings(session.getId());
                if (settings.isAutoReconnect() && settings.isDesiredOnline()) {
                    this.reconnectQueue.add(session.getId());
                }
            }
            pumpReconnectQueue();
        });
    }

    public void connectManually(long sessionId) {
        SessionAutomationSettings settings = getSettings(sessionId);
        settings.setDesiredOnline(true);
        persistSettings(sessionId, settings);

        this.worker.execute(() -> {
            this.reconnectQueue.remove(sessionId);
            this.reconnectAttempts.remove(sessionId);
            if (this.activeReconnectSession == sessionId) {
                this.activeReconnectSession = -1L;
                cancelReconnectTask();
            }
            try {
                this.controller.connect(sessionId);
            } catch (RuntimeException exception) {
                LOGGER.warn("Manual connection failed for session {}: {}",
                        sessionId, exception.getMessage());
                if (isOffline(sessionId)) {
                    this.controller.addSystemMessage(sessionId,
                            "[MSM] Could not connect: " + safeMessage(exception));
                }
            }
        });
    }

    public void disconnectManually(long sessionId) {
        SessionAutomationSettings settings = getSettings(sessionId);
        settings.setDesiredOnline(false);
        persistSettings(sessionId, settings);

        this.worker.execute(() -> {
            this.reconnectQueue.remove(sessionId);
            this.reconnectAttempts.remove(sessionId);
            cancelChatTasks(sessionId);
            if (this.activeReconnectSession == sessionId) {
                this.activeReconnectSession = -1L;
                cancelReconnectTask();
            }
            this.controller.disconnect(sessionId);
            pumpReconnectQueue();
        });
    }

    public void forgetSession(long sessionId) {
        forgetSession(sessionId, null);
    }

    public void forgetSession(long sessionId, Consumer<Throwable> completion) {
        SessionManager.Session session = requireSession(sessionId);
        this.worker.execute(() -> {
            this.reconnectQueue.remove(sessionId);
            this.reconnectAttempts.remove(sessionId);
            cancelChatTasks(sessionId);
            if (this.activeReconnectSession == sessionId) {
                this.activeReconnectSession = -1L;
                cancelReconnectTask();
            }

            SessionState state = this.controller.getState(sessionId);
            if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
                if (completion != null) {
                    completion.accept(new IllegalStateException(
                            "Disconnect the account before removing it."));
                }
                return;
            }

            try {
                this.store.delete(session.getProfile());
                this.settingsBySession.remove(sessionId);
                if (completion != null) completion.accept(null);
            } catch (IOException exception) {
                LOGGER.warn("Could not remove automation settings for session {}: {}",
                        sessionId, exception.getMessage());
                if (completion != null) completion.accept(exception);
            }
        });
    }

    @Override
    public void onJoinedGame(long sessionId) {
        this.worker.execute(() -> {
            SessionAutomationSettings settings = getSettings(sessionId);
            if (!settings.isDesiredOnline()) {
                settings.setDesiredOnline(true);
                persistSettings(sessionId, settings);
            }

            this.reconnectAttempts.remove(sessionId);
            if (this.activeReconnectSession == sessionId) {
                this.activeReconnectSession = -1L;
                cancelReconnectTask();
            }

            scheduleOnceRules(sessionId, settings);
            scheduleRepeatRules(sessionId, settings);
            pumpReconnectQueue();
        });
    }

    @Override
    public void onDisconnected(long sessionId, boolean manuallyStopped, String reason) {
        this.worker.execute(() -> {
            cancelChatTasks(sessionId);

            if (this.activeReconnectSession == sessionId) {
                this.activeReconnectSession = -1L;
                cancelReconnectTask();
            }

            SessionAutomationSettings settings = getSettings(sessionId);
            if (manuallyStopped) {
                settings.setDesiredOnline(false);
                persistSettings(sessionId, settings);
                this.reconnectQueue.remove(sessionId);
                this.reconnectAttempts.remove(sessionId);
                pumpReconnectQueue();
                return;
            }

            if (isServerRejection(reason)) {
                settings.setDesiredOnline(false);
                persistSettings(sessionId, settings);
                this.reconnectAttempts.remove(sessionId);
                this.reconnectQueue.remove(sessionId);
                this.controller.addSystemMessage(sessionId,
                        "[MSM] AutoReconnect paused because the server rejected this session.");
                pumpReconnectQueue();
                return;
            }

            if (!settings.isAutoReconnect() || !settings.isDesiredOnline()) {
                pumpReconnectQueue();
                return;
            }

            if (this.reconnectAttempts.getOrDefault(sessionId, 0)
                    >= settings.getMaxReconnectAttempts()) {
                settings.setDesiredOnline(false);
                persistSettings(sessionId, settings);
                this.reconnectAttempts.remove(sessionId);
                this.controller.addSystemMessage(sessionId,
                        "[MSM] AutoReconnect stopped after reaching the attempt limit.");
                pumpReconnectQueue();
                return;
            }

            this.reconnectQueue.add(sessionId);
            pumpReconnectQueue();
        });
    }

    private void scheduleOnceRules(long sessionId, SessionAutomationSettings settings) {
        cancelChatTasks(sessionId);
        if (!settings.isAutoChatEnabled()) return;

        long dueAt = System.currentTimeMillis()
                + TimeUnit.SECONDS.toMillis(settings.getInitialChatDelaySeconds());
        List<SessionAutomationSettings.AutoChatRule> rules = settings.getRules();
        for (int index = 0; index < rules.size(); index++) {
            SessionAutomationSettings.AutoChatRule rule = rules.get(index);
            if (!rule.isEnabled() || rule.getMode() != SessionAutomationSettings.AutoChatRule.Mode.ONCE) {
                continue;
            }
            dueAt += TimeUnit.SECONDS.toMillis(rule.getDelaySeconds());
            this.pendingChats.add(new PendingChat(sessionId, index, false, dueAt));
        }
        scheduleChatPump();
    }

    private void scheduleRepeatRules(long sessionId, SessionAutomationSettings settings) {
        cancelRepeatTasks(sessionId);
        if (!settings.isAutoChatEnabled() || !this.controller.isOnline(sessionId)) return;

        long startAt = System.currentTimeMillis()
                + TimeUnit.SECONDS.toMillis(settings.getInitialChatDelaySeconds());
        List<SessionAutomationSettings.AutoChatRule> rules = settings.getRules();
        for (int index = 0; index < rules.size(); index++) {
            SessionAutomationSettings.AutoChatRule rule = rules.get(index);
            if (!rule.isEnabled() || rule.getMode() != SessionAutomationSettings.AutoChatRule.Mode.REPEAT) {
                continue;
            }
            long dueAt = startAt + TimeUnit.SECONDS.toMillis(rule.getDelaySeconds());
            this.pendingChats.add(new PendingChat(sessionId, index, true, dueAt));
        }
        scheduleChatPump();
    }

    private void cancelRepeatTasks(long sessionId) {
        Iterator<PendingChat> iterator = this.pendingChats.iterator();
        while (iterator.hasNext()) {
            PendingChat pending = iterator.next();
            if (pending.sessionId == sessionId && pending.repeat) iterator.remove();
        }
        scheduleChatPump();
    }

    private void cancelChatTasks(long sessionId) {
        Iterator<PendingChat> iterator = this.pendingChats.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().sessionId == sessionId) iterator.remove();
        }
        scheduleChatPump();
    }

    private void scheduleChatPump() {
        if (this.chatTask != null) {
            this.chatTask.cancel(false);
            this.chatTask = null;
        }
        if (this.pendingChats.isEmpty()) return;

        long nextDue = Long.MAX_VALUE;
        for (PendingChat pending : this.pendingChats) {
            nextDue = Math.min(nextDue, pending.dueAt);
        }
        long wait = Math.max(0L, nextDue - System.currentTimeMillis());
        this.chatTask = this.worker.schedule(this::pumpChatQueue, wait, TimeUnit.MILLISECONDS);
    }

    private void pumpChatQueue() {
        this.chatTask = null;
        long now = System.currentTimeMillis();
        PendingChat next = selectNextPendingChat(
                this.pendingChats, this.sessionManager.getSessions(), now);
        if (next == null) return;

        if (next.dueAt > now) {
            this.chatTask = this.worker.schedule(
                    this::pumpChatQueue, next.dueAt - now, TimeUnit.MILLISECONDS);
            return;
        }

        SessionAutomationSettings settings;
        SessionAutomationSettings.AutoChatRule rule;
        try {
            settings = getSettings(next.sessionId);
            List<SessionAutomationSettings.AutoChatRule> rules = settings.getRules();
            if (!settings.isAutoChatEnabled() || next.ruleIndex >= rules.size()) {
                this.pendingChats.remove(next);
                scheduleChatPump();
                return;
            }
            rule = rules.get(next.ruleIndex);
        } catch (RuntimeException exception) {
            this.pendingChats.remove(next);
            scheduleChatPump();
            return;
        }

        boolean validMode = next.repeat
                ? rule.getMode() == SessionAutomationSettings.AutoChatRule.Mode.REPEAT
                : rule.getMode() == SessionAutomationSettings.AutoChatRule.Mode.ONCE;
        if (!rule.isEnabled() || !validMode || !this.controller.isOnline(next.sessionId)) {
            this.pendingChats.remove(next);
            scheduleChatPump();
            return;
        }

        long globalInterval = Math.max(settings.getGlobalChatIntervalSeconds(),
                this.lastGlobalChatIntervalSeconds);
        long earliestSend = this.lastGlobalChatAt
                + TimeUnit.SECONDS.toMillis(globalInterval);
        if (now < earliestSend) {
            this.chatTask = this.worker.schedule(this::pumpChatQueue,
                    earliestSend - now, TimeUnit.MILLISECONDS);
            return;
        }

        try {
            this.controller.sendChat(next.sessionId, rule.getMessage());
            this.lastGlobalChatAt = System.currentTimeMillis();
            this.lastGlobalChatIntervalSeconds = settings.getGlobalChatIntervalSeconds();
            this.controller.addSystemMessage(next.sessionId,
                    "[MSM] AutoChat sent rule " + (next.ruleIndex + 1) + ".");
        } catch (RuntimeException exception) {
            LOGGER.warn("AutoChat rule {} failed for session {}: {}",
                    next.ruleIndex + 1, next.sessionId, exception.getMessage());
        }

        if (next.repeat && this.controller.isOnline(next.sessionId)) {
            next.dueAt = System.currentTimeMillis()
                    + TimeUnit.SECONDS.toMillis(rule.getDelaySeconds());
        } else {
            this.pendingChats.remove(next);
        }
        scheduleChatPump();
    }

    static PendingChat selectNextPendingChat(List<PendingChat> pendingChats,
                                             List<SessionManager.Session> sessions,
                                             long now) {
        PendingChat nextOnce = null;
        PendingChat nextDueRepeat = null;
        PendingChat nextFutureRepeat = null;

        for (PendingChat pending : pendingChats) {
            if (!pending.repeat) {
                if (nextOnce == null || compareOnceOrder(pending, nextOnce, sessions) < 0) {
                    nextOnce = pending;
                }
            } else if (pending.dueAt <= now) {
                if (nextDueRepeat == null
                        || compareReadyRepeatOrder(pending, nextDueRepeat, sessions) < 0) {
                    nextDueRepeat = pending;
                }
            } else if (nextFutureRepeat == null
                    || compareFutureRepeatOrder(pending, nextFutureRepeat, sessions) < 0) {
                nextFutureRepeat = pending;
            }
        }

        if (nextOnce != null) return nextOnce;
        return nextDueRepeat != null ? nextDueRepeat : nextFutureRepeat;
    }

    private static int compareOnceOrder(PendingChat left, PendingChat right,
                                        List<SessionManager.Session> sessions) {
        int leftAccount = accountOrder(left.sessionId, sessions);
        int rightAccount = accountOrder(right.sessionId, sessions);
        if (leftAccount != rightAccount) return Integer.compare(leftAccount, rightAccount);
        if (left.ruleIndex != right.ruleIndex) return Integer.compare(left.ruleIndex, right.ruleIndex);
        return Long.compare(left.dueAt, right.dueAt);
    }

    private static int compareReadyRepeatOrder(PendingChat left, PendingChat right,
                                               List<SessionManager.Session> sessions) {
        int leftAccount = accountOrder(left.sessionId, sessions);
        int rightAccount = accountOrder(right.sessionId, sessions);
        if (leftAccount != rightAccount) return Integer.compare(leftAccount, rightAccount);
        if (left.dueAt != right.dueAt) return Long.compare(left.dueAt, right.dueAt);
        return Integer.compare(left.ruleIndex, right.ruleIndex);
    }

    private static int compareFutureRepeatOrder(PendingChat left, PendingChat right,
                                                List<SessionManager.Session> sessions) {
        if (left.dueAt != right.dueAt) return Long.compare(left.dueAt, right.dueAt);
        int leftAccount = accountOrder(left.sessionId, sessions);
        int rightAccount = accountOrder(right.sessionId, sessions);
        if (leftAccount != rightAccount) return Integer.compare(leftAccount, rightAccount);
        return Integer.compare(left.ruleIndex, right.ruleIndex);
    }

    private static int accountOrder(long sessionId, List<SessionManager.Session> sessions) {
        for (int index = 0; index < sessions.size(); index++) {
            if (sessions.get(index).getId() == sessionId) return index;
        }
        return Integer.MAX_VALUE;
    }

    private void pumpReconnectQueue() {
        if (this.activeReconnectSession >= 0L || this.reconnectQueue.isEmpty()) return;

        for (SessionManager.Session session : this.sessionManager.getSessions()) {
            long sessionId = session.getId();
            if (!this.reconnectQueue.contains(sessionId)) continue;

            SessionAutomationSettings settings = getSettings(sessionId);
            if (!settings.isAutoReconnect() || !settings.isDesiredOnline()) {
                this.reconnectQueue.remove(sessionId);
                continue;
            }
            if (!isOffline(sessionId)) {
                this.reconnectQueue.remove(sessionId);
                continue;
            }

            this.reconnectQueue.remove(sessionId);
            this.activeReconnectSession = sessionId;
            int nextAttempt = this.reconnectAttempts.getOrDefault(sessionId, 0) + 1;
            long delaySeconds = reconnectDelay(settings, nextAttempt);
            this.controller.addSystemMessage(sessionId,
                    "[MSM] AutoReconnect attempt " + nextAttempt + " queued in "
                            + delaySeconds + "s.");
            this.reconnectTask = this.worker.schedule(
                    () -> attemptReconnect(sessionId, nextAttempt),
                    delaySeconds, TimeUnit.SECONDS);
            return;
        }
    }

    private void attemptReconnect(long sessionId, int attempt) {
        this.reconnectTask = null;
        if (this.activeReconnectSession != sessionId) return;

        SessionAutomationSettings settings = getSettings(sessionId);
        if (!settings.isAutoReconnect() || !settings.isDesiredOnline() || !isOffline(sessionId)) {
            this.activeReconnectSession = -1L;
            pumpReconnectQueue();
            return;
        }

        this.reconnectAttempts.put(sessionId, attempt);
        try {
            this.controller.connect(sessionId);
        } catch (RuntimeException exception) {
            LOGGER.warn("AutoReconnect attempt {} failed for session {}: {}",
                    attempt, sessionId, exception.getMessage());
            if (this.controller.getState(sessionId) != SessionState.CONNECTING
                    && this.controller.getState(sessionId) != SessionState.ONLINE) {
                handleReconnectFailure(sessionId, attempt, safeMessage(exception));
            }
        }
    }

    private void handleReconnectFailure(long sessionId, int attempt, String reason) {
        SessionAutomationSettings settings = getSettings(sessionId);
        this.activeReconnectSession = -1L;
        this.controller.addSystemMessage(sessionId, "[MSM] AutoReconnect failed: " + reason);

        if (attempt >= settings.getMaxReconnectAttempts()) {
            settings.setDesiredOnline(false);
            persistSettings(sessionId, settings);
            this.reconnectAttempts.remove(sessionId);
            this.controller.addSystemMessage(sessionId,
                    "[MSM] AutoReconnect stopped after reaching the attempt limit.");
            pumpReconnectQueue();
            return;
        }

        this.reconnectQueue.add(sessionId);
        pumpReconnectQueue();
    }

    private long reconnectDelay(SessionAutomationSettings settings, int attempt) {
        long delay = settings.getReconnectDelaySeconds();
        if (settings.isRetryBackoff() && attempt > 1) {
            for (int index = 1; index < attempt; index++) {
                delay = Math.min(settings.getMaxReconnectDelaySeconds(), delay * 2L);
            }
        }
        return Math.min(settings.getMaxReconnectDelaySeconds(), delay);
    }

    private boolean isOffline(long sessionId) {
        SessionState state;
        try {
            state = this.controller.getState(sessionId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return state == SessionState.OFFLINE
                || state == SessionState.DISCONNECTED
                || state == SessionState.FAILED;
    }

    private boolean isServerRejection(String reason) {
        if (reason == null) return false;
        String normalized = reason.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("banned")
                || normalized.contains("ban ")
                || normalized.contains("whitelist")
                || normalized.contains("not whitelisted")
                || normalized.contains("invalid session")
                || normalized.contains("not authenticated")
                || normalized.contains("already logged in")
                || normalized.contains("logged in from another location");
    }

    private void persistSettings(long sessionId, SessionAutomationSettings settings) {
        SessionManager.Session session = requireSession(sessionId);
        try {
            this.store.save(session.getProfile(), settings);
        } catch (IOException exception) {
            LOGGER.error("Could not persist automation settings for session {}", sessionId, exception);
        }
        this.settingsBySession.put(sessionId, settings.copy());
    }

    private void cancelReconnectTask() {
        if (this.reconnectTask != null) {
            this.reconnectTask.cancel(false);
            this.reconnectTask = null;
        }
    }

    private SessionManager.Session requireSession(long sessionId) {
        return this.sessionManager.find(sessionId).orElseThrow(
                () -> new IllegalArgumentException("Unknown MSM session: " + sessionId));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    static final class PendingChat {
        final long sessionId;
        final int ruleIndex;
        final boolean repeat;
        long dueAt;

        PendingChat(long sessionId, int ruleIndex, boolean repeat, long dueAt) {
            this.sessionId = sessionId;
            this.ruleIndex = ruleIndex;
            this.repeat = repeat;
            this.dueAt = dueAt;
        }
    }
}
