package dev.cylox.msm.automation;

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
import java.util.ArrayList;
import java.util.Properties;

public final class SessionAutomationStore {
    private final Path file;

    public SessionAutomationStore(Path file) {
        if (file == null) throw new IllegalArgumentException("Automation settings path is required");
        this.file = file;
    }

    public synchronized SessionAutomationSettings load(SessionProfile profile) throws IOException {
        if (profile == null) throw new IllegalArgumentException("Session profile is required");
        Properties properties = readProperties();
        if (removeLegacyAutoChatEntries(properties)) {
            writeAtomically(properties);
        }

        String prefix = "account." + profileKey(profile) + ".";
        SessionAutomationSettings settings = new SessionAutomationSettings();
        settings.setAutoReconnect(readBoolean(properties, prefix + "autoReconnect", false));
        settings.setDesiredOnline(readBoolean(properties, prefix + "desiredOnline", false));
        settings.setReconnectDelaySeconds(readInt(properties, prefix + "reconnectDelaySeconds", 15));
        settings.setMaxReconnectAttempts(readInt(properties, prefix + "maxReconnectAttempts", 5));
        settings.setRetryBackoff(readBoolean(properties, prefix + "retryBackoff", true));
        settings.setMaxReconnectDelaySeconds(readInt(properties, prefix + "maxReconnectDelaySeconds", 300));
        return settings;
    }

    public synchronized void save(SessionProfile profile, SessionAutomationSettings settings) throws IOException {
        if (profile == null || settings == null) {
            throw new IllegalArgumentException("Profile and automation settings are required");
        }

        Properties properties = readProperties();
        removeLegacyAutoChatEntries(properties);
        String prefix = "account." + profileKey(profile) + ".";
        SessionAutomationSettings snapshot = settings.copy();

        properties.setProperty(prefix + "autoReconnect", Boolean.toString(snapshot.isAutoReconnect()));
        properties.setProperty(prefix + "desiredOnline", Boolean.toString(snapshot.isDesiredOnline()));
        properties.setProperty(prefix + "reconnectDelaySeconds",
                Integer.toString(snapshot.getReconnectDelaySeconds()));
        properties.setProperty(prefix + "maxReconnectAttempts",
                Integer.toString(snapshot.getMaxReconnectAttempts()));
        properties.setProperty(prefix + "retryBackoff", Boolean.toString(snapshot.isRetryBackoff()));
        properties.setProperty(prefix + "maxReconnectDelaySeconds",
                Integer.toString(snapshot.getMaxReconnectDelaySeconds()));

        writeAtomically(properties);
    }

    public synchronized void delete(SessionProfile profile) throws IOException {
        if (profile == null) throw new IllegalArgumentException("Session profile is required");
        Properties properties = readProperties();
        String prefix = "account." + profileKey(profile) + ".";
        for (String key : new ArrayList<String>(properties.stringPropertyNames())) {
            if (key.startsWith(prefix)) properties.remove(key);
        }
        writeAtomically(properties);
    }

    private static boolean removeLegacyAutoChatEntries(Properties properties) {
        boolean changed = false;
        for (String key : new ArrayList<String>(properties.stringPropertyNames())) {
            if (!key.startsWith("account.")) continue;
            int separator = key.indexOf('.', "account.".length());
            if (separator < 0) continue;
            String setting = key.substring(separator + 1);
            if (setting.equals("autoChatEnabled")
                    || setting.equals("initialChatDelaySeconds")
                    || setting.equals("globalChatIntervalSeconds")
                    || setting.startsWith("rule.")) {
                properties.remove(key);
                changed = true;
            }
        }
        return changed;
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
        Path temporary = Files.createTempFile(parent, "msm-automation-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "MSM per-account reconnect settings");
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

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int readInt(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String profileKey(SessionProfile profile) throws IOException {
        String identity = profile.getUsername() + "\u0000"
                + profile.getServerAddress() + "\u0000" + profile.getAuthMode().name();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is not available", exception);
        }
    }
}
