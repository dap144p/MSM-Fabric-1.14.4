package dev.cylox.msm.profile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class SessionProfileStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("msm-profile-test");
        Path file = directory.resolve("profiles.properties");
        SessionProfileStore store = new SessionProfileStore(file);
        List<SessionProfile> profiles = Arrays.asList(
                new SessionProfile("Player_B", "play.example.net:25565"),
                new SessionProfile("Player_C", "mc.example.org"));

        store.save(profiles);
        if (!profiles.equals(store.load())) throw new AssertionError("Saved profiles did not round-trip");
        store.save(java.util.Collections.<SessionProfile>emptyList());
        if (!store.load().isEmpty()) throw new AssertionError("Cleared profile list was not saved");

        boolean rejectedBlankUsername = false;
        try {
            new SessionProfile(" ", "play.example.net");
        } catch (IllegalArgumentException expected) {
            rejectedBlankUsername = true;
        }
        if (!rejectedBlankUsername) throw new AssertionError("Blank username must be rejected");

        boolean rejectedInvalidUsername = false;
        try {
            new SessionProfile("Player Name", "play.example.net");
        } catch (IllegalArgumentException expected) {
            rejectedInvalidUsername = true;
        }
        if (!rejectedInvalidUsername) throw new AssertionError("Invalid username must be rejected");

        System.out.println("SessionProfileStoreTest: PASS");
    }
}
