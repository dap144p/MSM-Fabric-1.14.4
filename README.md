# MSM-Fabric-1.14.4

**Cylox Multi Session Manager** — Fabric client mod for Minecraft Java 1.14.4, including MJ Launcher Android.

The Fabric project supplies Minecraft-specific UI and hooks. Shared domain logic is maintained in [MSM-Core](https://github.com/dap144p/MSM-Core) as a Git submodule and is merged into the final mod JAR. Players install **one mod JAR**, not a separate Core mod.

## Current build: 0.1.2

- Adds an `MSM` button to the in-game Pause Menu.
- Opens the manager as a normal Minecraft screen; closing it returns to the previous menu.
- Adds and validates local profiles using Minecraft username and server address.
- Supports editing and deleting saved profiles, with confirmation before deletion.
- Searches profiles by username or server address.
- Shows the selected profile and session state, with a summary of all slot states.
- Displays up to **20 session slots** managed by MSM Core, with pagination that adapts to screen height.
- Stores profiles in `.minecraft/config/msm-fabric.properties`.
- Does not save passwords, Microsoft credentials, access tokens, or session cookies.

## Current limitations

- Session slots are local management records only. They do not connect another player to a server or create a second Minecraft network connection.
- Independent secondary Minecraft connections and Microsoft/Minecraft authentication for additional accounts are not implemented.
- Background session packet handling, keep-alive, world/player state, secondary-session chat, movement, follow, jump, or sneak controls are not implemented.
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
