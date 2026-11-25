# Multi Sessions Manager (MSM)

**Multi Sessions Manager (MSM)** is a client-side Fabric mod for managing multiple independent Minecraft Java sessions from one Minecraft client.

This repository builds two separate, version-specific mod JARs:

| Minecraft | Integration source | Java runtime | Secondary-session protocol | Output |
|---|---|---:|---:|---|
| 1.14.4 | Repository root | Java 8 | 498 | `build/libs/MSM-Fabric-<version>-1.14.4.jar` |
| 1.21.8 | `fabric-1.21.8/` | Java 21 | 772 | `fabric-1.21.8/build/libs/MSM-Fabric-<version>-1.21.8.jar` |

The JARs are not interchangeable. Each targets one Minecraft/Fabric API generation and must be installed only in the matching profile.

The repository is named **Multi-Sessions-Manager-Fabric**. The product short name is **MSM**, and installable files use the **MSM-Fabric** prefix.

## Architecture and Core integration

MSM is split into three responsibility layers:

```text
MSM-Fabric application
|
+-- Version-specific Minecraft integration
|   +-- Fabric entrypoint and mixins
|   +-- Screens, widgets, and user interaction
|   +-- Minecraft packet adaptation and secondary-session networking
|   +-- Game-version-specific dependencies and compatibility
|
+-- Shared MSM-Fabric application logic
|   +-- AutoChat / AutoReconnect settings and scheduler
|   +-- Server template selection
|   +-- Connection orchestration and chat presentation
|
+-- Dapdep Core: modules/msm-core
    +-- SessionProfile / SessionAuthMode
    +-- SessionProfileStore
    +-- SessionManager / SessionState
```

### What lives where

- **Dapdep Core** owns the platform-independent MSM profile and session-domain layer.
- **MSM-Fabric** owns all Minecraft/Fabric integration: UI, mixins, packet handling, independent network connections, AutoChat, AutoReconnect, server selection, and game-version adapters.
- The Core module is included as a Git submodule at `dapdep-core/`, pinned to a specific Dapdep Core commit.
- Both Gradle builds include `dapdep-core/modules/msm-core` as project `:msm-core`.
- Each final mod JAR bundles the Core module classes. Players do **not** install a separate Core mod or Core JAR.

The shared module preserves the existing `dev.cylox.msm` package and profile format for compatibility. It has no Minecraft, Fabric, authentication-provider, or network dependency.

