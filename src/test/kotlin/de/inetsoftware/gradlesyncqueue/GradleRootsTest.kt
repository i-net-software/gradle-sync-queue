package de.inetsoftware.gradlesyncqueue

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class GradleRootsTest {
    private val roots = listOf("/ws/a", "/ws/b/c", "/ws/a/nested")

    private fun match(vararg selected: String) = GradleRoots.match(selected.map { Path.of(it) }, roots)

    @Test fun `file inside a root maps to that root`() = assertEquals(listOf("/ws/a"), match("/ws/a/src/Main.kt"))

    @Test fun `most specific containing root wins`() = assertEquals(listOf("/ws/a/nested"), match("/ws/a/nested/build.gradle"))

    @Test fun `parent directory maps to all roots below it`() =
        assertEquals(listOf("/ws/a", "/ws/b/c", "/ws/a/nested"), match("/ws"))

    @Test fun `unrelated path maps to nothing`() = assertEquals(emptyList<String>(), match("/other"))

    @Test fun `sibling prefix is not a match`() = assertEquals(emptyList<String>(), match("/ws/ab"))

    @Test fun `duplicates are removed`() = assertEquals(listOf("/ws/a"), match("/ws/a/x", "/ws/a/y"))
}
