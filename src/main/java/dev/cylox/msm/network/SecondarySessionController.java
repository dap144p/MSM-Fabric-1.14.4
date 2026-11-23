package dev.cylox.msm.network;

import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientChatPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerChatPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerJoinGamePacket;
import com.github.steveice10.packetlib.Client;
import com.github.steveice10.packetlib.Session;
import com.github.steveice10.packetlib.event.session.ConnectedEvent;
import com.github.steveice10.packetlib.event.session.DisconnectedEvent;
import com.github.steveice10.packetlib.event.session.PacketReceivedEvent;
import com.github.steveice10.packetlib.event.session.SessionAdapter;
import com.github.steveice10.packetlib.tcp.TcpSessionFactory;
import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns protocol-only secondary Java sessions.
 *
 * This first implementation intentionally supports offline-mode Java servers only.
 * It does not impersonate Microsoft authentication or translate other protocol versions.
 */
public final class SecondarySessionController {
    private static final int DEFAULT_PORT = 25565;
    private final SessionManager sessionManager;
    private final ConcurrentMap<Long, Connection> connections =
            new ConcurrentHashMap<Long, Connection>();
    private final ConcurrentMap<Long, ChatBuffer> chatBuffers =
            new ConcurrentHashMap<Long, ChatBuffer>();

    public SecondarySessionController(SessionManager sessionManager) {
        if (sessionManager == null) {
            throw new IllegalArgumentException("Session manager is required");
        }
        this.sessionManager = sessionManager;
    }

    public void connect(long sessionId) {
        SessionManager.Session managed = requireSession(sessionId);
        SessionProfile profile = managed.getProfile();

        if (profile.getAuthMode() != SessionAuthMode.OFFLINE_LOCAL) {
            throw new IllegalStateException(
                    "Microsoft sign-in is not implemented yet. Select OFFLINE only for an offline-mode server.");
        }
        if (managed.getState() == SessionState.CONNECTING || managed.getState() == SessionState.ONLINE) {
            throw new IllegalStateException("This session is already connecting or online.");
        }

        ServerAddress address = ServerAddress.parse(profile.getServerAddress());
        MinecraftProtocol protocol = new MinecraftProtocol(profile.getUsername());
        Client client = new Client(address.host, address.port, protocol, new TcpSessionFactory());
        final Connection connection = new Connection(sessionId, client);

        Connection previous = connections.putIfAbsent(sessionId, connection);
        if (previous != null) {
            throw new IllegalStateException("A network connection already exists for this session.");
        }

        try {
            sessionManager.updateState(sessionId, SessionState.CONNECTING);
            appendMessage(sessionId, "[MSM] Connecting to " + address.display + " as "
                    + profile.getUsername() + " (offline-mode).");

            client.getSession().addListener(new SessionAdapter() {
                @Override
                public void connected(ConnectedEvent event) {
                    appendMessage(sessionId, "[MSM] TCP connected; waiting for Minecraft Join Game.");
                }

                @Override
                public void packetReceived(PacketReceivedEvent event) {
                    Object packet = event.getPacket();
                    if (packet instanceof ServerJoinGamePacket) {
                        connection.joined.set(true);
                        markOnline(connection);
                        appendMessage(sessionId, "[MSM] Joined the server. Secondary session is online.");
                    } else if (packet instanceof ServerChatPacket) {
                        ServerChatPacket chatPacket = (ServerChatPacket) packet;
                        appendMessage(sessionId, chatPacket.getMessage().getFullText());
                    }
                    // MCProtocolLib's default ClientListener handles keep-alive replies.
                }

                @Override
                public void disconnected(DisconnectedEvent event) {
                    finishConnection(connection, event.getReason());
                }
            });

            client.getSession().connect(false);
        } catch (Throwable throwable) {
            finishConnection(connection, throwable.getMessage());
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
            connection.client.getSession().disconnect("Stopped from MSM", false);
        } catch (Throwable ignored) {
            // The state is still finalized below if the transport has already failed.
        } finally {
            finishConnection(connection, "Disconnected from MSM.");
        }
    }

    public void sendChat(long sessionId, String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) return;
        if (text.length() > 256) {
            throw new IllegalArgumentException("Chat messages must be 256 characters or fewer.");
        }

        Connection connection = connections.get(sessionId);
        if (connection == null || !connection.client.getSession().isConnected()) {
            throw new IllegalStateException("The secondary session is not connected.");
        }

        SessionManager.Session managed = requireSession(sessionId);
        if (managed.getState() != SessionState.ONLINE || !connection.joined.get()) {
            throw new IllegalStateException("Wait until the secondary player has joined the server.");
        }

        connection.client.getSession().send(new ClientChatPacket(text));
        appendMessage(sessionId, "You: " + text);
    }

    /**
     * Clears cached chat history after a profile slot is permanently removed.
     * Active network sessions must be disconnected before this is called.
     */
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

    private SessionManager.Session requireSession(long sessionId) {
        Optional<SessionManager.Session> managed = sessionManager.find(sessionId);
        if (!managed.isPresent()) {
            throw new IllegalArgumentException("Unknown MSM session: " + sessionId);
        }
        return managed.get();
    }

    private void markOnline(Connection connection) {
        Optional<SessionManager.Session> managed = sessionManager.find(connection.sessionId);
        if (!managed.isPresent()) return;
        if (managed.get().getState() == SessionState.CONNECTING) {
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
        appendMessage(connection.sessionId, "[MSM] " + detail);
    }

    private void appendMessage(long sessionId, String message) {
        ChatBuffer buffer = chatBuffers.get(sessionId);
        if (buffer == null) {
            ChatBuffer created = new ChatBuffer();
            ChatBuffer existing = chatBuffers.putIfAbsent(sessionId, created);
            buffer = existing == null ? created : existing;
        }
        buffer.add(message == null ? "" : message);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static final class Connection {
        private final long sessionId;
        private final Client client;
        private final AtomicBoolean joined = new AtomicBoolean(false);
        private final AtomicBoolean stopping = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private Connection(long sessionId, Client client) {
            this.sessionId = sessionId;
            this.client = client;
        }
    }

    private static final class ChatBuffer {
        private static final int MAX_MESSAGES = 60;
        private final List<String> messages = new ArrayList<String>();

        private synchronized void add(String message) {
            if (messages.size() == MAX_MESSAGES) {
                messages.remove(0);
            }
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
            if (address.isEmpty() || address.contains("://") || address.contains("/") || address.contains(" ")) {
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
                    if (!suffix.startsWith(":")) {
                        throw new IllegalArgumentException("Invalid server port suffix.");
                    }
                    port = parsePort(suffix.substring(1));
                }
            } else {
                int firstColon = address.indexOf(':');
                int lastColon = address.lastIndexOf(':');
                if (firstColon >= 0 && firstColon == lastColon) {
                    host = address.substring(0, firstColon);
                    port = parsePort(address.substring(firstColon + 1));
                }
                // An unbracketed address with multiple colons is treated as an IPv6 literal.
            }

            if (host.trim().isEmpty()) {
                throw new IllegalArgumentException("Server hostname is empty.");
            }
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
