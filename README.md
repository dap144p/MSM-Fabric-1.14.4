# MSM-Fabric-1.14.4

**Cylox Multi Session Manager** — Fabric client mod for Minecraft Java 1.14.4, including MJ Launcher Android.

The Fabric project supplies Minecraft-specific UI and hooks. Shared domain logic is maintained in [MSM-Core](https://github.com/dap144p/MSM-Core) as a Git submodule and is merged into the final mod JAR. Players install **one mod JAR**, not a separate Core mod.

## Current build: 0.1.2

- Adds an `MSM` button to the in-game Pause Menu and manages up to **20 saved session profiles**.
- Saves per-profile username, server address, and authentication mode (Microsoft or local/offline-mode).
- Adds an independent secondary network connection for profiles explicitly set to `OFFLINE_LOCAL`.
- Supports per-session connect/disconnect, server chat and commands, position updates, keep-alive responses, W/A/S/D movement, jump, sneak, and sprint controls.
- Keeps secondary sessions packet-driven; it does not create another rendered Minecraft world or viewport.
- Stores profiles in `.minecraft/config/msm-fabric.properties`.
- Does not save passwords, Microsoft credentials, access tokens, or session cookies.
- Existing profiles without an authentication-mode field default to Microsoft.

## Current limitations

- `OFFLINE_LOCAL` is only for a local/offline-mode server operated or explicitly configured by the player. A server that requests online authentication is rejected; this mode does not bypass online-mode authentication.
- Microsoft sign-in and authenticated Microsoft secondary connections are not implemented yet.
- The secondary protocol implementation targets Minecraft **1.14.4 (protocol 498)**. It does not perform ViaVersion/ViaFabric translation for secondary sessions.
- Secondary sessions do not render chunks, entities, inventory screens, or a second player view. They are background packet connections controlled from the MSM screen.
- Movement buttons send discrete position updates; they are not a streamed keyboard-input or full physics simulation.

Editing a profile is unavailable while its slot is CONNECTING or ONLINE. A successfully edited profile is reset to OFFLINE because it represents a new profile record.

## Build

Requirements: JDK 17 and the included Gradle wrapper.

```sh
git clone --recurse-submodules https://github.com/dap144p/MSM-Fabric-1.14.4.git
cd MSM-Fabric-1.14.4
./gradlew clean check build
```

For an existing clone, initialize or update Core with:

```sh
git submodule update --init --recursive
```

The remapped mod JAR is generated in `build/libs/`. MSM Core classes are included in that JAR and verified by the `check` task.

## Install on MJ Launcher

1. Create or select a Fabric profile for Minecraft **1.14.4**.
2. Install Fabric Loader and Fabric API compatible with 1.14.4.
3. Copy `MSM-Fabric-1.14.4-0.1.2.jar` into that profile's `mods` folder.
4. Launch the game, enter a world or server, and open the Pause Menu.
5. Tap **MSM**.

## ViaFabric note

ViaFabric is a separate protocol-translation mod and is not bundled into this project. Installing a compatible ViaFabric build may translate the main 1.14.4 connection to supported server protocols. It does not automatically translate future secondary MSM connections; those will need their own integration and tests.
