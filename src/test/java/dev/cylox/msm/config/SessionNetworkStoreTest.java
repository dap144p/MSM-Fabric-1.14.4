package dev.cylox.msm.config;

import dev.cylox.msm.profile.SessionAuthMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Dependency-free checks for per-account network settings persistence. */
public final class SessionNetworkStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("msm-network-store-test");
        try {
            SessionNetworkStore store = new SessionNetworkStore(directory.resolve("network.properties"));
            check(store.load("Alice", SessionAuthMode.OFFLINE_LOCAL).isAutomatic(),
                    "Missing settings must default to automatic binding.");

            store.save("Alice", SessionAuthMode.OFFLINE_LOCAL,
                    new SessionNetworkSettings("192.168.1.20"));
            store.save("Bob", SessionAuthMode.OFFLINE_LOCAL,
                    new SessionNetworkSettings("10.0.0.12"));
            store.save("Alice", SessionAuthMode.MICROSOFT,
                    new SessionNetworkSettings("172.16.0.5"));

            check("192.168.1.20".equals(store.load("Alice",
                    SessionAuthMode.OFFLINE_LOCAL).getOutgoingAddress()),
                    "Alice's offline settings must be restored.");
            check("10.0.0.12".equals(store.load("Bob",
                    SessionAuthMode.OFFLINE_LOCAL).getOutgoingAddress()),
                    "Bob's settings must remain isolated.");
            check("172.16.0.5".equals(store.load("Alice",
                    SessionAuthMode.MICROSOFT).getOutgoingAddress()),
                    "Authentication modes must have separate settings.");

            store.delete("Alice", SessionAuthMode.OFFLINE_LOCAL);
            check(store.load("Alice", SessionAuthMode.OFFLINE_LOCAL).isAutomatic(),
                    "Deleting an account must clear its network settings.");
            check("10.0.0.12".equals(store.load("Bob",
                    SessionAuthMode.OFFLINE_LOCAL).getOutgoingAddress()),
                    "Deleting one account must not change another account.");
            check("172.16.0.5".equals(store.load("Alice",
                    SessionAuthMode.MICROSOFT).getOutgoingAddress()),
                    "Deleting one authentication identity must not change another.");
        } finally {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
