package org.bidon.bidon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RendererLoadDeadlineTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun start_timeoutFires_callsOnTimeoutOnce() = runBlocking {
        val calls = AtomicInteger(0)

        val deadline = RendererLoadDeadline(
            scope = scope,
            timeoutMs = 50L,
            onTimeout = { calls.incrementAndGet() }
        )

        deadline.start()
        delay(100L)

        assertEquals(1, calls.get())
    }

    @Test
    fun finish_beforeTimeout_cancelsAndReturnsTrue() = runBlocking {
        val calls = AtomicInteger(0)

        val deadline = RendererLoadDeadline(
            scope = scope,
            timeoutMs = 200L,
            onTimeout = { calls.incrementAndGet() }
        )

        deadline.start()
        delay(20L)

        val finished = deadline.finish()

        assertTrue(finished)
        delay(250L) // wait past original timeout
        assertEquals(0, calls.get())
    }

    @Test
    fun finish_afterTimeout_returnsFalse_andDoesNotCallOnTimeoutAgain() = runBlocking {
        val calls = AtomicInteger(0)

        val deadline = RendererLoadDeadline(
            scope = scope,
            timeoutMs = 50L,
            onTimeout = { calls.incrementAndGet() }
        )

        deadline.start()
        delay(100L) // timeout already fired

        assertEquals(1, calls.get())

        val finished = deadline.finish() // race: response arrived after deadline

        assertFalse(finished)
        assertEquals(1, calls.get()) // still exactly one
    }

    @Test
    fun cancel_preventsTimeout() = runBlocking {
        val calls = AtomicInteger(0)

        val deadline = RendererLoadDeadline(
            scope = scope,
            timeoutMs = 50L,
            onTimeout = { calls.incrementAndGet() }
        )

        deadline.start()
        deadline.cancel()

        delay(100L)
        assertEquals(0, calls.get())
    }

    @Test
    fun finish_calledTwice_secondReturnsFalse() = runBlocking {
        val deadline = RendererLoadDeadline(
            scope = scope,
            timeoutMs = 500L,
            onTimeout = { }
        )

        deadline.start()

        assertTrue(deadline.finish())
        assertFalse(deadline.finish())
    }

    @Test
    fun start_afterFinish_canBeRestarted() = runBlocking {
        val calls = AtomicInteger(0)

        val deadline = RendererLoadDeadline(
            scope = scope,
            timeoutMs = 50L,
            onTimeout = { calls.incrementAndGet() }
        )

        deadline.start()
        assertTrue(deadline.finish()) // cancel first run

        deadline.start() // restart
        delay(100L)

        assertEquals(1, calls.get())
    }

    @Test
    fun cancel_thenFinish_returnsFalse() = runBlocking {
        val deadline = RendererLoadDeadline(
            scope = scope,
            timeoutMs = 200L,
            onTimeout = { }
        )

        deadline.start()
        deadline.cancel()

        assertFalse(deadline.finish())
    }
}