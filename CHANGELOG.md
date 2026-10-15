# Changelog

## [Unreleased]

## [1.1.1] - 2026-10-15

### Fixed

- *Production on test* dependencies are set through the workspace model instead of an internal API (Plugin Verifier).

## [1.1.0] - 2026-10-15

### Added

- Source folders a build borrows from other Gradle projects (`srcDirs '../Other/src'`) are replaced by an (exported) dependency on that project's module after each sync, like Eclipse/Buildship. The folders are taken from the Gradle model, so it also works when IDEA kept a folder in one module only. Avoids compiling them twice and the ignored exclude/include patterns of [IDEA-169124](https://youtrack.jetbrains.com/issue/IDEA-169124).
- Project references of the Eclipse `.classpath` (`kind="src" path="/Other"`) become module dependencies, including `exported="true"`.
- Other source sets of the same project that depend on the borrowing one (e.g. `mock` → `main`) get those dependencies directly; dependencies on modules with test sources only are marked *production on test*.
- Can be turned off in Settings → Advanced Settings → Gradle Sync Queue.

## [1.0.0] - 2026-10-14

### Added

- FIFO gate per IDE project that serializes Gradle model resolution, including syncs started by IntelliJ IDEA itself.
- **Gradle Queue** tool window showing queued, running and finished syncs; cancel or force-release entries; clear finished entries.
- **Refresh Gradle via Queue** action in the Project view context menu.
