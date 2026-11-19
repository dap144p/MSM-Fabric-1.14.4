# MSM-Fabric-1.14.4

**Cylox Multi Session Manager** — Fabric client mod for Minecraft Java 1.14.4, including MJ Launcher Android.

The Fabric project supplies Minecraft-specific UI and hooks. Shared domain logic is maintained in [MSM-Core](https://github.com/dap144p/MSM-Core) as a Git submodule and is merged into the final mod JAR. Players install **one mod JAR**, not a separate Core mod.

## Current build: 0.1.0

- Adds an `MSM` button to the in-game Pause Menu.
- Opens the manager as a normal Minecraft screen; closing it returns to the previous menu.
- Saves profile metadata: Minecraft username and server address.
- Shows up to **20 session slots** managed by MSM Core.
- Displays each slot's runtime state: OFFLINE, CONNECTING, ONLINE, DISCONNECTED, or FAILED. The current build only creates OFFLINE slots.
- Stores profiles in `.minecraft/config/msm-fabric.properties`.
- Does not save passwords, Microsoft credentials, access tokens, or session cookies.

## Not implemented yet

- Independent secondary Minecraft connections.
- Microsoft/Minecraft authentication for additional accounts.
- Background session packet handling, keep-alive, and world/player state.
- Secondary-session chat, movement, follow, jump, or sneak controls.
- Automatic protocol translation for secondary sessions.

The current session slots are management records only. They do not connect another player to a server or create a second Minecraft network connection.

## Build

Requirements: JDK 17 and the included Gradle wrapper.

Clone with the Core submodule:

```sh
git clone --recurse-submodules https://github.com/dap144p/MSM-Fabric-1.14.4.git
cd MSM-Fabric-1.14.4
./gradlew clean check build
```

For an existing clone, initialize or update Core with:

```sh
git submodule update --init --recursive
```

The remapped mod JAR is generated in `build/libs/`. MSM Core classes are included in that JAR.

## Install on MJ Launcher

1. Create or select a Fabric profile for Minecraft **1.14.4**.
2. Install Fabric Loader and Fabric API compatible with 1.14.4.
3. Copy the built `MSM-Fabric-1.14.4-0.1.0.jar` into that profile's `mods` folder.
4. Launch the game, enter a world or server, and open the Pause Menu.
5. Tap **MSM**.

The current build manages local session slots only. It does not make a second player join the server yet.

## ViaFabric note

ViaFabric is a separate protocol-translation mod and is not bundled into this project. Installing a compatible ViaFabric build may translate the main 1.14.4 connection to supported server protocols. It does not automatically translate future secondary MSM connections; those will need their own integration and tests.
