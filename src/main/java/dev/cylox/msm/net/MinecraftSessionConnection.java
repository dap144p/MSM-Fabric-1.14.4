package dev.cylox.msm.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cylox.msm.profile.SessionProfile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

final class MinecraftSessionConnection implements Runnable {
    private static final int PROTOCOL_VERSION = 498;
    private static final int MAX_PACKET_SIZE = 2 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10000;

    interface Listener {
        void onOnline(MinecraftSessionConnection connection);
        void onMessage(String message);
        void onEnded(MinecraftSessionConnection connection, boolean wasOnline,
                     boolean manual, String reason);
    }

    enum Direction {
        FORWARD, BACKWARD, LEFT, RIGHT
    }

    private interface PacketWriter {
        void write(DataOutputStream output) throws IOException;
    }

    private final long sessionId;
    private final SessionProfile profile;
    private final Listener listener;
    private final Socket socket = new Socket();
    private final Object outputLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean finishNotified = new AtomicBoolean(false);
    private final AtomicBoolean jumping = new AtomicBoolean(false);

    private volatile DataInputStream input;
    private volatile DataOutputStream output;
    private volatile int compressionThreshold = -1;
    private volatile boolean manualDisconnect;
    private volatile boolean loginAccepted;
    private volatile boolean online;
    private volatile String endReason = "Connection closed.";

    private int entityId;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean hasPosition;
    private boolean sneaking;
    private boolean sprinting;

    MinecraftSessionConnection(long sessionId, SessionProfile profile, Listener listener) {
        this.sessionId = sessionId;
        this.profile = profile;
        this.listener = listener;
    }

    void start() {
        Thread thread = new Thread(this, "MSM-Session-" + this.sessionId);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        try {
            ServerAddress address = ServerAddress.parse(this.profile.getServerAddress());
            this.socket.setTcpNoDelay(true);
            this.socket.setKeepAlive(true);
            this.socket.connect(new InetSocketAddress(address.host, address.port), CONNECT_TIMEOUT_MS);
            if (!this.running.get()) return;

            this.input = new DataInputStream(this.socket.getInputStream());
            this.output = new DataOutputStream(this.socket.getOutputStream());

            sendPacket(0x00, packet -> {
                writeVarInt(packet, PROTOCOL_VERSION);
                writeString(packet, address.host);
                packet.writeShort(address.port);
                writeVarInt(packet, 2);
            });
            sendPacket(0x00, packet -> writeString(packet, this.profile.getUsername()));

            while (this.running.get()) {
                byte[] payload = readFrame();
                handlePacket(payload);
            }
        } catch (EOFException exception) {
            if (!this.manualDisconnect) this.endReason = "Server closed the connection.";
        } catch (Exception exception) {
            if (!this.manualDisconnect) this.endReason = readableError(exception);
        } finally {
            this.running.set(false);
            closeSocket();
            if (this.finishNotified.compareAndSet(false, true)) {
                this.listener.onEnded(this, this.online, this.manualDisconnect, this.endReason);
            }
        }
    }

    void disconnect() {
        this.manualDisconnect = true;
        this.endReason = "Disconnected by user.";
        this.running.set(false);
        closeSocket();
    }

    boolean isRunning() {
        return this.running.get();
    }

    synchronized String getPositionText() {
        if (!this.hasPosition) return "POSITION: waiting for server position";
        return String.format(Locale.ROOT, "POSITION: %.2f, %.2f, %.2f  |  YAW %.1f",
                this.x, this.y, this.z, this.yaw);
    }

    synchronized boolean isSneaking() {
        return this.sneaking;
    }

    synchronized boolean isSprinting() {
        return this.sprinting;
    }

    void sendChat(String message) throws IOException {
        String normalized = message == null ? "" : message.trim();
        if (normalized.isEmpty()) return;
        if (normalized.length() > 256) {
            throw new IOException("Chat message is limited to 256 characters.");
        }
        sendPacket(0x03, packet -> writeString(packet, normalized));
    }

