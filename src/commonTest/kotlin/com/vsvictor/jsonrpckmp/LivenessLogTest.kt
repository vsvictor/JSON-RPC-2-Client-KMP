package com.vsvictor.jsonrpckmp

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LivenessLogTest {
    @Test
    fun readingEventsDoesNotSendPingsOrChangeFrequency() = runBlocking {
        val native = FakeClient()
        val client = JsonRpcClient(native)

        assertEquals(listOf("PING: sent", "PONG: received"), client.takeLivenessLog())
        assertEquals(emptyList(), client.takeLivenessLog())
        assertEquals(0, native.pings)
        assertEquals(0, native.frequencyChanges)

        client.setPingFrequency(1000uL)
        assertEquals(1, native.frequencyChanges)
        client.disconnect()
    }

    @Test
    fun disconnectedClientCannotDrainReleasedNativeHandle() = runBlocking {
        val native = FakeClient()
        val client = JsonRpcClient(native)
        client.disconnect()
        client.disconnect()

        assertFailsWith<IllegalStateException> { client.takeLivenessLog() }
        assertEquals(1, native.releases)
        assertEquals(0, native.logReads)
    }

    private class FakeClient : PlatformRpcClient {
        var pings = 0
        var frequencyChanges = 0
        var releases = 0
        var logReads = 0
        private var entries = "PING: sent\nPONG: received\n"

        override fun call(method: String, paramsJson: String?) = "{}"
        override fun notify(method: String, paramsJson: String?) = Unit
        override fun transportPing() {
            pings++
        }

        override fun setPingFrequency(frequencyMillis: ULong) {
            frequencyChanges++
        }

        override fun setMaxMissedPongs(count: UInt) = Unit
        override fun setRequestTimeout(timeoutMillis: ULong) = Unit
        override fun disconnect() = Unit
        override fun release() {
            releases++
        }

        override fun takeLivenessLog(): String {
            logReads++
            return entries.also { entries = "" }
        }
    }
}
