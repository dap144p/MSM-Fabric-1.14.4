# MSM-Fabric-1.14.4

**Cylox Multi Session Manager** — Fabric client mod foundation for Minecraft Java 1.14.4, including MJ Launcher Android.

## Current build: 0.1.0

- Adds an `MSM` button to the in-game Pause Menu.
- Opens the manager as a normal Minecraft screen; closing it returns to the previous menu.
- Saves profile metadata: Minecraft username and server address.
- Stores profiles in `.minecraft/config/msm-fabric.properties`.
- Does not save passwords, Microsoft credentials, access tokens, or session cookies.
- Profile status is explicitly shown as **OFFLINE**. Saving a profile does not connect an account.

## Not implemented yet

- Independent secondary Minecraft connections.
- Microsoft/Minecraft authentication for additional accounts.
- Background session packet handling, keep-alive, and world/player state.
- Secondary-session chat, movement, follow, jump, or sneak controls.
- Automatic protocol translation for secondary sessions.

These pieces need a separate client/network implementation. The Pause Menu and profile list are not a working multi-account connection by themselves.

## Build

Requirements: JDK 17 and Gradle 8.7 (the Gradle wrapper is included).

```sh
./gradlew clean check build
```

The remapped mod JAR is generated in `build/libs/`.

## Install on MJ Launcher

1. Create or select a Fabric profile for Minecraft **1.14.4**.
2. Install Fabric Loader and Fabric API compatible with 1.14.4.
3. Copy the built `MSM-Fabric-1.14.4-0.1.0.jar` into that profile's `mods` folder.
4. Launch the game, enter a world or server, and open the Pause Menu.
5. Tap **MSM** in the upper-left corner.

The current build only saves profile details. It does not make a second player join the server yet.

## ViaFabric note

ViaFabric is a separate protocol-translation mod and is not bundled into this project. Installing a compatible ViaFabric build may translate the main 1.14.4 connection to supported server protocols. It does not automatically translate future secondary MSM connections; those will need their own integration and tests.
