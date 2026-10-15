# Gradle Sync Queue

[![CI](https://github.com/i-net-software/gradle-sync-queue/actions/workflows/ci.yml/badge.svg)](https://github.com/i-net-software/gradle-sync-queue/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

IntelliJ IDEA plugin that runs the Gradle syncs of a project one after another instead of all at once.

## Why

When an IDE project links many Gradle builds, IntelliJ IDEA starts a sync for each of them in parallel. Every sync that finds the existing Gradle daemons busy spawns a new one. Measured on a workspace with about 120 linked builds:

| | Gradle daemons spawned by the sync | Memory |
|---|---|---|
| Without plugin | 121 within 7 seconds | > 6 GB, machine nearly out of RAM |
| With plugin | 1, reused for all builds | one daemon |

## How it works

* A `GradleProjectResolverExtension` acquires a per-project FIFO gate in `preImportCheck()`, before the Gradle model fetch action runs, and releases it in `resolveFinished()`. This applies to every Gradle sync, including those started by the IDE itself.
* An `ExternalSystemTaskNotificationListener` releases the gate when a sync fails or is cancelled.
* While a sync waits in the queue it can still be cancelled with the IDE's stop button.

### Borrowed source folders (Eclipse-like module dependencies)

Some builds compile source folders of other projects (`srcDirs '../Other/src'`), often with `exclude`/`include`
patterns. IntelliJ IDEA's own compiler then compiles those folders in several modules and ignores the patterns
([IDEA-169124](https://youtrack.jetbrains.com/issue/IDEA-169124)). Eclipse/Buildship avoids this with project
dependencies in `.classpath`. After every Gradle sync this plugin does the same:

* A source folder outside the module's own Gradle project whose home project has a module is removed and replaced by
  an exported dependency on that module (e.g. `InetServerCore.main` → `Json.main`). The folders come from the Gradle
  model, so this also works when IDEA kept a folder in one module only.
* Project references of the Eclipse `.classpath` (`<classpathentry kind="src" path="/Other"/>`) become module
  dependencies of all source set modules of that project, `exported="true"` included.
* Source sets of the same project that depend on the borrowing one (e.g. `mock` → `main`) get the same dependencies;
  dependencies on modules with test sources only are marked *production on test*.
* Every change is logged (category `de.inetsoftware.gradlesyncqueue`). Turn it off in
  **Settings → Advanced Settings → Gradle Sync Queue**.

## Installation

Download `gradle-sync-queue-<version>.zip` from the [latest release](https://github.com/i-net-software/gradle-sync-queue/releases/latest), then in IntelliJ IDEA 2026.2 or newer: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select the ZIP.

## Usage

* **Gradle Queue** tool window (bottom bar):
  * `[IDEA]` rows are gate entries (every Gradle sync); `[Requested]` rows are refreshes queued by this plugin.
  * **Queue all linked Gradle builds**: refresh every linked build, one after another.
  * **Cancel / release selected**: a waiting sync is cancelled; a sync holding the gate releases it (the sync itself keeps running). Use this if the gate ever gets stuck.
  * **Clear finished** removes finished rows. Only the last 50 finished entries are kept anyway.
* **Refresh Gradle via Queue** in the Project view context menu: refreshes the linked builds containing, or contained in, the selection.
* Logging: category `de.inetsoftware.gradlesyncqueue` (Help → Diagnostic Tools → Debug Log Settings).

## Limitations

* The gate is released at `resolveFinished()`, i.e. while the IDE is still applying the imported data (state "Resolved, importing").
* Daemons are only shared between syncs with the same Gradle version, Gradle JVM and JVM arguments; builds with different settings still get their own daemon.
* Requested refreshes wait at most 60 minutes for the platform's callback before the queue moves on.

## Development

Requirements: JDK 25. The build downloads IntelliJ IDEA 2026.2 (about 1.5 GB) on first use.

```bash
./gradlew check buildPlugin      # tests + build/distributions/gradle-sync-queue-<version>.zip
./gradlew verifyPlugin           # IntelliJ Plugin Verifier against recommended IDE versions
./gradlew runIde                 # sandbox IDE with the plugin
./gradlew runIdeWithWorkspace    # sandbox IDE with the plugin and Multi-Project Workspace
```

`runIde` always forces the plugin under development to be enabled (`-Didea.required.plugins.id`), so it cannot be disabled in the sandbox's plugin settings.

## Release

1. Move the entries under `[Unreleased]` in [CHANGELOG.md](CHANGELOG.md) to a new version section.
2. Push a tag `v<version>`, e.g. `v1.0.0` (`v1.1.0-beta.1` publishes to the `beta` channel).
3. The [release workflow](.github/workflows/release.yml) runs the tests, creates a GitHub release with the plugin ZIP and, if the `PUBLISH_TOKEN` secret is set, publishes to the JetBrains Marketplace.

Repository secrets: `PUBLISH_TOKEN` (JetBrains Marketplace token; the very first Marketplace upload has to be done manually on plugins.jetbrains.com); optional for plugin signing: `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`.

## License

[Apache License 2.0](LICENSE) © i-net software
