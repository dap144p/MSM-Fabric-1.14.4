package dev.cylox.msm.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks published MSM releases and stages a verified, version-specific JAR.
 * The updater never replaces a JAR while Minecraft is running.
 */
public final class MsmUpdateManager {
    private static final String RELEASES_API =
            "https://api.github.com/repos/dap144p/Multi-Sessions-Manager-Fabric/releases?per_page=50";
    private static final String USER_AGENT = "MSM-Fabric-Updater";
    private static final int CONNECT_TIMEOUT_MS = 7000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final long MAX_JAR_BYTES = 64L * 1024L * 1024L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MSM-Updater");
        thread.setDaemon(true);
        return thread;
    });

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-(dev|nightly|beta|rc)\\.(\\d+))?$");
    private static final Pattern HASH_PATTERN = Pattern.compile("(?i)\\b([a-f0-9]{64})\\b");

    private final String minecraftVersion;
    private final String installedVersion;
    private final Path settingsFile;
    private final Path stagingDirectory;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private volatile Channel channel;

    public MsmUpdateManager(Path configDirectory, String minecraftVersion, String installedVersion) {
        if (configDirectory == null || minecraftVersion == null || installedVersion == null) {
            throw new IllegalArgumentException("Updater paths and versions are required.");
        }
        this.minecraftVersion = minecraftVersion;
        this.installedVersion = installedVersion;
        this.settingsFile = configDirectory.resolve("msm-updater.properties");
        this.stagingDirectory = configDirectory.resolve("msm-updates");
        this.channel = loadChannel();
    }

    public String getMinecraftVersion() {
        return this.minecraftVersion;
    }

    public String getInstalledVersion() {
        return this.installedVersion;
    }

    public Channel getChannel() {
        return this.channel;
    }

    public boolean isChecking() {
        return this.checking.get();
    }

    public boolean isDownloading() {
        return this.downloading.get();
    }

    public void cycleChannel() throws IOException {
        Channel next = this.channel.next();
        Properties properties = new Properties();
        properties.setProperty("channel", next.id);
        Path parent = this.settingsFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = this.settingsFile.resolveSibling(this.settingsFile.getFileName().toString() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "Multi Sessions Manager updater settings");
        }
        moveAtomically(temporary, this.settingsFile);
        this.channel = next;
    }

    public void checkForUpdates(Consumer<CheckResult> completion) {
        if (!this.checking.compareAndSet(false, true)) return;
        final Channel selectedChannel = this.channel;
        WORKER.execute(() -> {
            CheckResult result;
            try {
                result = check(selectedChannel);
            } catch (Exception exception) {
                result = CheckResult.failure("Update check failed: " + safeMessage(exception));
            } finally {
                this.checking.set(false);
            }
            if (completion != null) completion.accept(result);
        });
    }

    public void download(UpdateInfo update, Consumer<DownloadResult> completion) {
        if (update == null || !this.downloading.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            DownloadResult result;
            try {
                result = downloadVerified(update);
            } catch (Exception exception) {
                result = DownloadResult.failure("Download failed: " + safeMessage(exception));
            } finally {
                this.downloading.set(false);
            }
            if (completion != null) completion.accept(result);
        });
    }

    private CheckResult check(Channel selectedChannel) throws IOException {
        if (this.installedVersion.contains("-dev.")) {
            return CheckResult.failure(
                    "Development builds use temporary CI artifacts. Release updates are disabled for this build.");
        }

        HttpURLConnection connection = openConnection(new URL(RELEASES_API));
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub returned HTTP " + responseCode + ".");
            }

            JsonArray releases = new JsonParser().parse(readText(connection.getInputStream(), 4L * 1024L * 1024L))
                    .getAsJsonArray();
            for (JsonElement element : releases) {
                if (!element.isJsonObject()) continue;
                JsonObject release = element.getAsJsonObject();
                if (booleanValue(release, "draft")) continue;

                JsonArray assets = release.has("assets") && release.get("assets").isJsonArray()
                        ? release.getAsJsonArray("assets") : new JsonArray();
                JsonObject jarAsset = null;
                String candidateVersion = null;
                String expectedSuffix = "-" + this.minecraftVersion + ".jar";
                for (JsonElement assetElement : assets) {
                    if (!assetElement.isJsonObject()) continue;
                    JsonObject asset = assetElement.getAsJsonObject();
                    String name = stringValue(asset, "name");
                    if (name != null && name.startsWith("MSM-Fabric-") && name.endsWith(expectedSuffix)) {
                        jarAsset = asset;
                        candidateVersion = name.substring("MSM-Fabric-".length(),
                                name.length() - expectedSuffix.length());
                        break;
                    }
                }

                if (jarAsset == null || candidateVersion == null
                        || !selectedChannel.accepts(candidateVersion)
                        || compareVersions(candidateVersion, this.installedVersion) <= 0) {
                    continue;
                }

                String downloadUrl = stringValue(jarAsset, "browser_download_url");
                if (!isOfficialReleaseAsset(downloadUrl)) continue;

                String checksumUrl = null;
                JsonObject checksumAsset = null;
                String jarName = stringValue(jarAsset, "name");
                for (JsonElement assetElement : assets) {
                    if (!assetElement.isJsonObject()) continue;
                    JsonObject asset = assetElement.getAsJsonObject();
                    if ((jarName + ".sha256").equals(stringValue(asset, "name"))) {
                        checksumAsset = asset;
                        break;
                    }
                }

                if (checksumAsset != null) {
                    checksumUrl = stringValue(checksumAsset, "browser_download_url");
                    if (!isOfficialReleaseAsset(checksumUrl)) checksumUrl = null;
                }

                String digest = stringValue(jarAsset, "digest");
                if (digest != null && digest.toLowerCase(Locale.ROOT).startsWith("sha256:")) {
                    digest = digest.substring("sha256:".length()).trim();
                    if (!HASH_PATTERN.matcher(digest).matches()) digest = null;
                } else {
                    digest = null;
                }

                if (digest == null && checksumUrl == null) {
                    throw new IOException("This release has no SHA-256 checksum for the JAR.");
                }

                String releaseUrl = stringValue(release, "html_url");
                return CheckResult.available(new UpdateInfo(
                        candidateVersion, this.minecraftVersion, downloadUrl, checksumUrl,
                        digest, releaseUrl, jarName));
            }

            return CheckResult.upToDate("No newer " + selectedChannel.label
                    + " release is available for Minecraft " + this.minecraftVersion + ".");
        } finally {
            connection.disconnect();
        }
    }

    private DownloadResult downloadVerified(UpdateInfo update) throws IOException, NoSuchAlgorithmException {
        if (!this.minecraftVersion.equals(update.minecraftVersion)
                || !isOfficialReleaseAsset(update.downloadUrl)) {
            throw new IOException("The selected update does not match this Minecraft version.");
        }

        String expectedHash = update.sha256;
        if (expectedHash == null) {
            if (!isOfficialReleaseAsset(update.checksumUrl)) {
                throw new IOException("The release checksum URL is missing or invalid.");
            }
            HttpURLConnection checksumConnection = openConnection(new URL(update.checksumUrl));
            try {
                if (checksumConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Could not retrieve the SHA-256 checksum.");
                }
                String checksumText = readText(checksumConnection.getInputStream(), 4096);
                Matcher matcher = HASH_PATTERN.matcher(checksumText);
                if (!matcher.find()) throw new IOException("The checksum file is malformed.");
                expectedHash = matcher.group(1).toLowerCase(Locale.ROOT);
            } finally {
                checksumConnection.disconnect();
            }
        }

        Files.createDirectories(this.stagingDirectory);
        Path destination = this.stagingDirectory.resolve(update.jarName);
        Path temporary = this.stagingDirectory.resolve(update.jarName + ".part");
        HttpURLConnection connection = openConnection(new URL(update.downloadUrl));
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub returned HTTP " + responseCode + " while downloading.");
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_JAR_BYTES) {
                throw new IOException("The downloaded JAR exceeds the size limit.");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long totalBytes = 0L;
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > MAX_JAR_BYTES) {
                        throw new IOException("The downloaded JAR exceeds the size limit.");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }

            String actualHash = toHex(digest.digest());
            if (!actualHash.equalsIgnoreCase(expectedHash)) {
                throw new IOException("SHA-256 verification failed. The staged file was rejected.");
            }
            moveAtomically(temporary, destination);
            return DownloadResult.success(destination);
        } catch (IOException | NoSuchAlgorithmException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        } finally {
            connection.disconnect();
        }
    }

    private Channel loadChannel() {
        Properties properties = new Properties();
        if (Files.isRegularFile(this.settingsFile)) {
            try (InputStream input = Files.newInputStream(this.settingsFile)) {
                properties.load(input);
                return Channel.fromId(properties.getProperty("channel"));
            } catch (IOException ignored) {
                // A missing or unreadable preference file falls back to the default channel.
            }
        }
        return Channel.NIGHTLY;
    }

    private static HttpURLConnection openConnection(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Only HTTPS update sources are allowed.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        return connection;
    }

    private static boolean isOfficialReleaseAsset(String value) {
        if (value == null) return false;
        try {
            URL url = new URL(value);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && "github.com".equalsIgnoreCase(url.getHost())
                    && url.getPath().startsWith(
                            "/dap144p/Multi-Sessions-Manager-Fabric/releases/download/");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String readText(InputStream input, long maximumBytes) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            long total = 0L;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) throw new IOException("The update response is unexpectedly large.");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static boolean booleanValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static int compareVersions(String left, String right) {
        Matcher a = VERSION_PATTERN.matcher(left);
        Matcher b = VERSION_PATTERN.matcher(right);
        if (!a.matches() || !b.matches()) return left.compareToIgnoreCase(right);

        for (int group = 1; group <= 3; group++) {
            int comparison = Integer.compare(Integer.parseInt(a.group(group)),
                    Integer.parseInt(b.group(group)));
            if (comparison != 0) return comparison;
        }

        String aChannel = a.group(4);
        String bChannel = b.group(4);
        int aRank = releaseRank(aChannel);
        int bRank = releaseRank(bChannel);
        if (aRank != bRank) return Integer.compare(aRank, bRank);
        if (aChannel == null) return 0;
        return Integer.compare(Integer.parseInt(a.group(5)), Integer.parseInt(b.group(5)));
    }

    private static int releaseRank(String channel) {
        if (channel == null) return 4;
        switch (channel) {
            case "dev": return 0;
            case "nightly": return 1;
            case "beta": return 2;
            case "rc": return 3;
            default: return -1;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName() : message;
    }

    public enum Channel {
        STABLE("stable", "STABLE"),
        RC("rc", "RC"),
        BETA("beta", "BETA"),
        NIGHTLY("nightly", "NIGHTLY");

        private final String id;
        private final String label;

        Channel(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String getLabel() {
            return this.label;
        }

        private Channel next() {
            switch (this) {
                case STABLE: return RC;
                case RC: return BETA;
                case BETA: return NIGHTLY;
                default: return STABLE;
            }
        }

        private boolean accepts(String version) {
            switch (this) {
                case STABLE:
                    return version.matches("^\\d+\\.\\d+\\.\\d+$");
                case RC:
                    return version.matches("^\\d+\\.\\d+\\.\\d+-rc\\.\\d+$");
                case BETA:
                    return version.matches("^\\d+\\.\\d+\\.\\d+-beta\\.\\d+$");
                case NIGHTLY:
                    return version.matches("^\\d+\\.\\d+\\.\\d+-nightly\\.\\d+$");
                default:
                    return false;
            }
        }

        private static Channel fromId(String id) {
            if (id != null) {
                for (Channel value : values()) {
                    if (value.id.equalsIgnoreCase(id.trim())) return value;
                }
            }
            return NIGHTLY;
        }
    }

    public static final class UpdateInfo {
        public final String version;
        public final String minecraftVersion;
        private final String downloadUrl;
        private final String checksumUrl;
        private final String sha256;
        public final String releaseUrl;
        public final String jarName;

        private UpdateInfo(String version, String minecraftVersion, String downloadUrl,
                           String checksumUrl, String sha256, String releaseUrl, String jarName) {
            this.version = version;
            this.minecraftVersion = minecraftVersion;
            this.downloadUrl = downloadUrl;
            this.checksumUrl = checksumUrl;
            this.sha256 = sha256;
            this.releaseUrl = releaseUrl;
            this.jarName = jarName;
        }
    }

    public static final class CheckResult {
        public final UpdateInfo update;
        public final String message;

        private CheckResult(UpdateInfo update, String message) {
            this.update = update;
            this.message = message;
        }

        private static CheckResult available(UpdateInfo update) {
            return new CheckResult(update, "Update " + update.version + " is available.");
        }

        private static CheckResult upToDate(String message) {
            return new CheckResult(null, message);
        }

        private static CheckResult failure(String message) {
            return new CheckResult(null, message);
        }

        public boolean isUpdateAvailable() {
            return this.update != null;
        }
    }

    public static final class DownloadResult {
        public final Path file;
        public final String message;

        private DownloadResult(Path file, String message) {
            this.file = file;
            this.message = message;
        }

        private static DownloadResult success(Path file) {
            return new DownloadResult(file, "Verified update saved to: " + file.toAbsolutePath());
        }

        private static DownloadResult failure(String message) {
            return new DownloadResult(null, message);
        }

        public boolean isSuccessful() {
            return this.file != null;
        }
    }
}
