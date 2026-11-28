package dev.cylox.msm.automation;

import dev.cylox.msm.network.SecondarySessionController;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
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
 * Serializes automatic and manual reconnect attempts across all managed accounts.
 */
public final class SessionAutomationScheduler
        implements SecondarySessionController.SessionLifecycleListener {
    private static final Logger LOGGER = LogManager.getLogger("MSM-Automation");

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
    private final Set<Long> manualConnectQueue = new HashSet<Long>();
    private final Map<Long, Integer> reconnectAttempts = new HashMap<Long, Integer>();

    private volatile boolean accountsRestored;
    private long activeReconnectSession = -1L;
    private ScheduledFuture<?> reconnectTask;

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
            SessionState state = this.controller.getState(sessionId);
            if (state == SessionState.CONNECTING || state == SessionState.ONLINE) return;

            if (this.activeReconnectSession == sessionId) {
                this.activeReconnectSession = -1L;
                cancelReconnectTask();
            }

            this.reconnectAttempts.remove(sessionId);
            this.reconnectQueue.remove(sessionId);
            this.manualConnectQueue.add(sessionId);
            pumpReconnectQueue();
        });
    }

    public void disconnectManually(long sessionId) {
        SessionAutomationSettings settings = getSettings(sessionId);
        settings.setDesiredOnline(false);
        persistSettings(sessionId, settings);

        this.worker.execute(() -> {
            this.reconnectQueue.remove(sessionId);
            this.manualConnectQueue.remove(sessionId);
            this.reconnectAttempts.remove(sessionId);
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
            this.manualConnectQueue.remove(sessionId);
            this.reconnectAttempts.remove(sessionId);

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

    /**
     * Disconnects a session and removes its automation data after the disconnect
     * lifecycle callback has finished on the automation worker.
     */
    public void disconnectAndForgetSession(long sessionId, Consumer<Throwable> completion) {
        SessionManager.Session session = requireSession(sessionId);
        this.worker.execute(() -> {
            this.reconnectQueue.remove(sessionId);
            this.manualConnectQueue.remove(sessionId);
            this.reconnectAttempts.remove(sessionId);

            if (this.activeReconnectSession == sessionId) {
                this.activeReconnectSession = -1L;
                cancelReconnectTask();
            }

            try {
                SessionState state = this.controller.getState(sessionId);
                if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
                    this.controller.disconnect(sessionId);
                }
            } catch (Throwable exception) {
                if (completion != null) completion.accept(exception);
                return;
            }

            // Queue removal after onDisconnected() so lifecycle handling cannot recreate settings.
            this.worker.execute(() -> {
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

            pumpReconnectQueue();
        });
    }

    @Override
    public void onDisconnected(long sessionId, boolean manuallyStopped, String reason) {
        this.worker.execute(() -> {

            if (this.activeReconnectSession == sessionId) {
                this.activeReconnectSession = -1L;
                cancelReconnectTask();
            }

            SessionAutomationSettings settings = getSettings(sessionId);
            if (manuallyStopped) {
                settings.setDesiredOnline(false);
                persistSettings(sessionId, settings);
                this.reconnectQueue.remove(sessionId);
                this.manualConnectQueue.remove(sessionId);
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

    static long selectNextQueuedSession(List<SessionManager.Session> sessions,
                                        Set<Long> manualQueue, Set<Long> reconnectQueue) {
        for (SessionManager.Session session : sessions) {
            long sessionId = session.getId();
            if (manualQueue.contains(sessionId) || reconnectQueue.contains(sessionId)) {
                return sessionId;
            }
        }
        return -1L;
    }

    private void pumpReconnectQueue() {
        if (this.activeReconnectSession >= 0L) return;

        while (true) {
            long sessionId = selectNextQueuedSession(
                    this.sessionManager.getSessions(),
                    this.manualConnectQueue,
                    this.reconnectQueue);
            if (sessionId < 0L) return;

            boolean manual = this.manualConnectQueue.remove(sessionId);
            this.reconnectQueue.remove(sessionId);
            SessionAutomationSettings settings = getSettings(sessionId);

            if (!settings.isDesiredOnline()
                    || (!manual && !settings.isAutoReconnect())
                    || !isOffline(sessionId)) {
                continue;
            }

            this.activeReconnectSession = sessionId;
            if (manual) {
                this.reconnectAttempts.remove(sessionId);
                this.controller.addSystemMessage(sessionId,
                        "[MSM] Manual connection queued in account order.");
                this.reconnectTask = this.worker.schedule(
                        () -> attemptManualConnect(sessionId), 0L, TimeUnit.MILLISECONDS);
            } else {
                int nextAttempt = this.reconnectAttempts.getOrDefault(sessionId, 0) + 1;
                long delaySeconds = reconnectDelay(settings, nextAttempt);
                this.controller.addSystemMessage(sessionId,
                        "[MSM] AutoReconnect attempt " + nextAttempt + " queued in "
                                + delaySeconds + "s.");
                this.reconnectTask = this.worker.schedule(
                        () -> attemptReconnect(sessionId, nextAttempt),
                        delaySeconds, TimeUnit.SECONDS);
            }
            return;
        }
    }

    private void attemptManualConnect(long sessionId) {
        this.reconnectTask = null;
        if (this.activeReconnectSession != sessionId) return;

        SessionAutomationSettings settings = getSettings(sessionId);
        if (!settings.isDesiredOnline() || !isOffline(sessionId)) {
            this.activeReconnectSession = -1L;
            pumpReconnectQueue();
            return;
        }

        try {
            this.controller.connect(sessionId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Manual connection failed for session {}: {}",
                    sessionId, exception.getMessage());
            this.activeReconnectSession = -1L;
            if (isOffline(sessionId)) {
                this.controller.addSystemMessage(sessionId,
                        "[MSM] Could not connect: " + safeMessage(exception));
            }
            pumpReconnectQueue();
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

    static long reconnectDelay(SessionAutomationSettings settings, int attempt) {
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

    static boolean isServerRejection(String reason) {
        if (reason == null) return false;
        String normalized = reason.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("banned")
                || normalized.contains("ban ")
                || normalized.contains("whitelist")
                || normalized.contains("not whitelisted")
                || normalized.contains("invalid session")
                || normalized.contains("not authenticated")
                || normalized.contains("online-mode authentication")
                || normalized.contains("offline_local cannot authenticate")
                || normalized.contains("requires online mode")
                || normalized.contains("already logged in")
                || normalized.contains("logged in from another location")
                || normalized.contains("too many login attempts")
                || normalized.contains("too many connections")
                || normalized.contains("rate limit")
                || normalized.contains("connection throttled")
                || normalized.contains("temporarily blocked");
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

}
