# Multi Sessions Manager (MSM)

**Multi Sessions Manager (MSM)** is a client-side Fabric mod for Minecraft Java. The repository builds two separate, version-specific mod JARs for Minecraft 1.14.4 and 1.21.8.

The repository is named **Multi-Sessions-Manager-Fabric**. The product's short name is **MSM**, and installable files use the **MSM-Fabric** prefix.

| Minecraft | Module | Java runtime | Secondary-session protocol | JAR filename |
|---|---|---:|---:|---|
| 1.14.4 | Repository root | Java 8 | 498 | `MSM-Fabric-<version>-1.14.4.jar` |
| 1.21.8 | `fabric-1.21.8/` | Java 21 | 772 | `MSM-Fabric-<version>-1.21.8.jar` |

The JARs are version-specific. Install only the JAR matching the Minecraft profile; a single Fabric JAR cannot safely target both game versions because their runtime and client APIs differ.

The Minecraft-specific UI and networking live in each version module. Shared profile/session logic comes from [Dapdep Core MSM module](https://github.com/dap144p/dapdep-core/tree/main/modules/msm-core), and the version-independent AutoChat / AutoReconnect scheduler is reused from the root source tree. Players install one MSM JAR per Minecraft profile, not a separate Core mod.

## Release stage

**Current stage: Development / Nightly.** Multi-account behavior still needs in-game validation on both supported versions. This project is not marked Beta, Release Candidate, or Stable yet.

| Channel | What it means |
|---|---|
| Development | Temporary CI builds from pull requests and feature branches. Intended for active development and testing. |
| Nightly | Automatically published prerelease builds from the `development` branch. May contain regressions or unfinished work. |
| Beta | Versioned prerelease created from a `vX.Y.Z-beta.N` tag after the main features pass initial testing. |
| Release Candidate | Versioned prerelease created from a `vX.Y.Z-rc.N` tag for final validation before Stable. |
| Stable | Final release created from a `vX.Y.Z` tag only after the release candidate is validated. |

See [RELEASE_CHANNELS.md](RELEASE_CHANNELS.md) for the branch, tag, build, and promotion rules.

## Features

- Adds an **MSM** button to the in-game Pause Menu.
- Responsive account manager with saved server selection, per-account status, and account removal confirmation.
- Supports multiple independent protocol-only secondary Java sessions.
- Marks a session ONLINE only after the Minecraft Join Game packet is received.
- Per-account chat history, normal chat messages, and slash-command sending.
- Manual connection requests and AutoReconnect attempts are serialized in saved account-list order; the next account starts after the active connection reaches Join Game or disconnects.
- Per-account AutoChat rules with `ONCE` and `REPEAT` modes. ONCE tasks are scheduled after Join Game; queued automated sends are serialized.
- Per-account AutoReconnect with configurable delay, attempt limit, and optional exponential backoff. Reconnect attempts are serialized in saved account-list order. Manual Disconnect removes the account from the reconnect queue.
- Removing an account clears its profile and saved AutoChat / AutoReconnect settings together. Active sessions must be disconnected first.
- Uses no secondary Minecraft world renderer; the primary Minecraft client remains unchanged.
- Does not save passwords, Microsoft credentials, access tokens, or session cookies.
- Keeps profiles in the shared MSM-Core format so the two game-version builds can read the same profile configuration when they use the same Minecraft config directory.

## Version-specific details

### Minecraft 1.14.4

The original Fabric project remains at the repository root and retains its existing client integration and protocol 498 secondary-session implementation.

- Build: `./gradlew clean check build`
- Output: `build/libs/MSM-Fabric-<version>-1.14.4.jar`
- Requires a Minecraft 1.14.4 Fabric profile and compatible Fabric Loader / Fabric API.
- Java 8 is the target runtime for Minecraft 1.14.4.

### Minecraft 1.21.8

The new Fabric project is isolated in `fabric-1.21.8/`. Its client UI and networking use the 1.21.8 Yarn/Fabric APIs and Java 21. The independent secondary-session connection uses MCProtocolLib's 1.21.7 release codec because Minecraft 1.21.7 and 1.21.8 share protocol 772.

- Build: `cd fabric-1.21.8 && ./gradlew clean check build`
- Output: `fabric-1.21.8/build/libs/MSM-Fabric-<version>-1.21.8.jar`
- Requires a Minecraft 1.21.8 Fabric profile, compatible Fabric Loader / Fabric API, and Java 21 runtime.

The new version keeps the Manager and Control Manage workflow: account list, connect / disconnect, chat, AutoChat, AutoReconnect, saved-server picker, and remove-account confirmation.

## Current limitations

- Secondary connections currently support only the protocol listed for the selected build: protocol 498 for 1.14.4 and protocol 772 for 1.21.8. The destination server must accept that protocol directly or provide compatible server-side protocol support.
- `OFFLINE_LOCAL` works only when the destination server is configured for offline-mode and permits that account name. It does not bypass online-mode authentication.
- Microsoft sign-in for secondary sessions is not implemented yet. Selecting Microsoft is profile metadata only; Connect reports the limitation.
- Offline secondary chat is unsigned. Servers that enforce signed secure chat may reject messages.
- Secondary sessions have no separate world renderer, movement controller, or inventory controller.
- Automatic protocol translation for secondary sessions is not implemented. ViaFabric/ViaVersion on the primary client does not automatically translate the independent secondary connection.
- Multiple live connections still require real-device testing, especially on low-memory Android devices.

## Build requirements

- Minecraft 1.14.4 module: JDK 17 for building, with Java 8 bytecode target.
- Minecraft 1.21.8 module: JDK 21 and its included Gradle 8.14.3 wrapper.
- Initialize the Dapdep Core submodule when cloning:

~~~sh
git clone --recurse-submodules https://github.com/dap144p/Multi-Sessions-Manager-Fabric.git
~~~

For an existing clone:

~~~sh
git submodule update --init --recursive
~~~

Build 1.14.4:

~~~sh
./gradlew clean check build
~~~

Build 1.21.8:

~~~sh
cd fabric-1.21.8
./gradlew clean check build
~~~

GitHub Actions builds both versions. Nightly, Beta, RC, and Stable releases publish two separate JAR assets—one for each Minecraft version. Development builds also expose the two JARs as separate CI artifacts. No combined mod ZIP is published.

## Install on MJ Launcher or another Fabric launcher

1. Create a Fabric profile for **Minecraft 1.14.4** or **Minecraft 1.21.8**.
2. Install Fabric Loader and Fabric API matching that exact game version.
3. Select the correct Java runtime: Java 8 for 1.14.4 or Java 21 for 1.21.8.
4. Download the matching MSM JAR. Do not put the 1.14.4 JAR into a 1.21.8 profile, or vice versa.
5. Copy that single JAR into the profile's `mods` folder.
6. Launch Minecraft and open the Pause Menu, then select **MSM**.
7. Add an account, choose **OFFLINE** only when the destination server accepts offline-mode accounts, then connect.