    synchronized void move(Direction direction) throws IOException {
        requirePosition();
        double radians = Math.toRadians(this.yaw);
        double step = 0.5D;
        double dx = 0.0D;
        double dz = 0.0D;

        switch (direction) {
            case FORWARD:
                dx = -Math.sin(radians) * step;
                dz = Math.cos(radians) * step;
                break;
            case BACKWARD:
                dx = Math.sin(radians) * step;
                dz = -Math.cos(radians) * step;
                break;
            case LEFT:
                dx = -Math.cos(radians) * step;
                dz = -Math.sin(radians) * step;
                break;
            case RIGHT:
                dx = Math.cos(radians) * step;
                dz = Math.sin(radians) * step;
                break;
            default:
                throw new IllegalArgumentException("Unknown movement direction.");
        }

        this.x += dx;
        this.z += dz;
        sendPosition(this.x, this.y, this.z, true);
    }

    void jump() throws IOException {
        final double baseY;
        synchronized (this) {
            requirePosition();
            if (!this.jumping.compareAndSet(false, true)) {
                throw new IOException("The session is already jumping.");
            }
            baseY = this.y;
        }

        Thread thread = new Thread(() -> {
            double velocity = 0.42D;
            try {
                for (int tick = 0; tick < 20 && this.running.get(); tick++) {
                    synchronized (MinecraftSessionConnection.this) {
                        double nextY = this.y + velocity;
                        if (nextY <= baseY) {
                            this.y = baseY;
                            sendPosition(this.x, this.y, this.z, true);
                            break;
                        }
                        this.y = nextY;
                        sendPosition(this.x, this.y, this.z, false);
                    }
                    velocity = (velocity - 0.08D) * 0.98D;
                    Thread.sleep(50L);
                }
            } catch (Exception exception) {
                if (this.running.get()) this.listener.onMessage("[MSM] Jump failed: " + readableError(exception));
            } finally {
                this.jumping.set(false);
            }
        }, "MSM-Jump-" + this.sessionId);
        thread.setDaemon(true);
        thread.start();
    }

    synchronized boolean toggleSneak() throws IOException {
        requireOnline();
        boolean next = !this.sneaking;
        sendPlayerState(next ? 0 : 1);
        this.sneaking = next;
        return next;
    }

    synchronized boolean toggleSprint() throws IOException {
        requireOnline();
        boolean next = !this.sprinting;
        sendPlayerState(next ? 3 : 4);
        this.sprinting = next;
        return next;
    }

    private void handlePacket(byte[] payload) throws IOException {
        DataInputStream packet = new DataInputStream(new ByteArrayInputStream(payload));
        int packetId = readVarInt(packet);

        if (!this.loginAccepted) {
            switch (packetId) {
                case 0x00:
                    this.endReason = "Login rejected: " + flattenChat(readString(packet));
                    this.running.set(false);
                    return;
                case 0x01:
                    this.endReason = "This server requires online authentication. OFFLINE_LOCAL only works with an offline-mode server.";
                    this.running.set(false);
                    return;
                case 0x02:
                    readString(packet); // UUID
                    readString(packet); // username
                    this.loginAccepted = true;
                    this.listener.onMessage("[MSM] Login accepted; waiting for world join.");
                    return;
                case 0x03:
                    this.compressionThreshold = readVarInt(packet);
                    return;
                case 0x04:
                    int messageId = readVarInt(packet);
                    readString(packet); // channel
                    sendPacket(0x02, response -> {
                        writeVarInt(response, messageId);
                        response.writeBoolean(false);
                    });
                    return;
                default:
                    return;
            }
        }

        switch (packetId) {
            case 0x0E:
                String chat = readString(packet);
                packet.readUnsignedByte(); // chat position
                this.listener.onMessage(flattenChat(chat));
                break;
            case 0x1A:
                this.endReason = "Server disconnected: " + flattenChat(readString(packet));
                this.running.set(false);
                break;
            case 0x20:
                long keepAliveId = packet.readLong();
                sendPacket(0x0F, response -> response.writeLong(keepAliveId));
                break;
            case 0x25:
                this.entityId = packet.readInt();
                packet.readUnsignedByte(); // game mode and hardcore bit
                packet.readInt(); // dimension
                packet.readUnsignedByte(); // maximum players
                readString(packet); // world type
                readVarInt(packet); // view distance
                packet.readBoolean(); // reduced debug info
                sendPacket(0x05, settings -> {
                    writeString(settings, "en_us");
                    settings.writeByte(8);
                    writeVarInt(settings, 0); // Full chat visibility.
                    settings.writeBoolean(true);
                    settings.writeByte(0x7F); // All skin parts visible.
                    writeVarInt(settings, 0); // Main hand.
                });
                this.online = true;
                this.listener.onOnline(this);
                this.listener.onMessage("[MSM] Joined the server.");
                break;
            case 0x35:
                handlePosition(packet);
                break;
            default:
                break;
        }
    }

