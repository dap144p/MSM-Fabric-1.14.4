package dev.cylox.msm.config;

import dev.cylox.msm.profile.SessionAuthMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Properties;

/** Atomic storage for network preferences keyed by account identity, not server profile. */
public final class SessionNetworkStore {
    private final Path file;

    public SessionNetworkStore(Path file) {
        if (file == null) throw new IllegalArgumentException("Network settings path is required.");
        this.file = file;
    }

    public synchronized SessionNetworkSettings load(String username, SessionAuthMode authMode)
            throws IOException {
        String prefix = accountPrefix(username, authMode);
        Properties properties = readProperties();
        return new SessionNetworkSettings(properties.getProperty(prefix + "outgoingAddress", ""));
    }

    public synchronized void save(String username, SessionAuthMode authMode,
                                  SessionNetworkSettings settings) throws IOException {
        if (settings == null) throw new IllegalArgumentException("Network settings are required.");
        String prefix = accountPrefix(username, authMode);
        Properties properties = readProperties();
        String address = settings.getOutgoingAddress();
        if (address.isEmpty()) {
            properties.remove(prefix + "outgoingAddress");
        } else {
            properties.setProperty(prefix + "outgoingAddress", address);
        }
        writeAtomically(properties);
    }

    public synchronized void delete(String username, SessionAuthMode authMode) throws IOException {
        String prefix = accountPrefix(username, authMode);
        Properties properties = readProperties();
        for (String key : new ArrayList<String>(properties.stringPropertyNames())) {
            if (key.startsWith(prefix)) properties.remove(key);
        }
        writeAtomically(properties);
    }

    private Properties readProperties() throws IOException {
        Properties properties = new Properties();
        if (!Files.exists(this.file)) return properties;
        try (InputStream input = Files.newInputStream(this.file)) {
            try {
                properties.load(input);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid MSM network settings file.", exception);
            }
        }
        return properties;
    }

    private void writeAtomically(Properties properties) throws IOException {
        Path absolute = this.file.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "msm-network-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "MSM per-account outgoing network settings");
            }
            try {
                Files.move(temporary, absolute,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String accountPrefix(String username, SessionAuthMode authMode)
            throws IOException {
        String normalized = username == null ? "" : username.trim();
        if (normalized.isEmpty() || authMode == null) {
            throw new IllegalArgumentException("Account username and authentication mode are required.");
        }
        String identity = normalized + "\u0000" + authMode.name();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return "account." + hex + ".";
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is not available.", exception);
        }
    }
}