See [Dapdep Core](https://github.com/dap144p/dapdep-core) and its [architecture document](https://github.com/dap144p/dapdep-core/blob/main/ARCHITECTURE.md).

## Supported workflow and features

- Adds an **MSM** button to the in-game Pause Menu.
- Account manager with per-account status, saved server selection, and account removal confirmation.
- Multiple independent protocol-only secondary Java sessions.
- A session is marked ONLINE only after the Minecraft Join Game packet is received.
- Per-account chat history, normal chat messages, and slash-command sending.
- Manual connection requests and AutoReconnect attempts are serialized in saved account-list order; the next account starts after the active connection reaches Join Game or disconnects.
- Per-account AutoChat rules with `ONCE` and `REPEAT` modes. ONCE tasks are scheduled after Join Game; queued automated sends are serialized.
- Per-account AutoReconnect with configurable delay, attempt limit, and optional exponential backoff. Reconnect attempts are serialized in saved account-list order.
- Manual Disconnect removes the account from the reconnect queue.
- Removing an account clears its profile and saved AutoChat / AutoReconnect settings together. Active sessions must be disconnected first.
- No secondary Minecraft world renderer is created; the primary Minecraft client remains the active rendered game.
- Profiles contain username, server address, and authentication-mode metadata; passwords, Microsoft credentials, access tokens, and session cookies are not stored.

## Version-specific implementation

### Minecraft 1.14.4

The original Fabric project remains at the repository root and retains the protocol 498 secondary-session implementation.

- Build with JDK 17; produced mod bytecode targets Java 8.
- Uses the root `gradlew` wrapper.
- Requires a Minecraft 1.14.4 Fabric profile with compatible Fabric Loader and Fabric API.

### Minecraft 1.21.8

The newer Fabric project is isolated in `fabric-1.21.8/` and uses the 1.21.8 Yarn/Fabric APIs.

- Requires JDK 21 for build and runtime.
- Uses its own Gradle 8.14.3 wrapper.
- The independent secondary-session connection uses MCProtocolLib's 1.21.7 release codec because Minecraft 1.21.7 and 1.21.8 share protocol 772.
- Requires a Minecraft 1.21.8 Fabric profile with compatible Fabric Loader and Fabric API.

Both versions retain the Manager and Control Manage workflow: account list, connect/disconnect, chat, AutoChat, AutoReconnect, saved-server picker, and remove-account confirmation.

## Current limitations

- Secondary connections support only the protocol listed for the selected build: protocol 498 for 1.14.4 and protocol 772 for 1.21.8. The destination server must accept that protocol directly or provide compatible server-side protocol support.
- `OFFLINE_LOCAL` works only when the destination server is configured for offline-mode and permits that account name. It does not bypass online-mode authentication.
- Microsoft sign-in for secondary sessions is not implemented yet. Selecting Microsoft is profile metadata only; Connect reports the limitation.
- Offline secondary chat is unsigned. Servers that enforce signed secure chat may reject messages.
- Secondary sessions have no separate world renderer, movement controller, or inventory controller.
- Automatic protocol translation for secondary sessions is not implemented. ViaFabric/ViaVersion on the primary client does not automatically translate the independent secondary connection.
- Multiple live connections still require real-device testing, especially on low-memory Android devices.

## Release channels

**Current project stage: Development / Nightly.** A successful CI build is not equivalent to in-game validation. The project is not marked Beta, Release Candidate, or Stable until the corresponding testing gates are met.

| Channel | Source | Meaning |
|---|---|---|
| Development | Pull requests, feature branches, and `main` CI | Temporary CI artifacts for testing |
| Nightly | `development` branch | Automatically published prerelease; may regress |
| Beta | `vX.Y.Z-beta.N` tag | Main features implemented and initial in-game tests passed |
| Release Candidate | `vX.Y.Z-rc.N` tag | Final compatibility and regression validation |
| Stable | `vX.Y.Z` tag | Validated release for normal use |

Full branch/tag behavior and promotion gates: [RELEASE_CHANNELS.md](RELEASE_CHANNELS.md).

## Build from source

### Requirements

- Git with submodule support.
- JDK 17 for the Minecraft 1.14.4 build.
- JDK 21 for the Minecraft 1.21.8 build.
- Network access to the configured Gradle and Maven repositories on first build.

### Clone

Clone recursively so the exact Dapdep Core revision is available:

```sh
git clone --recurse-submodules https://github.com/dap144p/Multi-Sessions-Manager-Fabric.git
cd Multi-Sessions-Manager-Fabric
```

For an existing checkout:

```sh
git submodule update --init --recursive
```

### Build Minecraft 1.14.4

Use JDK 17:

```sh
./gradlew --no-daemon clean check build
```

Output:

```text
build/libs/MSM-Fabric-<version>-1.14.4.jar
```

### Build Minecraft 1.21.8

Use JDK 21:

```sh
cd fabric-1.21.8
./gradlew --no-daemon clean check build
```

Output:

```text
fabric-1.21.8/build/libs/MSM-Fabric-<version>-1.21.8.jar
```

## CI verification

GitHub Actions checks both targets in one workflow:

1. Checks out the repository with recursive submodules.
2. Builds and checks Minecraft 1.14.4 using JDK 17.
3. Builds and checks Minecraft 1.21.8 using JDK 21.
4. Verifies required MSM Core/application classes and selected nested runtime dependencies in each remapped JAR.
5. Uploads separate Development artifacts or publishes the configured release channel.

The Dapdep Core repository has its own independent `clean check` workflow. Passing both workflows verifies the configured automated checks for those commits; it does not replace in-game testing on actual launchers and devices.

## Install on MJ Launcher or another Fabric launcher

1. Create a Fabric profile for **Minecraft 1.14.4** or **Minecraft 1.21.8**.
2. Install Fabric Loader and Fabric API matching that exact game version.
3. Select the matching Java runtime: Java 8 for 1.14.4 or Java 21 for 1.21.8.
4. Download the matching MSM JAR from the [GitHub Releases](https://github.com/dap144p/Multi-Sessions-Manager-Fabric/releases) page or the relevant Actions artifact.
5. Copy that single JAR into the selected profile's `mods` folder.
6. Launch Minecraft, open the Pause Menu, and select **MSM**.
7. Add an account. Choose **OFFLINE** only when the destination server accepts offline-mode accounts, then connect.

Do not install both version-specific JARs into one profile. No separate Dapdep Core JAR is required.

## Updating the shared Core revision

The Core repository is pinned as a Git submodule. To update it safely:

1. Choose the intended Dapdep Core commit.
2. Update the submodule checkout to that commit.
3. Run both version builds and checks.
4. Confirm the final JARs still contain the required Core classes.
5. Commit the changed `dapdep-core` gitlink in this repository.

Do not update the Core pin without validating both consumers. Keep the previous gitlink revision in Git history for rollback.

## Related repositories

- [Dapdep Core](https://github.com/dap144p/dapdep-core) — shared modular foundation and MSM domain module.
- [MSM-Core legacy repository](https://github.com/dap144p/MSM-Core) — historical source repository retained as a migration backup; active development is in Dapdep Core.
