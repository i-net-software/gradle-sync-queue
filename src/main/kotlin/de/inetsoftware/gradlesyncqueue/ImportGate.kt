package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Gates resolver work, including refreshes initiated by IDEA itself. */
@Service(Service.Level.PROJECT)
class ImportGate(project: Project) : FifoGate(
    isDisposed = { project.isDisposed },
    dispatch = { r -> ApplicationManager.getApplication().invokeLater(r, project.disposed) },
), Disposable {
    override fun dispose() = wakeAll()

    companion object { fun get(project: Project): ImportGate = project.service() }
}
