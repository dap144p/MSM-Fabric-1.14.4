# Release channels

Multi Sessions Manager (MSM) uses staged releases so an in-progress build is not presented as a stable mod.

## Channel definitions

| Channel | Source | Distribution | Readiness |
|---|---|---|---|
| Development | Pull requests, feature branches, and ordinary CI builds | Two separate GitHub Actions artifacts, one JAR per Minecraft version | Active development; may be incomplete |
| Nightly | `development` branch | Automatically created GitHub prerelease with two separate JAR assets | Latest development snapshot; may regress |
| Beta | `vX.Y.Z-beta.N` tag | GitHub prerelease with two separate JAR assets | Main features are implemented and initial in-game tests have passed |
| Release Candidate | `vX.Y.Z-rc.N` tag | GitHub prerelease with two separate JAR assets | Final compatibility and regression validation |
| Stable | `vX.Y.Z` tag | Regular GitHub release with two separate JAR assets | Tested release suitable for normal use |

## Build behavior

- Every pull request targeting `main` or `development` runs `clean check build` and uploads two Development artifacts: one 1.14.4 JAR and one 1.21.8 JAR.
- Pushes to `feat/**` and `main` run the same checks and produce the same two separate Development artifacts.
- Every push to `development` builds a Nightly version and publishes it as a prerelease. A manual workflow run with the `development` branch selected also publishes a Nightly build.
- A normal push to `main` does **not** create a Beta or Stable release.
- Version tags are validated by CI. Unsupported tag formats fail the build instead of publishing a misleading release.
- A local build uses `mod_version` from `gradle.properties`. CI sets the version suffix so the JAR filename and Fabric mod metadata identify the channel.
- No combined multi-version mod ZIP is produced or published; the two installable files remain separate JARs.

## Version tag format

Use the following formats and push the tag to GitHub:

| Release | Tag example | Result |
|---|---|---|
| Beta | `v0.1.2-beta.1` | Beta prerelease |
| Next Beta | `v0.1.2-beta.2` | New Beta prerelease |
| Release Candidate | `v0.1.2-rc.1` | RC prerelease |
| Stable | `v0.1.2` | Stable release |

Example:

```sh
git checkout development
git pull
git tag v0.1.2-beta.1
git push origin v0.1.2-beta.1
```

After the Beta has been tested, create an RC tag from the commit selected for final validation. Publish the plain `vX.Y.Z` tag only after that candidate passes the required checks and in-game testing.

Do not reuse an existing tag. Increment the prerelease number for another Beta or RC build.

## Promotion checklist

Before creating a Beta tag:

- The root Minecraft 1.14.4 build and `fabric-1.21.8/` build both pass repository CI checks.
- Install the matching JAR and runtime for each supported Minecraft version.
- A secondary account can connect and reach ONLINE on an offline-mode test server for each supported version.
- Manual connection and reconnect attempts follow the saved account order.
- AutoChat ONCE runs after Join Game, then ONCE and REPEAT rules follow account/rule order.
- Manual Disconnect prevents automatic reconnection.
- Removing an account clears its saved profile and automation settings.
- The primary Minecraft session remains unaffected.

Before creating an RC tag, repeat the checks on the supported launcher/device combination for both game versions and verify there are no known release-blocking regressions.

Before creating a Stable tag, confirm that the RC has passed real in-game testing. A successful CI build alone is not sufficient to mark a release Stable.

## Download and install

Each release publishes exactly two separate installable JAR assets:

- `MSM-Fabric-<version>-1.14.4.jar` — Minecraft 1.14.4 Fabric profile; Java 8 runtime.
- `MSM-Fabric-<version>-1.21.8.jar` — Minecraft 1.21.8 Fabric profile; Java 21 runtime.

Install only the JAR matching the selected Minecraft profile. Do not place both JARs in the same profile. There is no combined ZIP distribution.

All prerelease channels are explicitly marked as prereleases. Stable is published as a normal GitHub release and becomes the latest release.
