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
}
