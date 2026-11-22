# MSM-Fabric-1.14.4

**Cylox Multi Session Manager** — Fabric client mod for Minecraft Java 1.14.4, including MJ Launcher Android.

The Fabric project supplies Minecraft-specific UI and hooks. Shared domain logic is maintained in [MSM-Core](https://github.com/dap144p/MSM-Core) as a Git submodule and is merged into the final mod JAR. Players install **one mod JAR**, not a separate Core mod.

## Current build: 0.1.2

- Adds an `MSM` button to the in-game Pause Menu.
- Adds an active-session control panel when a saved profile matches the current Minecraft username and server exactly. It sends chat/commands through the existing vanilla connection, shows player coordinates/health/orientation, and provides movement toggles.
- Opens the manager as a normal Minecraft screen; closing it returns to the previous menu.
- Adds and validates local profiles using Minecraft username and server address.
- Saves a per-profile authentication-mode choice: Microsoft or local/offline-mode.
- Supports editing and deleting saved profiles, with confirmation before deletion.
- Searches profiles by username or server address.
- Shows the selected profile and session state, with a summary of all slot states.
- Displays up to **20 session slots** managed by MSM Core, with pagination that adapts to screen height.
- Stores profiles in `.minecraft/config/msm-fabric.properties`.
- Does not save passwords, Microsoft credentials, access tokens, or session cookies.
- Existing profiles without an authentication-mode field default to Microsoft.

## Current limitations

- Session slots are local management records only. They do not connect another player to a server or create a second Minecraft network connection.
- Selecting Microsoft or local/offline-mode is profile metadata only. Microsoft sign-in and independent secondary Minecraft connections are not implemented yet.
- The control panel controls only the already-active main Minecraft session. It does not create or authenticate additional Minecraft connections; other saved profiles remain local management records.
- Movement toggles are released when the control panel closes or the game window loses focus.
- Local/offline-mode is intended only for a local/offline-mode server operated or explicitly configured by the player; it does not bypass online-mode authentication.
- Automatic protocol translation for secondary sessions is not implemented.

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
