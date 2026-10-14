package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.progress.ProcessCanceledException
import de.inetsoftware.gradlesyncqueue.FifoGate.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FifoGateTest {
    private val gate = FifoGate()

    private fun state(id: String) = gate.snapshot().single { it.id == id }.state

    private fun awaitState(id: String, expected: State) {
        val deadline = System.currentTimeMillis() + 5_000
        while (gate.snapshot().firstOrNull { it.id == id }?.state != expected) {
            check(System.currentTimeMillis() < deadline) { "$id never reached $expected" }
            Thread.sleep(10)
        }
    }

    @Test fun `grants in FIFO order`() {
        gate.acquire("a", "/a")
        val order = Collections.synchronizedList(mutableListOf<String>())
        val b = thread { gate.acquire("b", "/b"); order += "b"; gate.resolved("b") }
        awaitState("b", State.QUEUED)
        val c = thread { gate.acquire("c", "/c"); order += "c"; gate.resolved("c") }
        awaitState("c", State.QUEUED)
        gate.resolved("a")
        b.join(5_000); c.join(5_000)
        assertEquals(listOf("b", "c"), order)
        assertNull(gate.owner())
    }

    @Test fun `reentrant acquire does not deadlock`() {
        gate.acquire("a", "/a")
        gate.acquire("a", "/a")
        gate.resolved("a")
        assertEquals("a", gate.owner())
        gate.resolved("a")
        assertNull(gate.owner())
        assertEquals(State.RESOLVED, state("a"))
    }

    @Test fun `failure is not overwritten by onEnd`() {
        gate.acquire("a", "/a")
        gate.complete("a", State.FAILED, "boom")
        gate.complete("a", State.DONE)
        val item = gate.snapshot().single()
        assertEquals(State.FAILED, item.state)
        assertEquals("boom", item.message)
        assertNull(gate.owner())
    }

    @Test fun `complete releases gate when resolveFinished is never called`() {
        gate.acquire("a", "/a")
        gate.complete("a", State.CANCELLED)
        gate.acquire("b", "/b")
        assertEquals("b", gate.owner())
    }

    @Test fun `cancelling a waiting entry throws in the waiter`() {
        gate.acquire("a", "/a")
        val failure = CountDownLatch(1)
        val t = thread { try { gate.acquire("b", "/b") } catch (_: ProcessCanceledException) { failure.countDown() } }
        awaitState("b", State.QUEUED)
        gate.cancel("b")
        assertTrue(failure.await(5, TimeUnit.SECONDS))
        t.join()
        assertEquals(State.CANCELLED, state("b"))
        assertEquals("a", gate.owner())
    }

    @Test fun `checkCancelled aborts waiting`() {
        gate.acquire("a", "/a")
        var cancelled = false
        val failure = CountDownLatch(1)
        thread { try { gate.acquire("b", "/b") { if (cancelled) throw ProcessCanceledException() } } catch (_: ProcessCanceledException) { failure.countDown() } }
        awaitState("b", State.QUEUED)
        cancelled = true
        assertTrue(failure.await(5, TimeUnit.SECONDS))
        assertEquals(State.CANCELLED, state("b"))
    }

    @Test fun `force release frees the gate for the next waiter`() {
        gate.acquire("a", "/a")
        val t = thread { gate.acquire("b", "/b") }
        awaitState("b", State.QUEUED)
        gate.cancel("a")
        t.join(5_000)
        assertEquals("b", gate.owner())
        assertEquals(State.CANCELLED, state("a"))
    }

    @Test fun `finished entries are pruned`() {
        val small = FifoGate(maxFinished = 2)
        repeat(5) { small.acquire("t$it", "/p"); small.complete("t$it", State.DONE) }
        assertEquals(listOf("t3", "t4"), small.snapshot().map { it.id })
        small.clearFinished()
        assertTrue(small.snapshot().isEmpty())
    }
}
