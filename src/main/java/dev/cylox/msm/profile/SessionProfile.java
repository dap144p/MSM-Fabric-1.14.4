package dev.cylox.msm.profile;

import java.util.Objects;
import java.util.regex.Pattern;

public final class SessionProfile {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final String username;
    private final String serverAddress;

    public SessionProfile(String username, String serverAddress) {
        String normalizedUsername = username == null ? "" : username.trim();
        String normalizedServer = serverAddress == null ? "" : serverAddress.trim();
        if (!USERNAME_PATTERN.matcher(normalizedUsername).matches()) {
            throw new IllegalArgumentException("Username must contain 1-16 letters, numbers, or underscores");
        }
        if (normalizedServer.isEmpty() || normalizedServer.length() > 255) {
            throw new IllegalArgumentException("Server address must contain 1-255 characters");
        }
        this.username = normalizedUsername;
        this.serverAddress = normalizedServer;
    }

    public String getUsername() {
        return username;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SessionProfile)) return false;
        SessionProfile that = (SessionProfile) other;
        return username.equals(that.username) && serverAddress.equals(that.serverAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, serverAddress);
    }
}
