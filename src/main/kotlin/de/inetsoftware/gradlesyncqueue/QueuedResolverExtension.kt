package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension

/** Called for IDEA-initiated Gradle syncs as well as our explicit refreshes.
 * NOTE: preImportCheck is invoked after GradleProjectResolver has opened a ProjectConnection,
 * but before the model fetch action runs. Whether that prevents the daemon spike must be measured.
 */
class QueuedResolverExtension : AbstractProjectResolverExtension() {
    override fun preImportCheck() {
        val ctx = resolverCtx
        val task = ctx.externalSystemTaskId
        val project = task.findProject() ?: return
        LOG.info("Gradle sync $task for ${ctx.projectPath} waiting for gate")
        ImportGate.get(project).acquire(task.toString(), ctx.projectPath) {
            if (ctx.cancellationToken.isCancellationRequested) throw ProcessCanceledException()
        }
        LOG.info("Gradle sync $task acquired gate")
    }

    override fun resolveFinished(projectDataNode: DataNode<ProjectData>) {
        val task = resolverCtx.externalSystemTaskId
        task.findProject()?.let { ImportGate.get(it).resolved(task.toString()) }
    }

    private companion object { val LOG = logger<QueuedResolverExtension>() }
}
