package dev.cylox.msm.network;

import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.status.PlayerInfo;
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo;
import org.geysermc.mcprotocollib.protocol.data.status.VersionInfo;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundHelloPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ping.clientbound.ClientboundPongResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.status.clientbound.ClientboundStatusResponsePacket;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SecondarySessionController {
    private static final Logger LOGGER = LogManager.getLogger("MSM-Secondary");
    private static final int DEFAULT_PORT = 25565;
    private static final int HANDSHAKE_TIMEOUT_SECONDS = 30;
    private static final int PING_TIMEOUT_SECONDS = 10;
    private static final ScheduledExecutorService TIMEOUTS =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MSM-Network-Timeouts");
                thread.setDaemon(true);
                return thread;
            });

    private final SessionManager sessionManager;
    private final ConcurrentMap<Long, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ChatBuffer> chatBuffers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PingAttempt> pingAttempts = new ConcurrentHashMap<>();
    private volatile SessionLifecycleListener lifecycleListener;

    public SecondarySessionController(SessionManager sessionManager) {
        if (sessionManager == null) {
            throw new IllegalArgumentException("Session manager is required");
        }
        this.sessionManager = sessionManager;
    }

    public void setLifecycleListener(SessionLifecycleListener listener) {
        this.lifecycleListener = listener;
    }

    public void addSystemMessage(long sessionId, String message) {
        appendMessage(sessionId, message);
    }

    public interface SessionLifecycleListener {
        void onJoinedGame(long sessionId);
        void onDisconnected(long sessionId, boolean manuallyStopped, String reason);
    }

    public interface PingCallback {
        void onComplete(String result);
    }

    public void connect(long sessionId) {
        SessionManager.Session managed = requireSession(sessionId);
        SessionProfile profile = managed.getProfile();

        if (profile.getAuthMode() != SessionAuthMode.OFFLINE_LOCAL) {
            String message = "Microsoft sign-in is not implemented yet. Select OFFLINE only if the server permits offline-mode accounts.";
            appendMessage(sessionId, "[MSM] " + message);
            throw new IllegalStateException(message);
        }
        if (managed.getState() == SessionState.CONNECTING || managed.getState() == SessionState.ONLINE) {
            throw new IllegalStateException("This session is already connecting or online.");
        }

        ServerAddress address = ServerAddress.parse(profile.getServerAddress());
        MinecraftProtocol protocol = new MinecraftProtocol(profile.getUsername());
        ClientSession session = ClientNetworkSessionFactory.factory()
                .setAddress(address.host, address.port)
                .setProtocol(protocol)
                .create();
        Connection connection = new Connection(sessionId, session);

        if (connections.putIfAbsent(sessionId, connection) != null) {
            throw new IllegalStateException("A network connection already exists for this session.");
        }

        try {
            sessionManager.updateState(sessionId, SessionState.CONNECTING);
            connection.stage = "TCP_CONNECTING";
            appendMessage(sessionId, "[MSM] Connecting to " + address.display + " as "
                    + profile.getUsername() + " (offline-mode).");

            session.addListener(new SessionAdapter() {
                @Override
                public void connected(ConnectedEvent event) {
                    connection.stage = "LOGIN_HANDSHAKE";
                    appendMessage(sessionId, "[MSM] TCP connected. Starting Minecraft login.");
                }

                @Override
                public void packetReceived(Session source, Packet packet) {
                    if (packet instanceof ClientboundLoginFinishedPacket) {
                        connection.loginAccepted.set(true);
                        connection.stage = "CONFIGURATION";
                        appendMessage(sessionId, "[MSM] Login accepted. Waiting for Join Game.");
                    } else if (packet instanceof ClientboundLoginPacket) {
                        connection.joined.set(true);
                        cancelTimeout(connection.handshakeTimeout);
                        connection.stage = "IN_GAME";
                        markOnline(connection);
                        appendMessage(sessionId, "[MSM] Join Game received. Secondary player is ONLINE.");
                        SessionLifecycleListener listener = lifecycleListener;
                        if (listener != null) listener.onJoinedGame(sessionId);
                    } else if (packet instanceof ClientboundSystemChatPacket) {
                        ClientboundSystemChatPacket chat = (ClientboundSystemChatPacket) packet;
                        appendMessage(sessionId, plain(chat.getContent()));
                    } else if (packet instanceof ClientboundPlayerChatPacket) {
                        ClientboundPlayerChatPacket chat = (ClientboundPlayerChatPacket) packet;
                        Component content = chat.getUnsignedContent();
                        String message = content == null ? chat.getContent() : plain(content);
                        appendMessage(sessionId, plain(chat.getName()) + ": " + message);
                    } else if (packet instanceof ClientboundHelloPacket) {
                        ClientboundHelloPacket hello = (ClientboundHelloPacket) packet;
                        if (hello.isShouldAuthenticate()) {
                            connection.stage = "ONLINE_AUTH_REQUIRED";
                            String reason = "MSM OFFLINE_LOCAL cannot authenticate to online-mode servers.";
                            appendMessage(sessionId,
                                    "[MSM] Server requires online-mode authentication. Disconnecting this offline-only session.");
                            try {
                                source.disconnect(reason);
                            } catch (Throwable ignored) {
                                // The connection state is finalized below even if the transport is already closed.
                            } finally {
                                finishConnection(connection, reason);
                            }
                        } else {
                            connection.stage = "ENCRYPTION_HANDSHAKE";
                            appendMessage(sessionId,
                                    "[MSM] Server requested encryption without online-mode authentication.");
                        }
                    } else if (packet instanceof ClientboundLoginDisconnectPacket) {
                        connection.stage = "LOGIN_REJECTED";
                        appendMessage(sessionId, "[MSM] Login rejected: "
                                + plain(((ClientboundLoginDisconnectPacket) packet).getReason()));
                    } else if (packet instanceof ClientboundDisconnectPacket) {
                        connection.stage = "SERVER_DISCONNECT";
                        appendMessage(sessionId, "[MSM] Server disconnected: "
                                + plain(((ClientboundDisconnectPacket) packet).getReason()));
                    }
                }

                @Override
                public void disconnected(DisconnectedEvent event) {
                    finishConnection(connection, plain(event.getReason()));
                }
            });

            connection.handshakeTimeout = TIMEOUTS.schedule(() -> {
                if (connection.joined.get() || connection.finished.get()) return;
                String reason = "Timed out waiting for Join Game.";
                try {
                    connection.session.disconnect(reason);
                } catch (Throwable ignored) {
                } finally {
                    finishConnection(connection, reason);
                }
            }, HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            session.connect(false);
        } catch (Throwable throwable) {
            finishConnection(connection, safeMessage(throwable));
            throw new IllegalStateException(
                    "Could not start the secondary connection: " + safeMessage(throwable), throwable);
        }
    }

    public void disconnect(long sessionId) {
        Connection connection = connections.get(sessionId);
        if (connection == null) {
            Optional<SessionManager.Session> managed = sessionManager.find(sessionId);
            if (managed.isPresent()) {
                SessionState state = managed.get().getState();
                if (state == SessionState.CONNECTING || state == SessionState.ONLINE) {
                    sessionManager.updateState(sessionId, SessionState.DISCONNECTED);
                }
            }
            return;
        }

        connection.stopping.set(true);
        try {
            connection.session.disconnect("Stopped from MSM");
        } catch (Throwable ignored) {
            // The network may already be closed; finishConnection handles the state once.
        } finally {
            finishConnection(connection, "Disconnected from MSM.");
        }
    }

    public void ping(String serverAddress, PingCallback callback) {
        if (callback == null) throw new IllegalArgumentException("Ping callback is required");
        ServerAddress address = ServerAddress.parse(serverAddress);
        String endpoint = address.display;
        MinecraftProtocol protocol = new MinecraftProtocol();
        ClientSession session = ClientNetworkSessionFactory.factory()
                .setAddress(address.host, address.port)
                .setProtocol(protocol)
                .create();
        PingAttempt attempt = new PingAttempt(endpoint, session, callback);
        if (pingAttempts.putIfAbsent(endpoint, attempt) != null) {
            throw new IllegalStateException("A ping is already running for " + endpoint + ".");
        }

        session.addListener(new SessionAdapter() {
            private String serverDetails = "status response received";

            @Override
            public void packetReceived(Session source, Packet packet) {
                if (packet instanceof ClientboundStatusResponsePacket) {
                    ServerStatusInfo info = ((ClientboundStatusResponsePacket) packet).parseInfo();
                    VersionInfo version = info.getVersionInfo();
                    PlayerInfo players = info.getPlayerInfo();
                    serverDetails = version == null ? "unknown server version"
                            : version.getVersionName() + " (protocol " + version.getProtocolVersion() + ")";
                    if (players != null) {
                        serverDetails += ", " + players.getOnlinePlayers() + "/"
                                + players.getMaxPlayers() + " players";
                    }
                    String motd = plain(info.getDescription());
                    if (!motd.isEmpty()) serverDetails += ", " + motd.replace('\n', ' ');
                } else if (packet instanceof ClientboundPongResponsePacket) {
                    long pingTime = Math.max(0L, System.currentTimeMillis()
                            - ((ClientboundPongResponsePacket) packet).getPingTime());
                    completePing(attempt, "PING OK | " + endpoint + " | "
                            + serverDetails + " | " + pingTime + " ms");
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                String reason = plain(event.getReason());
                completePing(attempt, "PING FAILED | " + endpoint + " | "
                        + (reason.isEmpty() ? "No status response received." : reason));
            }
        });

        try {
            attempt.timeout = TIMEOUTS.schedule(
                    () -> completePing(attempt, "PING FAILED | " + endpoint
                            + " | Timed out waiting for status response."),
                    PING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            session.connect(false);
        } catch (Throwable throwable) {
            completePing(attempt, "PING FAILED | " + endpoint + " | " + safeMessage(throwable));
        }
    }

    private void completePing(PingAttempt attempt, String result) {
        if (!attempt.finished.compareAndSet(false, true)) return;
        cancelTimeout(attempt.timeout);
        pingAttempts.remove(attempt.endpoint, attempt);
        try {
            attempt.session.disconnect("Status ping completed.");
        } catch (Throwable ignored) {
        }
        try {
            attempt.callback.onComplete(result);
        } catch (Throwable ignored) {
        }
        LOGGER.info(result);
    }

    public void sendChat(long sessionId, String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) return;
        if (text.length() > 256) {
            throw new IllegalArgumentException("Chat messages must be 256 characters or fewer.");
        }

        Connection connection = connections.get(sessionId);
        if (connection == null || !connection.session.isConnected()) {
            throw new IllegalStateException("The secondary session is not connected.");
        }
        SessionManager.Session managed = requireSession(sessionId);
        if (managed.getState() != SessionState.ONLINE || !connection.joined.get()) {
            throw new IllegalStateException("Wait until the secondary player has joined the server.");
        }

        if (text.startsWith("/")) {
            String command = text.substring(1).trim();
            if (command.isEmpty()) return;
            connection.session.send(new ServerboundChatCommandPacket(command));
        } else {
            // Offline profiles cannot sign chat; secure-chat-enforced servers may reject unsigned messages.
            connection.session.send(new ServerboundChatPacket(
                    text,
                    System.currentTimeMillis(),
                    ThreadLocalRandom.current().nextLong(),
                    null,
                    0,
                    new BitSet(20),
                    0));
        }
        appendMessage(sessionId, "You: " + text);
    }

    public void forgetSession(long sessionId) {
        if (connections.containsKey(sessionId)) {
            throw new IllegalStateException("Disconnect the secondary session before removing its data.");
        }
        chatBuffers.remove(sessionId);
    }

    public List<String> getMessages(long sessionId) {
        ChatBuffer buffer = chatBuffers.get(sessionId);
        return buffer == null ? Collections.emptyList() : buffer.snapshot();
    }

    public boolean isOnline(long sessionId) {
        Optional<SessionManager.Session> managed = sessionManager.find(sessionId);
        return managed.isPresent() && managed.get().getState() == SessionState.ONLINE;
    }

    public SessionState getState(long sessionId) {
        return requireSession(sessionId).getState();
    }

    private SessionManager.Session requireSession(long sessionId) {
        Optional<SessionManager.Session> managed = sessionManager.find(sessionId);
        if (!managed.isPresent()) throw new IllegalArgumentException("Unknown MSM session: " + sessionId);
        return managed.get();
    }

    private void markOnline(Connection connection) {
        Optional<SessionManager.Session> managed = sessionManager.find(connection.sessionId);
        if (managed.isPresent() && managed.get().getState() == SessionState.CONNECTING) {
            sessionManager.updateState(connection.sessionId, SessionState.ONLINE);
        }
    }

    private void finishConnection(Connection connection, String reason) {
        if (!connection.finished.compareAndSet(false, true)) return;
        cancelTimeout(connection.handshakeTimeout);
        connections.remove(connection.sessionId, connection);

        Optional<SessionManager.Session> managed = sessionManager.find(connection.sessionId);
        if (managed.isPresent()) {
            SessionState state = managed.get().getState();
            if (state == SessionState.CONNECTING) {
                sessionManager.updateState(connection.sessionId,
                        connection.stopping.get() ? SessionState.DISCONNECTED : SessionState.FAILED);
            } else if (state == SessionState.ONLINE) {
                sessionManager.updateState(connection.sessionId, SessionState.DISCONNECTED);
            }
        }

        String detail = reason == null || reason.trim().isEmpty() ? "Connection closed." : reason.trim();
        appendMessage(connection.sessionId, "[MSM] Disconnected during " + connection.stage
                + ". loginAccepted=" + connection.loginAccepted.get()
                + ", joinedGame=" + connection.joined.get() + ". Reason: " + detail);
        SessionLifecycleListener listener = lifecycleListener;
        if (listener != null) {
            listener.onDisconnected(connection.sessionId, connection.stopping.get(), detail);
        }
    }

    private void appendMessage(long sessionId, String message) {
        String entry = message == null ? "" : message;
        LOGGER.info("[session {}] {}", sessionId, entry);
        chatBuffers.computeIfAbsent(sessionId, ignored -> new ChatBuffer()).add(entry);
    }

    private static void cancelTimeout(ScheduledFuture<?> timeout) {
        if (timeout != null) timeout.cancel(false);
    }

    private static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static final class PingAttempt {
        private final String endpoint;
        private final ClientSession session;
        private final PingCallback callback;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> timeout;

        private PingAttempt(String endpoint, ClientSession session, PingCallback callback) {
            this.endpoint = endpoint;
            this.session = session;
            this.callback = callback;
        }
    }

    private static final class Connection {
        private final long sessionId;
        private final ClientSession session;
        private final AtomicBoolean joined = new AtomicBoolean(false);
        private final AtomicBoolean loginAccepted = new AtomicBoolean(false);
        private final AtomicBoolean stopping = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> handshakeTimeout;
        private volatile String stage = "CREATED";

        private Connection(long sessionId, ClientSession session) {
            this.sessionId = sessionId;
            this.session = session;
        }
    }

    private static final class ChatBuffer {
        private static final int MAX_MESSAGES = 200;
        private final List<String> messages = new ArrayList<>();

        private synchronized void add(String message) {
            if (messages.size() == MAX_MESSAGES) messages.remove(0);
            messages.add(message);
        }

        private synchronized List<String> snapshot() {
            return new ArrayList<>(messages);
        }
    }

    private static final class ServerAddress {
        private final String host;
        private final int port;
        private final String display;

        private ServerAddress(String host, int port) {
            this.host = host;
            this.port = port;
            this.display = host + ":" + port;
        }

        private static ServerAddress parse(String value) {
            String address = value == null ? "" : value.trim();
            if (address.isEmpty() || address.contains("://")
                    || address.contains("/") || address.contains(" ")) {
                throw new IllegalArgumentException("Enter a server hostname or IP, optionally followed by :port.");
            }

            String host = address;
            int port = DEFAULT_PORT;
            if (address.startsWith("[")) {
                int closingBracket = address.indexOf(']');
                if (closingBracket < 2) throw new IllegalArgumentException("Invalid bracketed IPv6 server address.");
                host = address.substring(1, closingBracket);
                String suffix = address.substring(closingBracket + 1);
                if (!suffix.isEmpty()) {
                    if (!suffix.startsWith(":")) throw new IllegalArgumentException("Invalid server port suffix.");
                    port = parsePort(suffix.substring(1));
                }
            } else {
                int firstColon = address.indexOf(':');
                int lastColon = address.lastIndexOf(':');
                if (firstColon >= 0 && firstColon == lastColon) {
                    host = address.substring(0, firstColon);
                    port = parsePort(address.substring(firstColon + 1));
                }
            }

            if (host.trim().isEmpty()) throw new IllegalArgumentException("Server hostname is empty.");
            return new ServerAddress(host, port);
        }

        private static int parsePort(String value) {
            try {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65535) throw new NumberFormatException();
                return port;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Server port must be between 1 and 65535.");
            }
        }
    }
}
