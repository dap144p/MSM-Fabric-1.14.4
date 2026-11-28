package dev.cylox.msm.config;

import java.util.UUID;

/** A reusable application-level proxy endpoint. */
public final class ProxyProfile {
    public enum Type { SOCKS5, HTTP_CONNECT }

    private final String id;
    private final String name;
    private final Type type;
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    public ProxyProfile(String id, String name, Type type, String host, int port,
                        String username, String password) {
        this.id = required(id, "Proxy ID", 64);
        this.name = required(name, "Proxy name", 32);
        this.type = type;
        this.host = required(host, "Proxy host", 253);
        this.port = port;
        this.username = clean(username, 128);
        this.password = clean(password, 128);
        if (type == null) throw new IllegalArgumentException("Proxy type is required.");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Proxy port must be between 1 and 65535.");
        if (this.host.indexOf(' ') >= 0 || this.host.indexOf('/') >= 0 || this.host.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Proxy host must be a hostname or IP address.");
        }
        if (this.username.isEmpty() != this.password.isEmpty()) {
            throw new IllegalArgumentException("Proxy username and password must both be provided, or both be empty.");
        }
    }

    public static ProxyProfile create(String name, Type type, String host, int port,
                                      String username, String password) {
        return new ProxyProfile(UUID.randomUUID().toString(), name, type, host, port, username, password);
    }

    public String getId() { return this.id; }
    public String getName() { return this.name; }
    public Type getType() { return this.type; }
    public String getHost() { return this.host; }
    public int getPort() { return this.port; }
    public String getUsername() { return this.username; }
    public String getPassword() { return this.password; }
    public boolean hasAuthentication() { return !this.username.isEmpty(); }

    private static String required(String value, String label, int maxLength) {
        String normalized = clean(value, maxLength);
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " is required.");
        return normalized;
    }

    private static String clean(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Proxy field contains an invalid value.");
        }
        return normalized;
    }
}
