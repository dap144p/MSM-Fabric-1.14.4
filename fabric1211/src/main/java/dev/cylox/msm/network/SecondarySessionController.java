package dev.cylox.msm.network;

import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ping.clientbound.ClientboundPongResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.status.clientbound.ClientboundStatusResponsePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns protocol-only secondary sessions for the Minecraft 1.21.11 protocol.
 * The main Minecraft client continues to own rendering and world interaction.
 */
public final class SecondarySessionController {
    private static final Logger LOGGER = LoggerFactory.getLogger("MSM-Secondary");
    private static final int DEFAULT_PORT = 25565;
    private static final PlainTextComponentSerializer PLAIN_TEXT =
            PlainTextComponentSerializer.plainText();

    private final SessionManager sessionManager;
    private volatile SessionLifecycleListener lifecycleListener;
    private final ConcurrentMap<Long, Connection> connections =
            new ConcurrentHashMap<Long, Connection>();
    private final ConcurrentMap<Long, ChatBuffer> chatBuffers =
            new ConcurrentHashMap<Long, ChatBuffer>();
    private final ConcurrentMap<String, PingAttempt> pingAttempts =
            new ConcurrentHashMap<String, PingAttempt>();

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
        appendMessage(sessionId, message == null ? "" : message);
    }

    public interface SessionLifecycleListener {
        void onJoinedGame(long sessionId);
        void onDisconnected(long sessionId, boolean manuallyStopped, String reason);
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
        ClientSession client = createClient(address, protocol);
        Connection connection = new Connection(sessionId, client);

        Connection previous = connections.putIfAbsent(sessionId, connection);
        if (previous != null) {
            throw new IllegalStateException("A network connection already exists for this session.");
        }

        try {
            sessionManager.updateState(sessionId, SessionState.CONNECTING);
            connection.stage = "TCP_CONNECTING";
            appendMessage(sessionId, "[MSM] Starting connection to " + address.display
                    + " as " + profile.getUsername() + " (offline-mode).");

            client.addListener(new SessionAdapter() {
                @Override
                public void connected(ConnectedEvent event) {
                    connection.stage = "LOGIN_HANDSHAKE";
                    appendMessage(sessionId, "[MSM] TCP connected. Sending Minecraft login handshake.");
                }

                @Override
                public void packetReceived(Session session, Packet packet) {
                    if (packet instanceof ClientboundLoginPacket) {
                        connection.stage = "IN_GAME";
                        connection.joined.set(true);
                        markOnline(connection);
                        appendMessage(sessionId, "[MSM] Login and configuration completed. Secondary player is ONLINE.");
                        SessionLifecycleListener listener = lifecycleListener;
                        if (listener != null) listener.onJoinedGame(sessionId);
                    } else if (packet instanceof ClientboundSystemChatPacket) {
                        Component content = ((ClientboundSystemChatPacket) packet).getContent();
                        appendMessage(sessionId, plainText(content));
                    } else if (packet instanceof ClientboundPlayerChatPacket) {
                        appendMessage(sessionId, ((ClientboundPlayerChatPacket) packet).getContent());
                    } else if (packet instanceof ClientboundLoginDisconnectPacket) {
                        connection.stage = "LOGIN_REJECTED";
                        appendMessage(sessionId, "[MSM] Login rejected: "
                                + plainText(((ClientboundLoginDisconnectPacket) packet).getReason()));
                    } else if (packet instanceof ClientboundDisconnectPacket) {
                        connection.stage = "SERVER_DISCONNECT";
                        appendMessage(sessionId, "[MSM] Server disconnected: "
                                + plainText(((ClientboundDisconnectPacket) packet).getReason()));
                    }
                    // MCProtocolLib's default listener handles LOGIN, CONFIGURATION, keep-alive, and status transitions.
                }

                @Override
                public void disconnected(DisconnectedEvent event) {
                    Throwable cause = event.getCause();
                    String reason = event.getReason() == null
                            ? safeMessage(cause) : plainText(event.getReason());
                    if (cause != null && cause.getClass().getSimpleName().contains("Encryption")) {
                        reason = "Server requires online-mode authentication; OFFLINE_LOCAL cannot log in.";
                    }
                    finishConnection(connection, reason);
                }
            });

            client.connect(false);
        } catch (Throwable throwable) {
            finishConnection(connection, safeMessage(throwable));
            throw new IllegalStateException(
                    "Could not start the secondary connection: " + safeMessage(throwable), throwable);
        }
    }

    private ClientSession createClient(ServerAddress address, MinecraftProtocol protocol) {
        return ClientNetworkSessionFactory.factory()
                .setRemoteSocketAddress(InetSocketAddress.createUnresolved(address.host, address.port))
                .setProtocol(protocol)
                .create();
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
            connection.client.disconnect(Component.text("Stopped from MSM"));
        } catch (Throwable ignored) {
            // The state is finalized below if the transport has already failed.
        } finally {
            finishConnection(connection, "Disconnected from MSM.");
        }
    }

    public void ping(String serverAddress, final PingCallback callback) {
        if (callback == null) throw new IllegalArgumentException("Ping callback is required");

        ServerAddress address = ServerAddress.parse(serverAddress);
        String endpoint = address.display;
        ClientSession client = createClient(address, new MinecraftProtocol());
        PingAttempt attempt = new PingAttempt(endpoint, client, callback);

        if (pingAttempts.putIfAbsent(endpoint, attempt) != null) {
            throw new IllegalStateException("A ping is already running for " + endpoint + ".");
        }

        client.addListener(new SessionAdapter() {
            private String serverDetails = "status response received";

            @Override
            public void packetReceived(Session session, Packet packet) {
                if (packet instanceof ClientboundStatusResponsePacket) {
                    ServerStatusInfo info = ((ClientboundStatusResponsePacket) packet).parseInfo();
                    if (info.getVersionInfo() != null) {
                        serverDetails = info.getVersionInfo().getVersionName()
                                + " (protocol " + info.getVersionInfo().getProtocolVersion() + ")";
                    }
                    if (info.getPlayerInfo() != null) {
                        serverDetails += ", " + info.getPlayerInfo().getOnlinePlayers()
                                + "/" + info.getPlayerInfo().getMaxPlayers() + " players";
                    }
                    String motd = plainText(info.getDescription());
                    if (!motd.trim().isEmpty()) serverDetails += ", " + motd.trim().replace('\n', ' ');
                } else if (packet instanceof ClientboundPongResponsePacket) {
                    long pingTime = Math.max(0L,
                            System.currentTimeMillis() - ((ClientboundPongResponsePacket) packet).getPingTime());
                    completePing(attempt, "PING OK | " + endpoint + " | "
                            + serverDetails + " | " + pingTime + " ms");
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                if (!attempt.finished.get()) {
                    String reason = event.getReason() == null
                            ? "No status response received." : plainText(event.getReason());
                    completePing(attempt, "PING FAILED | " + endpoint + " | " + reason);
                }
            }
        });

        try {
            client.connect(false);
        } catch (Throwable throwable) {
            completePing(attempt, "PING FAILED | " + endpoint + " | " + safeMessage(throwable));
        }
    }

    private void completePing(PingAttempt attempt, String result) {
        if (!attempt.finished.compareAndSet(false, true)) return;
        LOGGER.info(result);
        pingAttempts.remove(attempt.endpoint, attempt);
        try {
            attempt.callback.onComplete(result);
        } catch (Throwable ignored) {
            // A UI callback must not interrupt the network listener.
        }
    }

    public interface PingCallback {
        void onComplete(String result);
    }

    public void sendChat(long sessionId, String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) return;
        if (text.length() > 256) {
            throw new IllegalArgumentException("Chat messages must be 256 characters or fewer.");
        }

        Connection connection = connections.get(sessionId);
        if (connection == null || !connection.client.isConnected()) {
            throw new IllegalStateException("The secondary session is not connected.");
        }

        SessionManager.Session managed = requireSession(sessionId);
        if (managed.getState() != SessionState.ONLINE || !connection.joined.get()) {
            throw new IllegalStateException("Wait until the secondary player has joined the server.");
        }

        connection.client.send(new ServerboundChatPacket(
                text, System.currentTimeMillis(), 0L, null, 0, new BitSet(), 0));
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
        return buffer == null ? Collections.<String>emptyList() : buffer.snapshot();
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
        if (!managed.isPresent()) {
            throw new IllegalArgumentException("Unknown MSM session: " + sessionId);
        }
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

        connections.remove(connection.sessionId, connection);
        Optional<SessionManager.Session> managed = sessionManager.find(connection.sessionId);
        if (managed.isPresent()) {
            SessionState current = managed.get().getState();
            if (current == SessionState.CONNECTING) {
                sessionManager.updateState(connection.sessionId,
                        connection.stopping.get() ? SessionState.DISCONNECTED : SessionState.FAILED);
            } else if (current == SessionState.ONLINE) {
                sessionManager.updateState(connection.sessionId, SessionState.DISCONNECTED);
            }
        }

        String detail = reason == null || reason.trim().isEmpty() ? "Connection closed." : reason.trim();
        appendMessage(connection.sessionId, "[MSM] Disconnected during " + connection.stage
                + ". joinedGame=" + connection.joined.get() + ". Reason: " + detail);
        SessionLifecycleListener listener = lifecycleListener;
        if (listener != null) {
            listener.onDisconnected(connection.sessionId, connection.stopping.get(), detail);
        }
    }

    private void appendMessage(long sessionId, String message) {
        String entry = message == null ? "" : message;
        LOGGER.info("[session {}] {}", sessionId, entry);
        ChatBuffer buffer = chatBuffers.get(sessionId);
        if (buffer == null) {
            ChatBuffer created = new ChatBuffer();
            ChatBuffer existing = chatBuffers.putIfAbsent(sessionId, created);
            buffer = existing == null ? created : existing;
        }
        buffer.add(entry);
    }

    private static String plainText(Component component) {
        return component == null ? "" : PLAIN_TEXT.serialize(component);
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) return "Connection closed.";
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static final class PingAttempt {
        private final String endpoint;
        private final ClientSession client;
        private final PingCallback callback;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private PingAttempt(String endpoint, ClientSession client, PingCallback callback) {
            this.endpoint = endpoint;
            this.client = client;
            this.callback = callback;
        }
    }

    private static final class Connection {
        private final long sessionId;
        private final ClientSession client;
        private final AtomicBoolean joined = new AtomicBoolean(false);
        private volatile String stage = "CREATED";
        private final AtomicBoolean stopping = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private Connection(long sessionId, ClientSession client) {
            this.sessionId = sessionId;
            this.client = client;
        }
    }

    private static final class ChatBuffer {
        private static final int MAX_MESSAGES = 200;
        private final List<String> messages = new ArrayList<String>();

        private synchronized void add(String message) {
            if (messages.size() == MAX_MESSAGES) messages.remove(0);
            messages.add(message);
        }

        private synchronized List<String> snapshot() {
            return new ArrayList<String>(messages);
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
                if (closingBracket < 2) {
                    throw new IllegalArgumentException("Invalid bracketed IPv6 server address.");
                }
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
                // Unbracketed IPv6 literals retain the default port.
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
