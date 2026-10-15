# Changelog

## [Unreleased]

### Added

- FIFO gate per IDE project that serializes Gradle model resolution, including syncs started by IntelliJ IDEA itself.
- **Gradle Queue** tool window showing queued, running and finished syncs; cancel or force-release entries; clear finished entries.
- **Refresh Gradle via Queue** action in the Project view context menu.
- Source folders borrowed from other Gradle projects (`srcDirs '../Other/src'`) are replaced by a dependency on that project's module after each sync, like Eclipse/Buildship; project references of the Eclipse `.classpath` become module dependencies too. Avoids compiling them twice and the ignored exclude/include patterns of IDEA-169124. Advanced Settings → Gradle Sync Queue.