    private void handlePosition(DataInputStream packet) throws IOException {
        double packetX = packet.readDouble();
        double packetY = packet.readDouble();
        double packetZ = packet.readDouble();
        float packetYaw = packet.readFloat();
        float packetPitch = packet.readFloat();
        int flags = packet.readUnsignedByte();
        int teleportId = readVarInt(packet);

        synchronized (this) {
            this.x = (flags & 0x01) != 0 ? this.x + packetX : packetX;
            this.y = (flags & 0x02) != 0 ? this.y + packetY : packetY;
            this.z = (flags & 0x04) != 0 ? this.z + packetZ : packetZ;
            this.pitch = (flags & 0x08) != 0 ? this.pitch + packetPitch : packetPitch;
            this.yaw = (flags & 0x10) != 0 ? this.yaw + packetYaw : packetYaw;
            this.hasPosition = true;
        }

        sendPacket(0x00, response -> writeVarInt(response, teleportId));
    }

    private void sendPlayerState(int action) throws IOException {
        sendPacket(0x1B, packet -> {
            writeVarInt(packet, this.entityId);
            writeVarInt(packet, action);
            writeVarInt(packet, 0);
        });
    }

    private void sendPosition(double targetX, double targetY, double targetZ, boolean onGround)
            throws IOException {
        sendPacket(0x11, packet -> {
            packet.writeDouble(targetX);
            packet.writeDouble(targetY);
            packet.writeDouble(targetZ);
            packet.writeBoolean(onGround);
        });
    }

    private void requirePosition() throws IOException {
        requireOnline();
        if (!this.hasPosition) throw new IOException("Waiting for the server to send player position.");
    }

    private void requireOnline() throws IOException {
        if (!this.running.get() || !this.online) throw new IOException("Session is not connected.");
    }

    private byte[] readFrame() throws IOException {
        int frameLength = readVarInt(this.input);
        if (frameLength < 1 || frameLength > MAX_PACKET_SIZE) {
            throw new IOException("Invalid packet size: " + frameLength);
        }

        byte[] frame = new byte[frameLength];
        this.input.readFully(frame);
        if (this.compressionThreshold < 0) return frame;

        DataInputStream compressedFrame = new DataInputStream(new ByteArrayInputStream(frame));
        int uncompressedLength = readVarInt(compressedFrame);
        byte[] body = readRemaining(compressedFrame);
        if (uncompressedLength == 0) return body;
        if (uncompressedLength < 0 || uncompressedLength > MAX_PACKET_SIZE) {
            throw new IOException("Invalid uncompressed packet size: " + uncompressedLength);
        }
        return inflate(body, uncompressedLength);
    }

    private void sendPacket(int packetId, PacketWriter writer) throws IOException {
        synchronized (this.outputLock) {
            if (!this.running.get() || this.output == null) {
                throw new IOException("Session is not connected.");
            }

            ByteArrayOutputStream packetBytes = new ByteArrayOutputStream();
            DataOutputStream packet = new DataOutputStream(packetBytes);
            writeVarInt(packet, packetId);
            writer.write(packet);
            packet.flush();
            byte[] payload = packetBytes.toByteArray();

            ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
            if (this.compressionThreshold >= 0) {
                DataOutputStream frame = new DataOutputStream(frameBytes);
                if (payload.length >= this.compressionThreshold) {
                    writeVarInt(frame, payload.length);
                    frame.write(deflate(payload));
                } else {
                    writeVarInt(frame, 0);
                    frame.write(payload);
                }
                frame.flush();
            } else {
                frameBytes.write(payload);
            }

            byte[] framePayload = frameBytes.toByteArray();
            writeVarInt(this.output, framePayload.length);
            this.output.write(framePayload);
            this.output.flush();
        }
    }

