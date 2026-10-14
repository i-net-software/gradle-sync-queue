package de.inetsoftware.gradlesyncqueue

import java.nio.file.Path

object GradleRoots {
    /**
     * Maps selected paths to linked Gradle roots: a path inside a root maps to the most specific root
     * containing it; a directory above roots maps to every root below it.
     */
    fun match(selected: Collection<Path>, roots: Collection<String>): List<String> {
        val normalized = roots.mapNotNull { root ->
            runCatching { Path.of(root).toAbsolutePath().normalize() to root }.getOrNull()
        }
        return selected.flatMap { raw ->
            val path = raw.toAbsolutePath().normalize()
            val containing = normalized.filter { (root, _) -> path.startsWith(root) }
                .maxByOrNull { (root, _) -> root.nameCount }
            if (containing != null) listOf(containing.second)
            else normalized.filter { (root, _) -> root.startsWith(path) }.map { it.second }
        }.distinct()
    }
}
