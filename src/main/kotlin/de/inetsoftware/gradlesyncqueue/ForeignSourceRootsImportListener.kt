package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.GradleConstants

/** After every Gradle import, replaces borrowed source folders by module dependencies (see [ForeignSourceRoots]). */
class ForeignSourceRootsImportListener(private val project: Project) : ProjectDataImportListener {

    override fun onImportFinished(projectPath: String?) {
        if (!AdvancedSettings.getBoolean(SETTING)) return
        ApplicationManager.getApplication().invokeLater({
            val declared = declaredSourceFolders()
            val roots = ForeignSourceRoots(
                project,
                projectDirOf = { module ->
                    if (ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, module)) ExternalSystemApiUtil.getExternalProjectPath(module) else null
                },
                declaredSourcesOf = { module -> declared[module.name].orEmpty() },
            )
            val changes = roots.plan() // the EDT may read the project model
            if (changes.isEmpty()) return@invokeLater
            for (c in changes) {
                LOG.info("${c.module.name}: removed borrowed ${c.folderUrls.joinToString()}; depends on ${c.dependencies.joinToString { it.name }}")
            }
            roots.apply(changes)
        }, project.disposed)
    }

    /** Source set module name → source folders as the Gradle model declares them, from the last import of each build. */
    private fun declaredSourceFolders(): Map<String, Set<String>> {
        val result = HashMap<String, MutableSet<String>>()
        for (info in ProjectDataManager.getInstance().getExternalProjectsData(project, GradleConstants.SYSTEM_ID)) {
            val structure = info.externalProjectStructure ?: continue
            for (sourceSet in ExternalSystemApiUtil.findAllRecursively(structure, GradleSourceSetData.KEY)) {
                val folders = result.getOrPut(sourceSet.data.internalName) { LinkedHashSet() }
                for (root in ExternalSystemApiUtil.findAll(sourceSet, ProjectKeys.CONTENT_ROOT)) {
                    for (type in SOURCE_TYPES) root.data.getPaths(type).forEach { folders += it.path }
                }
            }
        }
        return result
    }

    companion object {
        private val SOURCE_TYPES = listOf(
            ExternalSystemSourceType.SOURCE, ExternalSystemSourceType.TEST,
            ExternalSystemSourceType.RESOURCE, ExternalSystemSourceType.TEST_RESOURCE,
        )
        const val SETTING = "gradle.sync.queue.replace.borrowed.sources"
        private val LOG = logger<ForeignSourceRootsImportListener>()
    }
}
