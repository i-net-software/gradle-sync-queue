package de.inetsoftware.gradlesyncqueue

import org.jetbrains.jps.model.java.impl.compiler.ProcessorConfigProfileImpl
import org.junit.Assert.*
import org.junit.Test

class AnnotationProcessingProfilesTest {
    private val common = AnnotationProcessingProfiles.Spec("/processor.jar", emptyMap())

    @Test fun `repair preserves output directories and settings without mutating existing profile`() {
        val existing = ProcessorConfigProfileImpl("Gradle Imported").apply {
            setProcessorPath(common.path)
            setGeneratedSourcesDirectoryName("custom/generated", false)
            setGeneratedSourcesDirectoryName("custom/generated-tests", true)
            isUseProcessorModulePath = true
            isProcOnly = true
            addProcessor("example.Processor")
            addModuleName("B.main")
            addModuleName("Detached.main")
        }
        val repaired = AnnotationProcessingProfiles.reconcile(
            listOf(existing), mapOf("A.main" to common, "B.main" to common),
        ).single()

        assertEquals("custom/generated", repaired.getGeneratedSourcesDirectoryName(false))
        assertEquals("custom/generated-tests", repaired.getGeneratedSourcesDirectoryName(true))
        assertTrue(repaired.isUseProcessorModulePath)
        assertTrue(repaired.isProcOnly)
        assertEquals(setOf("example.Processor"), repaired.processors)
        assertEquals(setOf("A.main", "B.main"), repaired.moduleNames)
        assertEquals(setOf("B.main", "Detached.main"), existing.moduleNames)
        assertNotSame(existing, repaired)
    }

    @Test fun `single build refresh restores modules lost by IDEA`() {
        val overwritten = ProcessorConfigProfileImpl("Gradle Imported").apply { addModuleName("B.main") }
        val declarations = mapOf("A.main" to common, "B.main" to common)
        val repaired = AnnotationProcessingProfiles.reconcile(listOf(overwritten), declarations)
        assertEquals(setOf("A.main", "B.main"), repaired.single().moduleNames)
        // Repeated imports, in either order, must not change the result.
        assertEquals(repaired, AnnotationProcessingProfiles.reconcile(repaired, declarations.toList().reversed().toMap()))
    }

    @Test fun `settings are copied only from profiles with matching processor path and options`() {
        val existing = ProcessorConfigProfileImpl("Gradle Imported").apply {
            setProcessorPath(common.path)
            setOption("mode", "old")
            setGeneratedSourcesDirectoryName("custom/generated", false)
            addModuleName("A.main")
        }
        val declarations = mapOf(
            "A.main" to common.copy(options = mapOf("mode" to "new")),
            "B.main" to common.copy(path = "/other.jar", options = mapOf("mode" to "old")),
            "C.main" to common.copy(options = mapOf("mode" to "old")),
        )
        val repaired = AnnotationProcessingProfiles.reconcile(listOf(existing), declarations)
        assertEquals(3, repaired.size)
        assertEquals("generated", repaired.single { "A.main" in it.moduleNames }.getGeneratedSourcesDirectoryName(false))
        assertEquals("generated", repaired.single { "B.main" in it.moduleNames }.getGeneratedSourcesDirectoryName(false))
        assertEquals("custom/generated", repaired.single { "C.main" in it.moduleNames }.getGeneratedSourcesDirectoryName(false))
        assertEquals(setOf("A.main"), existing.moduleNames)
    }

    @Test fun `removed processor and detached module are not restored from old profiles`() {
        val old = ProcessorConfigProfileImpl("Gradle Imported").apply {
            addModuleName("A.main")
            addModuleName("B.main")
        }
        val repaired = AnnotationProcessingProfiles.reconcile(listOf(old), mapOf("B.main" to common))
        assertEquals(setOf("B.main"), repaired.single().moduleNames)
        assertTrue(AnnotationProcessingProfiles.reconcile(repaired, emptyMap()).isEmpty())
    }

    @Test fun `different paths and options remain separate`() {
        val declarations = mapOf(
            "A.main" to common,
            "B.main" to common.copy(path = "/other.jar"),
            "C.main" to common.copy(options = mapOf("mode" to "other")),
        )
        val repaired = AnnotationProcessingProfiles.reconcile(emptyList(), declarations)
        assertEquals(3, repaired.size)
        assertTrue(repaired.all { it.isEnabled && !it.isObtainProcessorsFromClasspath })
        assertEquals(mapOf("mode" to "other"), repaired.single { "C.main" in it.moduleNames }.processorOptions)
    }

    @Test fun `manual profiles retain their module assignments`() {
        val manual = ProcessorConfigProfileImpl("My processors").apply { addModuleName("A.main") }
        val repaired = AnnotationProcessingProfiles.reconcile(listOf(manual), mapOf("A.main" to common, "B.main" to common))
        assertSame(manual, repaired.first())
        assertEquals(setOf("A.main"), manual.moduleNames)
        assertEquals(setOf("B.main"), repaired.last().moduleNames)
    }
}
