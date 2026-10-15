package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.util.GradleConstants

/** After every Gradle import, replaces borrowed source folders by module dependencies (see [ForeignSourceRoots]). */
class ForeignSourceRootsImportListener(private val project: Project) : ProjectDataImportListener {

    override fun onImportFinished(projectPath: String?) {
        if (!AdvancedSettings.getBoolean(SETTING)) return
        ApplicationManager.getApplication().invokeLater({
            val roots = ForeignSourceRoots(project) { module ->
                if (ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, module)) ExternalSystemApiUtil.getExternalProjectPath(module) else null
            }
            val changes = roots.plan() // the EDT may read the project model
            if (changes.isEmpty()) return@invokeLater
            for (c in changes) {
                LOG.info("${c.module.name}: borrowed ${c.folderUrls.joinToString()} -> depends on ${c.dependencies.joinToString { it.name }}")
            }
            roots.apply(changes)
        }, project.disposed)
    }

    companion object {
        const val SETTING = "gradle.sync.queue.replace.borrowed.sources"
        private val LOG = logger<ForeignSourceRootsImportListener>()
    }
}
