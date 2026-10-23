package com.vsvictor.jsonrpckmp

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonRpcClientTest {
    @Test
    fun delegatesEveryOperationToTheNativeClient() = runTest {
        val native = RecordingClient()
        val client = JsonRpcClient(native)

        assertEquals("result:echo:[1]", client.call("echo", "[1]"))
        client.notify("log", null)
        client.transportPing()
        client.setPingFrequency(250uL)
        client.setMaxMissedPongs(5u)
        client.setRequestTimeout(3000uL)
        client.disconnect()

        assertEquals(
            listOf(
                "call echo [1]",
                "notify log null",
                "ping",
                "frequency 250",
                "missed 5",
                "timeout 3000",
                "disconnect",
                "release",
            ),
            native.calls,
        )
    }

    @Test
    fun everyOperationFailsAfterDisconnect() = runTest {
        val client = JsonRpcClient(RecordingClient())
        client.disconnect()

        val operations: List<suspend () -> Unit> = listOf(
            { client.call("echo") },
            { client.notify("echo") },
            { client.transportPing() },
            { client.setPingFrequency(1uL) },
            { client.setMaxMissedPongs(1u) },
            { client.setRequestTimeout(1uL) },
            { client.takeLivenessLog() },
        )
        operations.forEach { operation ->
            val error = assertFailsWith<IllegalStateException> { operation() }
            assertEquals("JSON-RPC client is disconnected", error.message)
        }
    }

    @Test
    fun zeroRequestTimeoutIsRejectedBeforeNativeCall() = runTest {
        val native = RecordingClient()
        assertFailsWith<IllegalArgumentException> { JsonRpcClient(native).setRequestTimeout(0uL) }
        assertTrue(native.calls.none { it.startsWith("timeout") })
    }

    @Test
    fun nativeErrorsArePropagated() = runTest {
        val native = RecordingClient(callError = IllegalStateException("Transport: No route to host"))
        val error = assertFailsWith<IllegalStateException> { JsonRpcClient(native).call("echo") }
        assertEquals("Transport: No route to host", error.message)
    }

    @Test
    fun releaseHappensEvenWhenNativeDisconnectFails() = runTest {
        val native = RecordingClient(disconnectError = IllegalStateException("socket closed"))
        val client = JsonRpcClient(native)

        assertFailsWith<IllegalStateException> { client.disconnect() }
        client.disconnect()

        assertEquals(1, native.calls.count { it == "release" })
        assertFailsWith<IllegalStateException> { client.call("echo") }
    }

    @Test
    fun concurrentDisconnectsReleaseOnlyOnce() = runTest {
        val native = RecordingClient()
        val client = JsonRpcClient(native)

        (1..20).map { async { client.disconnect() } }.awaitAll()

        assertEquals(1, native.calls.count { it == "release" })
    }

    @Test
    fun livenessLogSkipsBlankLinesAndKeepsOrder() = runTest {
        val native = RecordingClient(log = "\nPING: 1\n\nPONG: 1\nERROR: lost\n")
        assertEquals(listOf("PING: 1", "PONG: 1", "ERROR: lost"), JsonRpcClient(native).takeLivenessLog())
    }

    private class RecordingClient(
        private val callError: Throwable? = null,
        private val disconnectError: Throwable? = null,
        private var log: String = "",
    ) : PlatformRpcClient {
        val calls = mutableListOf<String>()

        override fun call(method: String, paramsJson: String?): String {
            callError?.let { throw it }
            calls += "call $method $paramsJson"
            return "result:$method:$paramsJson"
        }

        override fun notify(method: String, paramsJson: String?) {
            calls += "notify $method $paramsJson"
        }

        override fun transportPing() {
            calls += "ping"
        }

        override fun setPingFrequency(frequencyMillis: ULong) {
            calls += "frequency $frequencyMillis"
        }

        override fun setMaxMissedPongs(count: UInt) {
            calls += "missed $count"
        }

        override fun setRequestTimeout(timeoutMillis: ULong) {
            calls += "timeout $timeoutMillis"
        }

        override fun takeLivenessLog(): String = log.also { log = "" }

        override fun disconnect() {
            calls += "disconnect"
            disconnectError?.let { throw it }
        }

        override fun release() {
            calls += "release"
        }
    }
}
