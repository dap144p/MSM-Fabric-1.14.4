package dev.cylox.msm.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Atomic persistence for MSM proxy profiles. Credentials are stored locally in this file. */
public final class ProxyProfileStore {
    private final Path file;

    public ProxyProfileStore(Path file) {
        if (file == null) throw new IllegalArgumentException("Proxy profile path is required.");
        this.file = file;
    }

    public synchronized List<ProxyProfile> load() throws IOException {
        Properties properties = readProperties();
        int count;
        try {
            count = Integer.parseInt(properties.getProperty("proxy.count", "0"));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid proxy profile count.", exception);
        }
        if (count < 0 || count > 128) throw new IOException("Proxy profile count is outside the supported range.");

        List<ProxyProfile> profiles = new ArrayList<ProxyProfile>();
        Set<String> ids = new HashSet<String>();
        for (int index = 0; index < count; index++) {
            String prefix = "proxy." + index + ".";
            try {
                String id = required(properties, prefix + "id");
                ProxyProfile.Type type = ProxyProfile.Type.valueOf(required(properties, prefix + "type"));
                ProxyProfile profile = new ProxyProfile(id, required(properties, prefix + "name"),
                        type, required(properties, prefix + "host"),
                        Integer.parseInt(required(properties, prefix + "port")),
                        properties.getProperty(prefix + "username", ""),
                        properties.getProperty(prefix + "password", ""));
                if (!ids.add(profile.getId())) throw new IllegalArgumentException("Duplicate proxy ID.");
                profiles.add(profile);
            } catch (RuntimeException exception) {
                throw new IOException("Invalid saved proxy profile at index " + index + ".", exception);
            }
        }
        return Collections.unmodifiableList(profiles);
    }

    public synchronized void save(List<ProxyProfile> profiles) throws IOException {
        if (profiles == null || profiles.size() > 128) {
            throw new IllegalArgumentException("Between 0 and 128 proxy profiles are supported.");
        }
        Properties properties = new Properties();
        Set<String> ids = new HashSet<String>();
        properties.setProperty("proxy.count", Integer.toString(profiles.size()));
        for (int index = 0; index < profiles.size(); index++) {
            ProxyProfile profile = profiles.get(index);
            if (profile == null || !ids.add(profile.getId())) {
                throw new IllegalArgumentException("Proxy profiles must be valid and have unique IDs.");
            }
            String prefix = "proxy." + index + ".";
            properties.setProperty(prefix + "id", profile.getId());
            properties.setProperty(prefix + "name", profile.getName());
            properties.setProperty(prefix + "type", profile.getType().name());
            properties.setProperty(prefix + "host", profile.getHost());
            properties.setProperty(prefix + "port", Integer.toString(profile.getPort()));
            if (profile.hasAuthentication()) {
                properties.setProperty(prefix + "username", profile.getUsername());
                properties.setProperty(prefix + "password", profile.getPassword());
            }
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
                throw new IOException("Invalid MSM proxy profiles file.", exception);
            }
        }
        return properties;
    }

    private void writeAtomically(Properties properties) throws IOException {
        Path absolute = this.file.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "msm-proxies-", ".tmp");
        try {
            restrictPermissions(temporary);
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "MSM proxy profiles; credentials are stored locally");
            }
            try {
                Files.move(temporary, absolute,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(absolute);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and filesystems without POSIX permissions use their existing user profile ACLs.
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing " + key + ".");
        return value;
    }
}
