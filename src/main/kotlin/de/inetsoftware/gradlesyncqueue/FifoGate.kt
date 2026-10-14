package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.ArrayDeque

/**
 * FIFO mutex with observable entries: one owner at a time, reentrant per id.
 * Free of platform services so it can be unit-tested; [ImportGate] binds it to a project.
 */
open class FifoGate(
    private val isDisposed: () -> Boolean = { false },
    private val dispatch: (Runnable) -> Unit = Runnable::run,
    private val maxFinished: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class State(val label: String, val terminal: Boolean) {
        QUEUED("Queued", false),
        RUNNING("Running", false),
        RESOLVED("Resolved, importing", false),
        DONE("Done", true),
        FAILED("Failed", true),
        CANCELLED("Cancelled", true),
    }

    data class Item(val id: String, val path: String, val state: State, val message: String?, val since: Long)

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // needed for wait/notifyAll
    private val lock = Object()
    private val waiting = ArrayDeque<String>()
    private val items = linkedMapOf<String, Item>()
    private val cancelRequested = mutableSetOf<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private var owner: String? = null
    private var holds = 0

    fun subscribe(callback: () -> Unit): () -> Unit {
        synchronized(lock) { listeners.add(callback) }
        return { synchronized(lock) { listeners.remove(callback) } }
    }

    fun snapshot(): List<Item> = synchronized(lock) { items.values.toList() }

    fun owner(): String? = synchronized(lock) { owner }

    /** Blocks until [id] is at the head of the queue and the gate is free. Throws [ProcessCanceledException] on cancel. */
    fun acquire(id: String, path: String, checkCancelled: () -> Unit = {}) {
        synchronized(lock) {
            if (owner == id) { holds++; return }
            if (id !in waiting) {
                waiting.addLast(id)
                items.remove(id)
                items[id] = Item(id, path, State.QUEUED, null, clock())
                changed()
            }
            try {
                while (owner != null || waiting.peekFirst() != id) {
                    if (isDisposed() || id !in waiting || cancelRequested.remove(id)) throw ProcessCanceledException()
                    lock.wait(WAIT_SLICE_MS)
                    checkCancelled()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                abandon(id)
                throw ProcessCanceledException(e)
            } catch (e: Throwable) {
                abandon(id)
                throw e
            }
            waiting.removeFirst()
            owner = id
            holds = 1
            update(id) { it.copy(state = State.RUNNING, since = clock()) }
        }
    }

    /** Model resolution finished; frees the gate while the IDE keeps importing. */
    fun resolved(id: String) {
        synchronized(lock) {
            if (owner != id || --holds > 0) return
            owner = null
            holds = 0
            update(id) { if (it.state.terminal) it else it.copy(state = State.RESOLVED) }
            lock.notifyAll()
        }
    }

    /** Task ended. The first terminal state wins, so a later onEnd does not hide a failure. */
    fun complete(id: String, state: State, message: String? = null) {
        require(state.terminal) { "not a terminal state: $state" }
        synchronized(lock) {
            if (owner == id) { owner = null; holds = 0 }
            waiting.remove(id)
            cancelRequested.remove(id)
            update(id) { if (it.state.terminal) it else it.copy(state = state, message = message, since = clock()) }
            lock.notifyAll()
        }
    }

    /** Cancels a waiting entry, or force-releases the gate if [id] holds it (the sync itself keeps running). */
    fun cancel(id: String) {
        synchronized(lock) {
            when {
                id in waiting -> cancelRequested += id
                owner == id -> {
                    owner = null
                    holds = 0
                    update(id) { it.copy(state = State.CANCELLED, message = "gate released manually", since = clock()) }
                }
                else -> return
            }
            lock.notifyAll()
        }
    }

    fun clearFinished() {
        synchronized(lock) {
            if (items.values.removeIf { it.state.terminal }) changed()
        }
    }

    /** Wakes waiters so they re-check disposal and cancellation. */
    fun wakeAll() {
        synchronized(lock) { lock.notifyAll() }
    }

    private fun abandon(id: String) {
        waiting.remove(id)
        cancelRequested.remove(id)
        update(id) { if (it.state.terminal) it else it.copy(state = State.CANCELLED, since = clock()) }
        lock.notifyAll()
    }

    private fun update(id: String, f: (Item) -> Item) {
        val item = items[id] ?: return
        items[id] = f(item)
        prune()
        changed()
    }

    private fun prune() {
        val finished = items.values.filter { it.state.terminal }
        finished.take((finished.size - maxFinished).coerceAtLeast(0)).forEach { items.remove(it.id) }
    }

    private fun changed() {
        val callbacks = listeners.toList()
        dispatch(Runnable { callbacks.forEach { it() } })
    }

    companion object {
        const val WAIT_SLICE_MS = 250L
    }
}
