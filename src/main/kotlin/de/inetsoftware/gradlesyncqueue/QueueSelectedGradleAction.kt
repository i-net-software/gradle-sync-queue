package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Maps selected files/directories to linked Gradle build roots, see [GradleRoots.match]. */
class QueueSelectedGradleAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible = project != null && !files.isNullOrEmpty() &&
            resolve(project, files.asList()).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.asList() ?: return
        GradleQueueService.get(project).enqueue(resolve(project, selected))
    }

    private fun resolve(project: Project, files: List<VirtualFile>): List<String> =
        GradleRoots.match(files.mapNotNull { it.fileSystem.getNioPath(it) }, GradleQueueService.get(project).linkedPaths())
}
