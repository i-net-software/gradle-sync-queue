package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleDependency
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity

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
    /**
     * Source folders the Gradle model declares for a module. IDEA may have dropped some of them from the module
     * (a folder can belong to one module only), so the IDE model alone does not show every borrowed folder.
     */
    private val declaredSourcesOf: (Module) -> Collection<String> = { emptyList() },
    /** Folder of the Gradle (sub)project a module was imported from, `null` for modules not imported from Gradle. */
    private val projectDirOf: (Module) -> String?,
) {

    /** [exported]: dependencies Eclipse re-exports (`exported="true"` in `.classpath`) */
    data class Change(val module: Module, val folderUrls: Set<String>, val dependencies: Set<Module>, val exported: Set<Module> = emptySet()) {
        /** Dependencies on modules with test sources only (e.g. CCBase.mock): their test output is what is needed */
        var testOnly: Set<Module> = emptySet()
    }

    /** What [apply] would change. Needs a read action. */
    fun plan(): List<Change> {
        val modules = ModuleManager.getInstance(project).modules
        val dirs = modules.associateWith { projectDirOf(it)?.let(::normalize) }
        // Source folder path → modules having it in IDEA, or declaring it in Gradle
        val ideFolders = modules.associateWith { module ->
            ModuleRootManager.getInstance(module).contentEntries.flatMap { it.sourceFolders.toList() }
                .associateBy { normalize(VfsUtilCore.urlToPath(it.url)) }
        }
        val declared = modules.associateWith { module -> declaredSourcesOf(module).map(::normalize).toSet() }
        val holders = HashMap<String, MutableSet<Module>>()
        for (module in modules) {
            (ideFolders[module]!!.keys + declared[module]!!).forEach { holders.getOrPut(it) { LinkedHashSet() } += module }
        }
        val eclipse = EclipseProjects(modules.mapNotNull { dirs[it] }.toSet())
        // Borrowed folder → home module, per module: imported from the Gradle project that contains the folder
        val borrowed = modules.associateWith { module ->
            val ownDir = dirs[module] ?: return@associateWith emptyMap<String, Module>()
            (ideFolders[module]!!.keys + declared[module]!!).filter { !isUnder(it, ownDir) }.mapNotNull { path ->
                holders[path].orEmpty()
                    .filter { it != module && dirs[it]?.let { dir -> isUnder(path, dir) } == true }
                    .maxWithOrNull(compareBy<Module>({ dirs[it]!!.length }, { if (ideFolders[it]!!.containsKey(path)) 1 else 0 }))
                    ?.let { path to it }
            }.toMap()
        }
        val changes = ArrayList<Change>()
        for (module in modules) {
            val ownDir = dirs[module] ?: continue
            val existing = ModuleRootManager.getInstance(module).dependencies.toSet()
            val existingExported = ModuleRootManager.getInstance(module).orderEntries
                .filterIsInstance<ModuleOrderEntry>().filter { it.isExported }.mapNotNull { it.module }.toSet()
            val urls = LinkedHashSet<String>()
            val deps = LinkedHashSet<Module>()
            val exported = LinkedHashSet<Module>()
            for ((path, home) in borrowed[module]!!) {
                ideFolders[module]!![path]?.let { urls += it.url }
                // Exported: modules depending on this one saw the borrowed classes before (e.g. CC.mock → CC.main)
                if (home !in existingExported) {
                    deps += home
                    exported += home
                }
            }
            // Source sets of the same project that this one depends on (CC.mock → CC.main) compiled the borrowed classes
            // before; give their homes directly, as Eclipse has one class path per project
            for (sibling in existing) {
                if (sibling == module || dirs[sibling] != ownDir) continue
                for (home in borrowed[sibling]!!.values) {
                    if (home != module && home !in existing) deps += home
                }
            }
            // Project dependencies of the Eclipse .classpath: the borrowed folders compile in their home module only
            // if that module also gets the other Eclipse projects it needs (e.g. CCWriter → CCBase, CCConstants)
            if (ideFolders[module]!!.isNotEmpty()) {
                for (ref in eclipse.projectReferences(ownDir)) {
                    val targetDir = eclipse.dirOf(ref.name) ?: continue
                    if (targetDir == ownDir) continue
                    val target = modules.filter { dirs[it] == targetDir && it.name.endsWith(".main") }.singleOrNull() ?: continue
                    if (target in existing) continue
                    deps += target
                    if (ref.exported) exported += target
                }
            }
            // A dependency on a module with test sources only needs "production on test" to see its classes
            val entries = ModuleRootManager.getInstance(module).orderEntries.filterIsInstance<ModuleOrderEntry>()
            for (entry in entries) {
                val target = entry.module ?: continue
                if (isTestOnly(target) && !entry.isProductionOnTestDependency) deps += target
            }
            if (urls.isNotEmpty() || deps.isNotEmpty()) {
                changes += Change(module, urls, deps, exported).also { c -> c.testOnly = deps.filter(::isTestOnly).toSet() }
            }
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
                for (dependency in change.dependencies) {
                    if (dependency == change.module) continue
                    val entry = model.orderEntries.filterIsInstance<ModuleOrderEntry>().firstOrNull { it.module == dependency }
                        ?: model.addModuleOrderEntry(dependency)
                    if (dependency in change.exported) entry.isExported = true
                }
            }
        }
        WriteAction.run<RuntimeException> { markProductionOnTest(changes) }
    }

    /** "Production on test" via the workspace model, the order entry setter is internal API. Needs a write action. */
    private fun markProductionOnTest(changes: List<Change>) {
        val targets = changes.filter { it.testOnly.isNotEmpty() && !it.module.isDisposed }
            .associate { it.module.name to it.testOnly.map(Module::getName).toSet() }
        if (targets.isEmpty()) return
        WorkspaceModel.getInstance(project).updateProjectModel("Gradle Sync Queue: production on test") { storage ->
            for ((moduleName, names) in targets) {
                val entity = storage.resolve(ModuleId(moduleName)) ?: continue
                storage.modifyModuleEntity(entity) {
                    dependencies = dependencies.map { dep ->
                        if (dep is ModuleDependency && dep.module.name in names && !dep.productionOnTest) dep.copy(productionOnTest = true) else dep
                    }.toMutableList()
                }
            }
        }
    }

    private fun isTestOnly(module: Module): Boolean {
        val roots = ModuleRootManager.getInstance(module)
        return roots.getSourceRoots(false).isEmpty() && roots.getSourceRoots(true).isNotEmpty()
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
