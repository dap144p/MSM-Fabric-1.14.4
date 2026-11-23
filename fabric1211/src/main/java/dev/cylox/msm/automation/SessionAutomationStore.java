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
import java.util.List;
import java.util.Properties;

/**
 * Atomic per-profile storage for AutoChat and AutoReconnect preferences.
 */
public final class SessionAutomationStore {
    private static final int MAX_RULES = 100;
    private final Path file;

    public SessionAutomationStore(Path file) {
        if (file == null) throw new IllegalArgumentException("Automation settings path is required");
        this.file = file;
    }

    public synchronized SessionAutomationSettings load(SessionProfile profile) throws IOException {
        if (profile == null) throw new IllegalArgumentException("Session profile is required");
        Properties properties = readProperties();
        String prefix = "account." + profileKey(profile) + ".";
        SessionAutomationSettings settings = new SessionAutomationSettings();

        settings.setAutoReconnect(readBoolean(properties, prefix + "autoReconnect", false));
        settings.setDesiredOnline(readBoolean(properties, prefix + "desiredOnline", false));
        settings.setReconnectDelaySeconds(readInt(properties, prefix + "reconnectDelaySeconds", 10));
        settings.setMaxReconnectAttempts(readInt(properties, prefix + "maxReconnectAttempts", 10));
        settings.setRetryBackoff(readBoolean(properties, prefix + "retryBackoff", true));
        settings.setMaxReconnectDelaySeconds(readInt(properties, prefix + "maxReconnectDelaySeconds", 60));
        settings.setAutoChatEnabled(readBoolean(properties, prefix + "autoChatEnabled", false));
        settings.setInitialChatDelaySeconds(readInt(properties, prefix + "initialChatDelaySeconds", 5));
        settings.setGlobalChatIntervalSeconds(readInt(properties, prefix + "globalChatIntervalSeconds", 3));

        int count = readInt(properties, prefix + "rule.count", 0);
        if (count < 0 || count > MAX_RULES) {
            throw new IOException("Invalid AutoChat rule count for profile " + profile.getUsername());
        }

        List<SessionAutomationSettings.AutoChatRule> rules =
                new ArrayList<SessionAutomationSettings.AutoChatRule>();
        for (int index = 0; index < count; index++) {
            String rulePrefix = prefix + "rule." + index + ".";
            String message = properties.getProperty(rulePrefix + "message", "").trim();
            String modeValue = properties.getProperty(rulePrefix + "mode", "ONCE");
            boolean enabled = readBoolean(properties, rulePrefix + "enabled", true);
            int delay = readInt(properties, rulePrefix + "delaySeconds", 0);
            try {
                SessionAutomationSettings.AutoChatRule.Mode mode =
                        SessionAutomationSettings.AutoChatRule.Mode.valueOf(modeValue);
                rules.add(new SessionAutomationSettings.AutoChatRule(message, mode, delay, enabled));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid AutoChat rule " + index
                        + " for profile " + profile.getUsername(), exception);
            }
        }
        settings.replaceRules(rules);
        return settings;
    }

    public synchronized void save(SessionProfile profile, SessionAutomationSettings settings) throws IOException {
        if (profile == null || settings == null) {
            throw new IllegalArgumentException("Profile and automation settings are required");
        }

        Properties properties = readProperties();
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
        properties.setProperty(prefix + "autoChatEnabled", Boolean.toString(snapshot.isAutoChatEnabled()));
        properties.setProperty(prefix + "initialChatDelaySeconds",
                Integer.toString(snapshot.getInitialChatDelaySeconds()));
        properties.setProperty(prefix + "globalChatIntervalSeconds",
                Integer.toString(snapshot.getGlobalChatIntervalSeconds()));

        List<SessionAutomationSettings.AutoChatRule> rules = snapshot.getRules();
        properties.setProperty(prefix + "rule.count", Integer.toString(rules.size()));
        for (int index = 0; index < rules.size(); index++) {
            SessionAutomationSettings.AutoChatRule rule = rules.get(index);
            String rulePrefix = prefix + "rule." + index + ".";
            properties.setProperty(rulePrefix + "message", rule.getMessage());
            properties.setProperty(rulePrefix + "mode", rule.getMode().name());
            properties.setProperty(rulePrefix + "delaySeconds", Integer.toString(rule.getDelaySeconds()));
            properties.setProperty(rulePrefix + "enabled", Boolean.toString(rule.isEnabled()));
        }

        writeAtomically(properties);
    }

    public synchronized void delete(SessionProfile profile) throws IOException {
        if (profile == null) throw new IllegalArgumentException("Session profile is required");

        Properties properties = readProperties();
        String prefix = "account." + profileKey(profile) + ".";
        for (String key : new ArrayList<String>(properties.stringPropertyNames())) {
            if (key.startsWith(prefix)) {
                properties.remove(key);
            }
        }
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
        Path temporary = Files.createTempFile(parent, "msm-automation-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "MSM per-account automation settings");
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
