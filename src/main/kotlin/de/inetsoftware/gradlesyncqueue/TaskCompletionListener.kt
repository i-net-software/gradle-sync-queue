package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import de.inetsoftware.gradlesyncqueue.FifoGate.State

/** Releases the gate on failures/cancellation too, where resolveFinished is not called. */
class TaskCompletionListener : ExternalSystemTaskNotificationListener {
    override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) = complete(id, State.DONE)
    override fun onFailure(projectPath: String, id: ExternalSystemTaskId, e: Exception) =
        complete(id, State.FAILED, e.message ?: e.javaClass.simpleName)
    override fun onCancel(projectPath: String, id: ExternalSystemTaskId) = complete(id, State.CANCELLED)
    // Fires after success/failure/cancel; complete() keeps the first terminal state.
    override fun onEnd(projectPath: String, id: ExternalSystemTaskId) = complete(id, State.DONE)

    private fun complete(id: ExternalSystemTaskId, state: State, message: String? = null) {
        val project = id.findProject() ?: return
        if (project.isDisposed) return
        ImportGate.get(project).complete(id.toString(), state, message)
    }
}
