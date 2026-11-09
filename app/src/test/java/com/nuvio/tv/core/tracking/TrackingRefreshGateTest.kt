package com.nuvio.tv.core.tracking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingRefreshGateTest {
    @Test
    fun `user requested refresh runs after a concurrent automatic refresh`() = runBlocking {
        val gate = TrackingRefreshGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val automatic = async {
            gate.runIfNeeded(profileGeneration = 1L, shouldRun = { true }) {
                started.complete(Unit)
                release.await()
                runs++
            }
        }
        started.await()
        val requested = async(start = CoroutineStart.UNDISPATCHED) {
            gate.runIfNeeded(
                profileGeneration = 1L,
                skipIfCompletedWhileWaiting = false,
                shouldRun = { true }
            ) { runs++ }
        }
        release.complete(Unit)
        automatic.await()
        requested.await()

        assertEquals(2, runs)
    }
}
