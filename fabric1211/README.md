# MSM Fabric 1.21.11

This is the Minecraft 1.21.11 Fabric target for **Cylox Multi Session Manager**.

The original root project remains the Minecraft 1.14.4 target. This directory builds a separate 1.21.11 JAR because Minecraft GUI, Fabric, Java runtime, and protocol APIs are not binary-compatible across those releases.

## Target

- Minecraft Java **1.21.11** only
- Fabric Loader **0.19.5** or newer for 1.21.11
- Fabric API **0.141.6+1.21.11**
- Java **21** or newer at game runtime
- One installable JAR; MSM-Core and protocol runtime libraries are bundled

A build for 1.21.11 does not imply compatibility with earlier 1.21.x patch releases. Each patch must be built and tested against its matching game/Fabric dependencies before it can be listed as supported.

## Features carried over

- Pause-menu MSM entry through Fabric Screen Events
- Account creation, selection, connect/disconnect, and confirmed account removal
- Per-account Control Manage: chat, AutoChat ONCE/REPEAT, AutoReconnect and retry settings
- Server list selection and remembered server address
- Independent secondary Java protocol session; no second world renderer
- Shared MSM-Core profile/session state and shared automation code

Secondary connections currently use OFFLINE_LOCAL only when the target server permits offline-mode accounts. Microsoft secondary authentication is not implemented. Sending unsigned chat can be rejected by servers that require signed player chat.

## Build

Requires JDK 25 for the Gradle/Loom build process; the generated mod bytecode targets Java 21.

From the repository root:

```sh
gradle -p fabric1211 clean check build
```

The remapped JAR is written to `fabric1211/build/libs/`.
