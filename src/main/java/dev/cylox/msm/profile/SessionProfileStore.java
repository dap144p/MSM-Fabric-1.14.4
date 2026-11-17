package dev.cylox.msm.profile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public final class SessionProfileStore {
    private static final int MAX_PROFILES = 100;
    private final Path file;

    public SessionProfileStore(Path file) {
        if (file == null) throw new IllegalArgumentException("Profile file is required");
        this.file = file;
    }

    public List<SessionProfile> load() throws IOException {
        if (!Files.exists(file)) return Collections.emptyList();

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }

        int count;
        try {
            count = Integer.parseInt(properties.getProperty("profile.count", "0"));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid profile count", exception);
        }
        if (count < 0 || count > MAX_PROFILES) throw new IOException("Profile count is out of range");

        List<SessionProfile> profiles = new ArrayList<SessionProfile>(count);
        for (int index = 0; index < count; index++) {
            String prefix = "profile." + index + ".";
            try {
                profiles.add(new SessionProfile(
                        properties.getProperty(prefix + "username"),
                        properties.getProperty(prefix + "server")));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid saved profile at index " + index, exception);
            }
        }
        return profiles;
    }

    public void save(List<SessionProfile> profiles) throws IOException {
        if (profiles == null || profiles.size() > MAX_PROFILES) {
            throw new IllegalArgumentException("Profile list must contain 0-" + MAX_PROFILES + " entries");
        }

        Properties properties = new Properties();
        properties.setProperty("profile.count", Integer.toString(profiles.size()));
        for (int index = 0; index < profiles.size(); index++) {
            SessionProfile profile = profiles.get(index);
            if (profile == null) throw new IllegalArgumentException("Profile cannot be null");
            String prefix = "profile." + index + ".";
            properties.setProperty(prefix + "username", profile.getUsername());
            properties.setProperty(prefix + "server", profile.getServerAddress());
        }

        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "msm-profiles-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Cylox Multi Session Manager profiles; credentials are not stored");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
