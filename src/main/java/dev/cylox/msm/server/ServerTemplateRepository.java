package dev.cylox.msm.server;

import com.github.steveice10.opennbt.NBTIO;
import com.github.steveice10.opennbt.tag.builtin.CompoundTag;
import com.github.steveice10.opennbt.tag.builtin.ListTag;
import com.github.steveice10.opennbt.tag.builtin.StringTag;

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
import java.util.Optional;
import java.util.Properties;

/**
 * Reads vanilla Multiplayer server templates without modifying servers.dat.
 */
public final class ServerTemplateRepository {
    private final Path serversFile;
    private final Path lastSelectionFile;

    public ServerTemplateRepository(Path serversFile, Path lastSelectionFile) {
        if (serversFile == null || lastSelectionFile == null) {
            throw new IllegalArgumentException("Server list paths are required");
        }
        this.serversFile = serversFile;
        this.lastSelectionFile = lastSelectionFile;
    }

    public synchronized List<ServerTemplate> loadServers() throws IOException {
        if (!Files.exists(this.serversFile)) return Collections.emptyList();

        CompoundTag root = NBTIO.readFile(this.serversFile.toFile());
        ListTag entries = root.get("servers");
        if (entries == null) return Collections.emptyList();

        List<ServerTemplate> servers = new ArrayList<ServerTemplate>();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.get(index);
            if (entry == null) continue;

            StringTag nameTag = entry.get("name");
            StringTag addressTag = entry.get("ip");
            String name = nameTag == null ? "" : nameTag.getValue().trim();
            String address = addressTag == null ? "" : addressTag.getValue().trim();
            if (name.isEmpty() || address.isEmpty()) continue;

            servers.add(new ServerTemplate(name, address));
        }
        return Collections.unmodifiableList(servers);
    }

    public synchronized void rememberLastSelected(String name, String address) throws IOException {
        String normalizedAddress = address == null ? "" : address.trim();
        if (normalizedAddress.isEmpty()) return;

        Properties properties = new Properties();
        if (Files.exists(this.lastSelectionFile)) {
            try (InputStream input = Files.newInputStream(this.lastSelectionFile)) {
                properties.load(input);
            }
        }
        properties.setProperty("name", name == null ? normalizedAddress : name.trim());
        properties.setProperty("address", normalizedAddress);

        Path absolute = this.lastSelectionFile.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "msm-last-server-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Last server selected in Minecraft Multiplayer");
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

    public synchronized Optional<ServerTemplate> getLastSelected(List<ServerTemplate> currentServers)
            throws IOException {
        if (!Files.exists(this.lastSelectionFile)) return Optional.empty();

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(this.lastSelectionFile)) {
            properties.load(input);
        }
        String address = properties.getProperty("address", "").trim();
        if (address.isEmpty()) return Optional.empty();

        if (currentServers != null) {
            for (ServerTemplate server : currentServers) {
                if (server.getAddress().equalsIgnoreCase(address)) return Optional.of(server);
            }
        }

        String name = properties.getProperty("name", address).trim();
        return Optional.of(new ServerTemplate(name.isEmpty() ? address : name, address));
    }

    public static final class ServerTemplate {
        private final String name;
        private final String address;

        public ServerTemplate(String name, String address) {
            this.name = name == null ? "" : name.trim();
            this.address = address == null ? "" : address.trim();
            if (this.address.isEmpty()) {
                throw new IllegalArgumentException("Server address is required");
            }
        }

        public String getName() {
            return name;
        }

        public String getAddress() {
            return address;
        }

        @Override
        public String toString() {
            return this.name + " (" + this.address + ")";
        }
    }
}