    private static byte[] inflate(byte[] compressed, int expectedLength) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream result = new ByteArrayOutputStream(expectedLength);
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(buffer);
                } catch (DataFormatException exception) {
                    throw new IOException("Invalid compressed packet.", exception);
                }
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                } else {
                    result.write(buffer, 0, count);
                    if (result.size() > expectedLength || result.size() > MAX_PACKET_SIZE) {
                        throw new IOException("Compressed packet exceeded its declared size.");
                    }
                }
            }
            if (!inflater.finished() || result.size() != expectedLength) {
                throw new IOException("Compressed packet size mismatch.");
            }
            return result.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static byte[] deflate(byte[] payload) throws IOException {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(payload);
            deflater.finish();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count <= 0) throw new IOException("Could not compress packet.");
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] readRemaining(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            result.write(buffer, 0, count);
            if (result.size() > MAX_PACKET_SIZE) throw new IOException("Packet is too large.");
        }
        return result.toByteArray();
    }

    private static int readVarInt(DataInputStream input) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int current = input.readUnsignedByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) return value;
            position += 7;
            if (position >= 35) throw new IOException("VarInt is too large.");
        }
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            output.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.writeByte(value);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > 65536) throw new IOException("Invalid string length: " + length);
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static String flattenChat(String json) {
        try {
            JsonElement root = new JsonParser().parse(json);
            StringBuilder output = new StringBuilder();
            appendChat(root, output);
            String result = output.toString().trim();
            return result.isEmpty() ? json : result;
        } catch (RuntimeException exception) {
            return json;
        }
    }

    private static void appendChat(JsonElement element, StringBuilder output) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive()) {
            output.append(element.getAsString());
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) appendChat(child, output);
            return;
        }

        JsonObject object = element.getAsJsonObject();
        if (object.has("text")) {
            appendChat(object.get("text"), output);
        } else if (object.has("translate")) {
            appendChat(object.get("translate"), output);
            if (object.has("with")) appendChat(object.get("with"), output);
        }
        if (object.has("extra")) appendChat(object.get("extra"), output);
    }

    private static String readableError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) return exception.getClass().getSimpleName();
        return message;
    }

    private void closeSocket() {
        try {
            this.socket.close();
        } catch (IOException ignored) {
            // Socket is already closed.
        }
    }

    private static final class ServerAddress {
        private final String host;
        private final int port;

        private ServerAddress(String host, int port) {
            this.host = host;
            this.port = port;
        }

        private static ServerAddress parse(String address) throws IOException {
            String value = address == null ? "" : address.trim();
            if (value.isEmpty()) throw new IOException("Server address is empty.");

            String host = value;
            int port = 25565;
            if (value.startsWith("[")) {
                int closingBracket = value.indexOf(']');
                if (closingBracket < 0) throw new IOException("Invalid IPv6 server address.");
                host = value.substring(1, closingBracket);
                String suffix = value.substring(closingBracket + 1);
                if (!suffix.isEmpty()) {
                    if (!suffix.startsWith(":")) throw new IOException("Invalid server address.");
                    port = parsePort(suffix.substring(1));
                }
            } else {
                int firstColon = value.indexOf(':');
                int lastColon = value.lastIndexOf(':');
                if (firstColon >= 0 && firstColon == lastColon) {
                    host = value.substring(0, lastColon);
                    port = parsePort(value.substring(lastColon + 1));
                }
            }

            if (host.trim().isEmpty()) throw new IOException("Server host is empty.");
            return new ServerAddress(host, port);
        }

        private static int parsePort(String value) throws IOException {
            try {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65535) throw new NumberFormatException();
                return port;
            } catch (NumberFormatException exception) {
                throw new IOException("Server port must be between 1 and 65535.");
            }
        }
    }
}