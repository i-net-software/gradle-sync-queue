package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Launches requested refreshes one after another; each refresh additionally passes the [ImportGate]. */
@Service(Service.Level.PROJECT)
class GradleQueueService(private val project: Project) : Disposable {
    data class Entry(val path: String, val state: String)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "Gradle-Queue-Worker").apply { isDaemon = true } }
    private val entries = linkedMapOf<String, String>()
    private val listeners = mutableListOf<() -> Unit>()
    @Volatile private var current: CountDownLatch? = null

    fun linkedPaths(): List<String> = ExternalSystemApiUtil.getSettings(project, GradleConstants.SYSTEM_ID)
        .linkedProjectsSettings.map { it.externalProjectPath }

    @Synchronized fun snapshot(): List<Entry> = entries.map { Entry(it.key, it.value) }

    @Synchronized fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { synchronized(this) { listeners.remove(listener) } }
    }

    @Synchronized private fun notifyListeners() {
        val callbacks = listeners.toList()
        ApplicationManager.getApplication().invokeLater({ callbacks.forEach { it() } }, project.disposed)
    }

    @Synchronized fun enqueue(paths: Collection<String>) {
        if (executor.isShutdown) return
        for (path in paths.distinct()) {
            if (entries[path] == QUEUED || entries[path] == RUNNING) continue
            entries.remove(path)
            entries[path] = QUEUED
            executor.execute { refresh(path) }
        }
        notifyListeners()
    }

    @Synchronized fun clearFinished() {
        if (entries.values.removeIf { it != QUEUED && it != RUNNING }) notifyListeners()
    }

    private fun refresh(path: String) {
        if (project.isDisposed) return
        setState(path, RUNNING)
        val completion = CountDownLatch(1).also { current = it }
        var result = "Done"
        try {
            val spec = ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                .withCallback(object : ExternalProjectRefreshCallback {
                    override fun onSuccess(externalProject: DataNode<ProjectData>?) { completion.countDown() }
                    override fun onFailure(errorMessage: String, errorDetails: String?) {
                        result = "Failed: $errorMessage"
                        completion.countDown()
                    }
                })
            ExternalSystemUtil.refreshProject(path, spec)
            if (!completion.await(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                result = "Timed out after $TIMEOUT_MINUTES min"
                LOG.warn("Gradle refresh of $path did not report back within $TIMEOUT_MINUTES min; continuing queue")
            }
            if (project.isDisposed) result = "Cancelled"
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            result = "Cancelled"
        } catch (e: Exception) {
            LOG.warn("Gradle refresh of $path failed", e)
            result = "Failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            current = null
        }
        setState(path, result)
    }

    @Synchronized private fun setState(path: String, state: String) {
        entries[path] = state
        val finished = entries.filterValues { it != QUEUED && it != RUNNING }.keys
        finished.take((finished.size - MAX_FINISHED).coerceAtLeast(0)).forEach { entries.remove(it) }
        notifyListeners()
    }

    override fun dispose() {
        executor.shutdownNow()
        current?.countDown()
    }

    companion object {
        private const val QUEUED = "Queued"
        private const val RUNNING = "Running"
        private const val TIMEOUT_MINUTES = 60L
        private const val MAX_FINISHED = 50
        private val LOG = logger<GradleQueueService>()

        fun get(project: Project): GradleQueueService = project.service()
    }
}
