# MSM-Fabric-1.14.4

**Cylox Multi Session Manager** — Fabric client mod for Minecraft Java 1.14.4, including MJ Launcher Android.

The Fabric project supplies Minecraft-specific UI and network integration. Shared domain logic is maintained in [MSM-Core](https://github.com/dap144p/MSM-Core) as a Git submodule and is merged into the final mod JAR. Players install **one mod JAR**, not a separate Core mod.

## Current build: 0.1.2

- Adds an `MSM` button to the in-game Pause Menu.
- Opens the manager as a normal Minecraft screen; closing it returns to the previous menu.
- Adds and validates local profiles using Minecraft username and server address.
- Saves a per-profile authentication-mode choice: Microsoft or local/offline-mode.
- Saves, edits, deletes, and searches profiles.
- Shows the selected profile and session state, with a summary of all slot states.
- Displays up to **20 session slots** managed by MSM Core, with pagination that adapts to screen height.
- Supports an independent secondary Java connection using the Minecraft 1.14.4 protocol (protocol 498) for `OFFLINE_LOCAL` profiles.
- Marks a secondary session ONLINE only after the server's Join Game packet is received.
- Handles the protocol keep-alive flow and provides per-session chat receive/send.
- Uses no secondary Minecraft world renderer; the main Minecraft client remains unchanged.
- Stores profiles in `.minecraft/config/msm-fabric.properties`.
- Does not save passwords, Microsoft credentials, access tokens, or session cookies.

## Current limitations

- Secondary connections currently support **Minecraft Java 1.14.4 protocol 498 only**. The server must accept 1.14.4 clients directly or provide compatible server-side protocol support.
- `OFFLINE_LOCAL` only works when the destination server is configured for offline-mode and permits that account name. It does not bypass online-mode authentication.
- Microsoft sign-in for secondary sessions is not implemented yet. Selecting Microsoft is profile metadata only, and Connect reports that limitation instead of attempting an unauthenticated login.
- Secondary sessions do not have a separate world renderer, movement controller, inventory controller, or task automation.
- Automatic reconnect and protocol translation for secondary sessions are not implemented.
- Each active secondary connection currently owns its own PacketLib network event loop. Multi-session resource use still needs device testing, especially on low-memory Android devices.

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

The build resolves `com.github.GeyserMC:MCProtocolLib:1.14.4-2` from JitPack and nests it with its runtime dependencies in the mod JAR. The remapped mod JAR is generated in `build/libs/`. MSM Core classes are included in that JAR and verified by the `check` task.

## Install on MJ Launcher

1. Create or select a Fabric profile for Minecraft **1.14.4**.
2. Install Fabric Loader and Fabric API compatible with 1.14.4.
3. Copy `MSM-Fabric-1.14.4-0.1.2.jar` into that profile's `mods` folder.
4. Launch the game, enter a world or server, and open the Pause Menu.
5. Tap **MSM**, add a profile with **OFFLINE** authentication only when the target server uses offline-mode, select it, and press **CONNECT**.
6. Wait for the slot to show **ONLINE**. Open **CHAT** to read and send messages through the secondary account.

## ViaFabric note

ViaFabric is a separate protocol-translation mod and is not bundled into this project. It may translate the main 1.14.4 connection to supported server protocols. It does not automatically translate the secondary connection; the secondary currently speaks protocol 498 directly.
