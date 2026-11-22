package dev.cylox.msm.net;

import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionNetworkManager {
    private static final int MAX_MESSAGES_PER_SESSION = 60;

    public enum Direction {
        FORWARD, BACKWARD, LEFT, RIGHT
    }

    private final SessionManager sessionManager;
    private final Map<Long, MinecraftSessionConnection> connections =
            new ConcurrentHashMap<Long, MinecraftSessionConnection>();
    private final Map<Long, List<String>> messageHistory =
            new ConcurrentHashMap<Long, List<String>>();

    public SessionNetworkManager(SessionManager sessionManager) {
        if (sessionManager == null) throw new IllegalArgumentException("Session manager is required");
        this.sessionManager = sessionManager;
    }

    public synchronized void connect(long sessionId) {
        SessionManager.Session session = requireSession(sessionId);
        if (session.getProfile().getAuthMode() != SessionAuthMode.OFFLINE_LOCAL) {
            throw new IllegalStateException(
                    "Microsoft sign-in is not implemented yet. Select OFFLINE only for your own offline-mode server.");
        }

        SessionState state = session.getState();
        if (state != SessionState.OFFLINE
                && state != SessionState.DISCONNECTED
                && state != SessionState.FAILED) {
            throw new IllegalStateException("This session is already connecting or online.");
        }

        MinecraftSessionConnection oldConnection = this.connections.get(sessionId);
        if (oldConnection != null && oldConnection.isRunning()) {
            throw new IllegalStateException("This session already has an active connection.");
        }
        this.connections.remove(sessionId);

        this.sessionManager.updateState(sessionId, SessionState.CONNECTING);
        addMessage(sessionId, "[MSM] Connecting to " + session.getProfile().getServerAddress() + "...");
        MinecraftSessionConnection connection = new MinecraftSessionConnection(
                sessionId, session.getProfile(), new ConnectionListener(sessionId));
        this.connections.put(sessionId, connection);
        connection.start();
    }

    public void disconnect(long sessionId) {
        MinecraftSessionConnection connection = this.connections.get(sessionId);
        if (connection == null || !connection.isRunning()) {
            throw new IllegalStateException("This session has no active connection.");
        }
        connection.disconnect();
    }

    public void sendChat(long sessionId, String message) throws IOException {
        requireOnlineConnection(sessionId).sendChat(message);
    }

    public void move(long sessionId, Direction direction) throws IOException {
        if (direction == null) throw new IllegalArgumentException("Movement direction is required.");
        requireOnlineConnection(sessionId).move(MinecraftSessionConnection.Direction.valueOf(direction.name()));
    }

    public void jump(long sessionId) throws IOException {
        requireOnlineConnection(sessionId).jump();
    }

    public boolean toggleSneak(long sessionId) throws IOException {
        return requireOnlineConnection(sessionId).toggleSneak();
    }

    public boolean toggleSprint(long sessionId) throws IOException {
        return requireOnlineConnection(sessionId).toggleSprint();
    }

    public List<String> getRecentMessages(long sessionId) {
        List<String> messages = this.messageHistory.get(sessionId);
        if (messages == null) return Collections.emptyList();
        synchronized (messages) {
            return new ArrayList<String>(messages);
        }
    }

    public void clearHistory(long sessionId) {
        List<String> messages = this.messageHistory.get(sessionId);
        if (messages != null) {
            synchronized (messages) {
                messages.clear();
            }
        }
    }

    public String getPositionText(long sessionId) {
        MinecraftSessionConnection connection = this.connections.get(sessionId);
        return connection == null ? "POSITION: not connected" : connection.getPositionText();
    }

    public boolean isSneaking(long sessionId) {
        MinecraftSessionConnection connection = this.connections.get(sessionId);
        return connection != null && connection.isSneaking();
    }

    public boolean isSprinting(long sessionId) {
        MinecraftSessionConnection connection = this.connections.get(sessionId);
        return connection != null && connection.isSprinting();
    }

    public void forget(long sessionId) {
        MinecraftSessionConnection connection = this.connections.get(sessionId);
        if (connection == null || !connection.isRunning()) {
            this.messageHistory.remove(sessionId);
        }
    }

    public void disconnectAll() {
        for (MinecraftSessionConnection connection : this.connections.values()) {
            connection.disconnect();
        }
    }

    private MinecraftSessionConnection requireOnlineConnection(long sessionId) throws IOException {
        SessionManager.Session session = requireSession(sessionId);
        if (session.getState() != SessionState.ONLINE) {
            throw new IOException("Session is not online.");
        }
        MinecraftSessionConnection connection = this.connections.get(sessionId);
        if (connection == null || !connection.isRunning()) {
            throw new IOException("Session connection is no longer active.");
        }
        return connection;
    }

    private SessionManager.Session requireSession(long sessionId) {
        return this.sessionManager.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
    }

    private void addMessage(long sessionId, String message) {
        List<String> messages = this.messageHistory.get(sessionId);
        if (messages == null) {
            List<String> created = Collections.synchronizedList(new ArrayList<String>());
            List<String> existing = this.messageHistory.putIfAbsent(sessionId, created);
            messages = existing == null ? created : existing;
        }

        synchronized (messages) {
            messages.add(message == null ? "" : message);
            while (messages.size() > MAX_MESSAGES_PER_SESSION) messages.remove(0);
        }
    }

    private void updateState(long sessionId, SessionState target) {
        SessionManager.Session current = this.sessionManager.find(sessionId).orElse(null);
        if (current == null || current.getState() == target) return;
        if (current.getState().canTransitionTo(target)) {
            this.sessionManager.updateState(sessionId, target);
        }
    }

    private final class ConnectionListener implements MinecraftSessionConnection.Listener {
        private final long sessionId;

        private ConnectionListener(long sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onOnline(MinecraftSessionConnection connection) {
            updateState(this.sessionId, SessionState.ONLINE);
        }

        @Override
        public void onMessage(String message) {
            addMessage(this.sessionId, message);
        }

        @Override
        public void onEnded(MinecraftSessionConnection connection, boolean wasOnline,
                            boolean manual, String reason) {
            MinecraftSessionConnection current = connections.get(this.sessionId);
            if (current == connection) connections.remove(this.sessionId);
            SessionState target = manual || wasOnline
                    ? SessionState.DISCONNECTED : SessionState.FAILED;
            updateState(this.sessionId, target);
            if (reason != null && !reason.trim().isEmpty()) {
                addMessage(this.sessionId, "[MSM] " + reason);
            }
        }
    }
}