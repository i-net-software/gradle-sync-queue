package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil

class ForeignSourceRootsTest : HeavyPlatformTestCase() {

    fun testBorrowedFolderBecomesDependency() {
        // InetServerCore compiles ../InetShared/src, which InetShared compiles too
        lateinit var core: VirtualFile
        lateinit var shared: VirtualFile
        lateinit var coreSrc: VirtualFile
        lateinit var sharedSrc: VirtualFile
        WriteAction.run<Exception> {
            val ws = tempDir.createVirtualDir()
            core = ws.createChildDirectory(this, "InetServerCore")
            shared = ws.createChildDirectory(this, "InetShared")
            coreSrc = core.createChildDirectory(this, "src")
            sharedSrc = shared.createChildDirectory(this, "src")
        }
        val coreModule = module
        val sharedModule = createModule("InetShared.main")
        PsiTestUtil.addContentRoot(coreModule, coreSrc)
        PsiTestUtil.addSourceRoot(coreModule, coreSrc)
        PsiTestUtil.addContentRoot(coreModule, shared)
        PsiTestUtil.addSourceRoot(coreModule, sharedSrc)
        PsiTestUtil.addContentRoot(sharedModule, sharedSrc)
        PsiTestUtil.addSourceRoot(sharedModule, sharedSrc)

        val dirs = mapOf<Module, String>(coreModule to core.path, sharedModule to shared.path)
        val roots = ForeignSourceRoots(project) { dirs[it] }
        val changes = ReadAction.compute<List<ForeignSourceRoots.Change>, RuntimeException> { roots.plan() }
        assertEquals(1, changes.size)
        roots.apply(changes)

        val coreRoots = ModuleRootManager.getInstance(coreModule)
        assertEquals(listOf(coreSrc), coreRoots.sourceRoots.toList())
        assertEquals(listOf(coreSrc), coreRoots.contentRoots.toList())
        assertEquals(listOf(sharedModule), coreRoots.dependencies.toList())
        assertEquals(listOf(sharedSrc), ModuleRootManager.getInstance(sharedModule).sourceRoots.toList())

        // Idempotent
        assertEmpty(ReadAction.compute<List<ForeignSourceRoots.Change>, RuntimeException> { roots.plan() })
    }

    fun testFolderWithoutHomeModuleStays() {
        lateinit var other: VirtualFile
        WriteAction.run<Exception> { other = tempDir.createVirtualDir().createChildDirectory(this, "src") }
        PsiTestUtil.addContentRoot(module, other)
        PsiTestUtil.addSourceRoot(module, other)
        val roots = ForeignSourceRoots(project) { if (it == module) "/somewhere/else" else null }
        assertEmpty(ReadAction.compute<List<ForeignSourceRoots.Change>, RuntimeException> { roots.plan() })
    }

    fun testEclipseProjectReferencesBecomeDependencies() {
        // CCWriter's .classpath references /CCBase, which its Gradle build does not know
        lateinit var writer: VirtualFile
        lateinit var base: VirtualFile
        WriteAction.run<Exception> {
            val ws = tempDir.createVirtualDir()
            writer = ws.createChildDirectory(this, "CCWriter")
            base = ws.createChildDirectory(this, "CCBase")
            writer.createChildData(this, ".classpath").setBinaryContent(
                "<classpath><classpathentry kind=\"src\" path=\"src\"/><classpathentry combineaccessrules=\"false\" exported=\"true\" kind=\"src\" path=\"/CCBase\"/></classpath>".toByteArray()
            )
            base.createChildData(this, ".project").setBinaryContent("<projectDescription><name>CCBase</name></projectDescription>".toByteArray())
            writer.createChildDirectory(this, "src")
            base.createChildDirectory(this, "src")
        }
        val writerModule = createModule("CCWriter.main")
        val baseModule = createModule("CCBase.main")
        PsiTestUtil.addSourceContentToRoots(writerModule, writer.findChild("src")!!)
        PsiTestUtil.addSourceContentToRoots(baseModule, base.findChild("src")!!)

        val dirs = mapOf<Module, String>(writerModule to writer.path, baseModule to base.path)
        val roots = ForeignSourceRoots(project) { dirs[it] }
        roots.apply(ReadAction.compute<List<ForeignSourceRoots.Change>, RuntimeException> { roots.plan() })

        assertEquals(listOf(baseModule), ModuleRootManager.getInstance(writerModule).dependencies.toList())
        val entry = ModuleRootManager.getInstance(writerModule).orderEntries.filterIsInstance<com.intellij.openapi.roots.ModuleOrderEntry>().single()
        assertTrue(entry.isExported)
        assertEmpty(ReadAction.compute<List<ForeignSourceRoots.Change>, RuntimeException> { roots.plan() })
    }
}
