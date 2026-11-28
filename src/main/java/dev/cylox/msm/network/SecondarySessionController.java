package dev.cylox.msm.network;

import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.data.SubProtocol;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerDisconnectPacket;
import com.github.steveice10.mc.protocol.packet.login.server.EncryptionRequestPacket;
import com.github.steveice10.mc.protocol.packet.login.server.LoginDisconnectPacket;
import com.github.steveice10.mc.protocol.packet.login.server.LoginSuccessPacket;
import com.github.steveice10.mc.protocol.packet.status.server.StatusPongPacket;
import com.github.steveice10.mc.protocol.packet.status.server.StatusResponsePacket;
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
import dev.cylox.msm.config.LocalAddressCatalog;
import dev.cylox.msm.config.ProxyProfile;
import dev.cylox.msm.config.ProxyProfileStore;
import dev.cylox.msm.config.SessionNetworkSettings;
import dev.cylox.msm.config.SessionNetworkStore;
import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;
import dev.cylox.msm.session.SessionManager;
import dev.cylox.msm.session.SessionState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns protocol-only secondary Java sessions.
 *
 * This first implementation intentionally supports offline-mode Java servers only.
 * It does not impersonate Microsoft authentication or translate other protocol versions.
 */
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
    private final SessionNetworkStore networkStore;
    private final ProxyProfileStore proxyProfileStore;
    private volatile SessionLifecycleListener lifecycleListener;
    private final ConcurrentMap<Long, Connection> connections =
            new ConcurrentHashMap<Long, Connection>();
    private final ConcurrentMap<Long, ChatBuffer> chatBuffers =
            new ConcurrentHashMap<Long, ChatBuffer>();
    private final ConcurrentMap<String, PingAttempt> pingAttempts =
            new ConcurrentHashMap<String, PingAttempt>();

    public SecondarySessionController(SessionManager sessionManager) {
        this(sessionManager, new SessionNetworkStore(
                FabricLoader.getInstance().getConfigDir().resolve("msm-network.properties")));
    }

    public SecondarySessionController(SessionManager sessionManager, SessionNetworkStore networkStore) {
        if (sessionManager == null || networkStore == null) {
            throw new IllegalArgumentException("Session manager and network settings store are required");
        }
        this.sessionManager = sessionManager;
        this.networkStore = networkStore;
        this.proxyProfileStore = new ProxyProfileStore(
                FabricLoader.getInstance().getConfigDir().resolve("msm-proxies.properties"));
    }

    public SessionManager getSessionManager() {
        return this.sessionManager;
    }

    public SessionNetworkStore getNetworkStore() {
        return this.networkStore;
    }

    public ProxyProfileStore getProxyProfileStore() {
        return this.proxyProfileStore;
    }

    /** Returns the source IP assigned to this session's live TCP socket, or an empty string. */
    public String getLocalAddress(long sessionId) {
        Connection connection = this.connections.get(sessionId);
        if (connection == null) return "";
        SocketAddress local = connection.client.getSession().getLocalAddress();
        if (local instanceof InetSocketAddress) {
            InetAddress address = ((InetSocketAddress) local).getAddress();
            return address == null ? "" : address.getHostAddress();
        }
        return local == null ? "" : local.toString();
    }

    private InetAddress resolveBindAddress(SessionProfile profile) {
        try {
            SessionNetworkSettings settings = this.networkStore.load(
                    profile.getUsername(), profile.getAuthMode());
            return settings.isAutomatic()
                    ? null : LocalAddressCatalog.resolveAvailableAddress(settings.getOutgoingAddress());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load or validate this account's outgoing IP: " + exception.getMessage(),
                    exception);
        }
    }

    private ProxyProfile resolveProxyProfile(SessionProfile profile) {
        try {
            SessionNetworkSettings settings = this.networkStore.load(
                    profile.getUsername(), profile.getAuthMode());
            String selectedId = settings.getProxyProfileId();
            if (selectedId.isEmpty()) return null;
            for (ProxyProfile proxy : this.proxyProfileStore.load()) {
                if (proxy.getId().equals(selectedId)) return proxy;
            }
            throw new IllegalStateException("The selected proxy profile no longer exists. Choose another proxy or Direct Connection.");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load this account's proxy profile: "
                    + exception.getMessage(), exception);
        }
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
            String message =
                    "Microsoft sign-in is not implemented yet. Select OFFLINE only if the server permits offline-mode accounts.";
            appendMessage(sessionId, "[MSM] " + message);
            throw new IllegalStateException(message);
        }
        if (managed.getState() == SessionState.CONNECTING || managed.getState() == SessionState.ONLINE) {
            throw new IllegalStateException("This session is already connecting or online.");
        }

        ServerAddress address = ServerAddress.parse(profile.getServerAddress());
        InetAddress bindAddress = resolveBindAddress(profile);
        ProxyProfile proxyProfile = resolveProxyProfile(profile);
        MinecraftProtocol protocol = new MsmMinecraftProtocol(profile.getUsername());
        TcpSessionFactory factory = new TcpSessionFactory();
        if (bindAddress != null || proxyProfile != null) {
            factory = new TcpSessionFactory() {
                @Override
                public Session createClientSession(Client client) {
                    return new BoundTcpClientSession(client, bindAddress, proxyProfile);
                }
            };
        }
        Client client = new Client(address.host, address.port, protocol, factory);
        final Connection connection = new Connection(sessionId, client);

        Connection previous = connections.putIfAbsent(sessionId, connection);
        if (previous != null) {
            throw new IllegalStateException("A network connection already exists for this session.");
        }

        try {
            sessionManager.updateState(sessionId, SessionState.CONNECTING);
            connection.stage = "TCP_CONNECTING";
            appendMessage(sessionId, "[MSM] Starting connection to " + address.display + " as "
                    + profile.getUsername() + " (offline-mode).");

            client.getSession().addListener(new SessionAdapter() {
                @Override
                public void connected(ConnectedEvent event) {
                    connection.stage = "LOGIN_HANDSHAKE";
                    appendMessage(sessionId, "[MSM] TCP connected. Sending Minecraft login handshake.");
                }

                @Override
                public void packetReceived(PacketReceivedEvent event) {
                    Object packet = event.getPacket();
                    if (packet instanceof LoginSuccessPacket) {
                        connection.stage = "WAITING_FOR_JOIN_GAME";
                        connection.loginAccepted.set(true);
                        appendMessage(sessionId,
                                "[MSM] Login accepted by server. Waiting for Join Game packet.");
                    } else if (packet instanceof ServerJoinGamePacket) {
                        connection.stage = "IN_GAME";
                        connection.joined.set(true);
                        cancelTimeout(connection.handshakeTimeout);
                        markOnline(connection);
                        appendMessage(sessionId, "[MSM] Join Game received. Secondary player is ONLINE.");
                        SessionLifecycleListener listener = lifecycleListener;
                        if (listener != null) listener.onJoinedGame(sessionId);
                    } else if (packet instanceof ServerChatPacket) {
                        ServerChatPacket chatPacket = (ServerChatPacket) packet;
                        appendMessage(sessionId, chatPacket.getMessage().getFullText());
                    } else if (packet instanceof EncryptionRequestPacket) {
                        connection.stage = "ONLINE_AUTH_REQUIRED";
                        String reason = "MSM OFFLINE_LOCAL cannot authenticate to online-mode servers.";
                        appendMessage(sessionId,
                                "[MSM] Server requested online-mode authentication. Disconnecting this offline-only session.");
                        try {
                            connection.client.getSession().disconnect(reason, false);
                        } catch (Throwable ignored) {
                            // The connection state is finalized below even if the transport is already closed.
                        } finally {
                            finishConnection(connection, reason);
                        }
                    } else if (packet instanceof LoginDisconnectPacket) {
                        connection.stage = "LOGIN_REJECTED";
                        LoginDisconnectPacket disconnect = (LoginDisconnectPacket) packet;
                        appendMessage(sessionId, "[MSM] Login rejected: "
                                + disconnect.getReason().getFullText());
                    } else if (packet instanceof ServerDisconnectPacket) {
                        connection.stage = "SERVER_DISCONNECT";
                        ServerDisconnectPacket disconnect = (ServerDisconnectPacket) packet;
                        appendMessage(sessionId, "[MSM] Server disconnected: "
                                + disconnect.getReason().getFullText());
                    }
                    // MCProtocolLib's default ClientListener handles keep-alive replies.
                }

                @Override
                public void disconnected(DisconnectedEvent event) {
                    finishConnection(connection, event.getReason());
                }
            });

            connection.handshakeTimeout = TIMEOUTS.schedule(() -> {
                if (connection.joined.get() || connection.finished.get()) return;
                String reason = "Timed out waiting for Join Game.";
                try {
                    connection.client.getSession().disconnect(reason, false);
                } catch (Throwable ignored) {
                } finally {
                    finishConnection(connection, reason);
                }
            }, HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

    /**
     * Performs a Minecraft status ping without attempting to log in.
     * This can verify DNS/TCP reachability and report the server's advertised version.
     */
    public void ping(String serverAddress, final PingCallback callback) {
        if (callback == null) throw new IllegalArgumentException("Ping callback is required");

        final ServerAddress address = ServerAddress.parse(serverAddress);
        final String endpoint = address.display;
        final MinecraftProtocol protocol = new MinecraftProtocol(SubProtocol.STATUS);
        final Client client = new Client(address.host, address.port, protocol, new TcpSessionFactory());
        final PingAttempt attempt = new PingAttempt(endpoint, client, callback);

        if (this.pingAttempts.putIfAbsent(endpoint, attempt) != null) {
            throw new IllegalStateException("A ping is already running for " + endpoint + ".");
        }

        client.getSession().addListener(new SessionAdapter() {
            private String serverDetails = "status response received";

            @Override
            public void packetReceived(PacketReceivedEvent event) {
                Object packet = event.getPacket();
                if (packet instanceof StatusResponsePacket) {
                    StatusResponsePacket response = (StatusResponsePacket) packet;
                    this.serverDetails = response.getInfo().getVersionInfo().getVersionName()
                            + " (protocol " + response.getInfo().getVersionInfo().getProtocolVersion() + "), "
                            + response.getInfo().getPlayerInfo().getOnlinePlayers() + "/"
                            + response.getInfo().getPlayerInfo().getMaxPlayers() + " players";
                    String motd = response.getInfo().getDescription().getFullText();
                    if (motd != null && !motd.trim().isEmpty()) {
                        this.serverDetails += ", " + motd.trim().replace('\n', ' ');
                    }
                } else if (packet instanceof StatusPongPacket) {
                    long pingTime = Math.max(0L,
                            System.currentTimeMillis() - ((StatusPongPacket) packet).getPingTime());
                    completePing(attempt, "PING OK | " + endpoint + " | "
                            + this.serverDetails + " | " + pingTime + " ms");
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                String reason = event.getReason();
                completePing(attempt, "PING FAILED | " + endpoint + " | "
                        + (reason == null || reason.trim().isEmpty()
                        ? "No status response received." : reason.trim()));
            }
        });

        try {
            attempt.timeout = TIMEOUTS.schedule(
                    () -> completePing(attempt, "PING FAILED | " + endpoint
                            + " | Timed out waiting for status response."),
                    PING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            client.getSession().connect(false);
        } catch (Throwable throwable) {
            completePing(attempt, "PING FAILED | " + endpoint + " | " + safeMessage(throwable));
        }
    }

    private void completePing(PingAttempt attempt, String result) {
        if (!attempt.finished.compareAndSet(false, true)) return;
        cancelTimeout(attempt.timeout);
        try {
            attempt.client.getSession().disconnect("Status ping completed.", false);
        } catch (Throwable ignored) {
        }
        LOGGER.info(result);
        this.pingAttempts.remove(attempt.endpoint, attempt);
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
        if (!managed.isPresent()) return;
        if (managed.get().getState() == SessionState.CONNECTING) {
            sessionManager.updateState(connection.sessionId, SessionState.ONLINE);
        }
    }

    private void finishConnection(Connection connection, String reason) {
        if (!connection.finished.compareAndSet(false, true)) return;
        cancelTimeout(connection.handshakeTimeout);

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
                + ". loginAccepted=" + connection.loginAccepted.get()
                + ", joinedGame=" + connection.joined.get()
                + ". Reason: " + detail);
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

    private static void cancelTimeout(ScheduledFuture<?> timeout) {
        if (timeout != null) timeout.cancel(false);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static final class PingAttempt {
        private final String endpoint;
        private final Client client;
        private final PingCallback callback;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> timeout;

        private PingAttempt(String endpoint, Client client, PingCallback callback) {
            this.endpoint = endpoint;
            this.client = client;
            this.callback = callback;
        }
    }

    private static final class Connection {
        private final long sessionId;
        private final Client client;
        private final AtomicBoolean joined = new AtomicBoolean(false);
        private final AtomicBoolean loginAccepted = new AtomicBoolean(false);
        private volatile String stage = "CREATED";
        private final AtomicBoolean stopping = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> handshakeTimeout;

        private Connection(long sessionId, Client client) {
            this.sessionId = sessionId;
            this.client = client;
        }
    }

    private static final class ChatBuffer {
        private static final int MAX_MESSAGES = 200;
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
