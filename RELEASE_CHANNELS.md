# Release channels

MSM uses staged releases so an in-progress build is not presented as a stable mod.

## Channel definitions

| Channel | Source | Distribution | Readiness |
|---|---|---|---|
| Development | Pull requests, feature branches, and ordinary CI builds | Temporary GitHub Actions artifact | Active development; may be incomplete |
| Nightly | `development` branch | Automatically created GitHub prerelease with one JAR | Latest development snapshot; may regress |
| Beta | `vX.Y.Z-beta.N` tag | GitHub prerelease with one JAR | Main features are implemented and initial in-game tests have passed |
| Release Candidate | `vX.Y.Z-rc.N` tag | GitHub prerelease with one JAR | Final compatibility and regression validation |
| Stable | `vX.Y.Z` tag | Regular GitHub release with one JAR | Tested release suitable for normal use |

## Build behavior

- Every pull request targeting `main` or `development` runs `clean check build` and uploads a Development artifact.
- Pushes to `feat/**` and `main` run the same checks and produce a Development artifact.
- Every push to `development` builds a Nightly version and publishes it as a prerelease. A manual workflow run with the `development` branch selected also publishes a Nightly build.
- A normal push to `main` does **not** create a Beta or Stable release.
- Version tags are validated by CI. Unsupported tag formats fail the build instead of publishing a misleading release.
- A local build uses `mod_version` from `gradle.properties`. CI sets the version suffix so the JAR filename and Fabric mod metadata identify the channel.

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

- The build passes the repository CI checks.
- A secondary account can connect and reach ONLINE in Minecraft 1.14.4.
- Account joins and reconnects follow the configured account order.
- AutoChat ONCE and REPEAT behavior is verified in-game.
- Manual Disconnect prevents automatic reconnection.
- Main Minecraft session behavior remains unaffected.

Before creating an RC tag, repeat the checks on the supported launcher/device combination and verify there are no known release-blocking regressions.

Before creating a Stable tag, confirm that the RC has passed real in-game testing. A successful CI build alone is not sufficient to mark a release Stable.

## Download and install

Release assets contain one installable `.jar`. Put that JAR in the Minecraft 1.14.4 Fabric profile's `mods` folder. Development artifacts are packaged by GitHub Actions as an artifact download; extract that artifact archive and install the JAR inside it.

All prerelease channels are explicitly marked as prereleases. Stable is published as a normal GitHub release and becomes the latest release.
