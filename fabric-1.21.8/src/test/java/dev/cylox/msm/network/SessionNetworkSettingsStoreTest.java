package dev.cylox.msm.network;

import dev.cylox.msm.profile.SessionAuthMode;
import dev.cylox.msm.profile.SessionProfile;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SessionNetworkSettingsStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("msm-network-settings-test");
        Path file = directory.resolve("network.properties");
        SessionNetworkSettingsStore store = new SessionNetworkSettingsStore(file);
        SessionProfile first = new SessionProfile(
                "NetworkA", "server-a.example", SessionAuthMode.OFFLINE_LOCAL);
        SessionProfile second = new SessionProfile(
                "NetworkA", "server-b.example", SessionAuthMode.OFFLINE_LOCAL);
        SessionProfile third = new SessionProfile(
                "NetworkB", "server-a.example", SessionAuthMode.OFFLINE_LOCAL);

        try {
            check(store.loadBindAddress(first).isEmpty(), "Missing settings must default to automatic routing");
            store.saveBindAddress(first, "192.168.1.21");
            store.saveBindAddress(second, "10.0.0.22");
            store.saveBindAddress(third, "");

            check("192.168.1.21".equals(store.loadBindAddress(first)),
                    "First profile binding was not persisted");
            check("10.0.0.22".equals(store.loadBindAddress(second)),
                    "Second profile binding was not persisted");
            check(store.loadBindAddress(third).isEmpty(),
                    "A profile using automatic routing must remain independent");

            store.delete(first);
            check(store.loadBindAddress(first).isEmpty(),
                    "Deleting one profile must clear its network settings");
            check("10.0.0.22".equals(store.loadBindAddress(second)),
                    "Deleting one profile must not change another profile");

            store.saveBindAddress(second, "");
            check(store.loadBindAddress(second).isEmpty(),
                    "Saving an empty binding must restore automatic routing");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }

        System.out.println("SessionNetworkSettingsStoreTest: PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
