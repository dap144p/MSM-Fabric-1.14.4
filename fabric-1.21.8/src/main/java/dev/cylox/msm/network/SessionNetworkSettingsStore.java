package dev.cylox.msm.network;

import dev.cylox.msm.profile.SessionProfile;

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
import java.util.Properties;

public final class SessionNetworkSettingsStore {
    private final Path file;

    public SessionNetworkSettingsStore(Path file) {
        if (file == null) throw new IllegalArgumentException("Network settings path is required");
        this.file = file;
    }

    public synchronized String loadBindAddress(SessionProfile profile) throws IOException {
        if (profile == null) throw new IllegalArgumentException("Session profile is required");
        String value = readProperties().getProperty(key(profile), "");
        return value == null ? "" : value.trim();
    }

    public synchronized void saveBindAddress(SessionProfile profile, String address) throws IOException {
        if (profile == null) throw new IllegalArgumentException("Session profile is required");
        Properties properties = readProperties();
        String key = key(profile);
        String value = address == null ? "" : address.trim();
        if (value.isEmpty()) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
        writeAtomically(properties);
    }

    public synchronized void delete(SessionProfile profile) throws IOException {
        if (profile == null) throw new IllegalArgumentException("Session profile is required");
        Properties properties = readProperties();
        properties.remove(key(profile));
        writeAtomically(properties);
    }

    private Properties readProperties() throws IOException {
        Properties properties = new Properties();
        if (!Files.exists(file)) return properties;
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }

    private void writeAtomically(Properties properties) throws IOException {
        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "msm-network-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "MSM per-account outgoing network bindings");
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

    private static String key(SessionProfile profile) throws IOException {
        String identity = profile.getUsername() + (char) 0
                + profile.getServerAddress() + (char) 0 + profile.getAuthMode().name();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return "account." + hex + ".bindAddress";
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is not available", exception);
        }
    }
}
