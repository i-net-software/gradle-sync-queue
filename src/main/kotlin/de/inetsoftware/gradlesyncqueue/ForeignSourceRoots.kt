package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtilCore

/**
 * Source folders that a Gradle build borrows from another project (`srcDirs '../Other/src'`) are compiled by IDEA in
 * every module that lists them, and the source set's `exclude`/`include` patterns are ignored (IDEA-169124). Eclipse
 * (Buildship) handles this by removing such folders and depending on the other project instead.
 *
 * This does the same for IDEA modules: a source folder outside the module's own Gradle project, which the module of
 * its home project also has, is removed and replaced by a dependency on that module.
 */
class ForeignSourceRoots(
    private val project: Project,
    /** Folder of the Gradle (sub)project a module was imported from, `null` for modules not imported from Gradle. */
    private val projectDirOf: (Module) -> String?,
) {

    /** [exported]: dependencies Eclipse re-exports (`exported="true"` in `.classpath`) */
    data class Change(val module: Module, val folderUrls: Set<String>, val dependencies: Set<Module>, val exported: Set<Module> = emptySet())

    /** What [apply] would change. Needs a read action. */
    fun plan(): List<Change> {
        val modules = ModuleManager.getInstance(project).modules
        val dirs = modules.associateWith { projectDirOf(it)?.let(::normalize) }
        // Source folder url → modules having it
        val holders = HashMap<String, MutableList<Module>>()
        for (module in modules) {
            for (entry in ModuleRootManager.getInstance(module).contentEntries) {
                for (folder in entry.sourceFolders) holders.getOrPut(folder.url) { ArrayList() } += module
            }
        }
        val eclipse = EclipseProjects(modules.mapNotNull { dirs[it] }.toSet())
        val changes = ArrayList<Change>()
        for (module in modules) {
            val ownDir = dirs[module] ?: continue
            val urls = LinkedHashSet<String>()
            val deps = LinkedHashSet<Module>()
            val exported = LinkedHashSet<Module>()
            for (entry in ModuleRootManager.getInstance(module).contentEntries) {
                for (folder in entry.sourceFolders) {
                    val path = normalize(VfsUtilCore.urlToPath(folder.url))
                    if (isUnder(path, ownDir)) continue
                    // The home module: imported from the Gradle project that contains the folder
                    val home = holders[folder.url].orEmpty()
                        .filter { it != module && dirs[it]?.let { dir -> isUnder(path, dir) } == true }
                        .maxByOrNull { dirs[it]!!.length } ?: continue
                    urls += folder.url
                    deps += home
                }
            }
            // Project dependencies of the Eclipse .classpath: the borrowed folders compile in their home module only
            // if that module also gets the other Eclipse projects it needs (e.g. CCWriter → CCBase, CCConstants)
            if (ModuleRootManager.getInstance(module).sourceRoots.isNotEmpty()) {
                val existing = ModuleRootManager.getInstance(module).dependencies.toSet()
                for (ref in eclipse.projectReferences(ownDir)) {
                    val targetDir = eclipse.dirOf(ref.name) ?: continue
                    if (targetDir == ownDir) continue
                    val target = modules.filter { dirs[it] == targetDir && it.name.endsWith(".main") }.singleOrNull() ?: continue
                    if (target in existing) continue
                    deps += target
                    if (ref.exported) exported += target
                }
            }
            if (urls.isNotEmpty() || deps.isNotEmpty()) changes += Change(module, urls, deps, exported)
        }
        return changes
    }

    /** Applies [changes]; must run on the EDT. */
    fun apply(changes: List<Change>) {
        for (change in changes) {
            if (change.module.isDisposed) continue
            ModuleRootModificationUtil.updateModel(change.module) { model ->
                for (entry in model.contentEntries) {
                    entry.sourceFolders.filter { it.url in change.folderUrls }.forEach(entry::removeSourceFolder)
                    // A content root that only held borrowed folders (e.g. "InetShared" of InetServerCore.main) goes too
                    val ownDir = projectDirOf(change.module)?.let(::normalize)
                    val rootPath = normalize(VfsUtilCore.urlToPath(entry.url))
                    if (entry.sourceFolders.isEmpty() && ownDir != null && !isUnder(rootPath, ownDir)) model.removeContentEntry(entry)
                }
                val existing = model.moduleDependencies.toSet()
                for (dependency in change.dependencies) {
                    if (dependency !in existing && dependency != change.module) {
                        model.addModuleOrderEntry(dependency).isExported = dependency in change.exported
                    }
                }
            }
        }
    }

    companion object {
        private fun normalize(path: String) = path.replace('\\', '/').trimEnd('/')

        private fun isUnder(path: String, dir: String) = path == dir || path.startsWith("$dir/")
    }
}

/** Eclipse project names and `.classpath` project references of the Gradle project folders. */
internal class EclipseProjects(projectDirs: Set<String>) {
    data class Reference(val name: String, val exported: Boolean)

    private val dirsByName: Map<String, String> = projectDirs.mapNotNull { dir -> readName(dir)?.let { it to dir } }.toMap()

    fun dirOf(name: String): String? = dirsByName[name]

    fun projectReferences(dir: String): List<Reference> {
        val file = java.io.File(dir, ".classpath")
        if (!file.isFile) return emptyList()
        return try {
            ENTRY.findAll(file.readText()).mapNotNull { m ->
                val attrs = ATTR.findAll(m.value).associate { it.groupValues[1] to it.groupValues[2] }
                val path = attrs["path"] ?: return@mapNotNull null
                if (attrs["kind"] != "src" || !path.startsWith("/")) null else Reference(path.substring(1), attrs["exported"] == "true")
            }.toList()
        } catch (_: java.io.IOException) {
            emptyList()
        }
    }

    private companion object {
        val ENTRY = Regex("""<classpathentry\s[^>]*>""")
        val ATTR = Regex("""(\w+)="([^"]*)"""")
        val NAME = Regex("""<projectDescription>\s*<name>([^<]*)</name>""")

        fun readName(dir: String): String? {
            val file = java.io.File(dir, ".project")
            if (!file.isFile) return null
            return try {
                NAME.find(file.readText())?.groupValues?.get(1)?.trim()?.ifEmpty { null }
            } catch (_: java.io.IOException) {
                null
            }
        }
    }
}
