package com.vsvictor.jsonrpckmp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionLifecycleApiTest {
    @Test
    fun boundedPingAndRestoreDelegateTimeoutsToNative() = runTest {
        val native = LifecycleClient()
        val client = JsonRpcClient(native)

        client.transportPing(750uL)
        client.restoreConnection(1500uL)
        client.transportPing()

        assertEquals(listOf("ping 750", "restore 1500", "ping"), native.calls)
    }

    @Test
    fun zeroTimeoutsAreRejectedBeforeReachingNative() = runTest {
        val native = LifecycleClient()
        val client = JsonRpcClient(native)

        assertFailsWith<IllegalArgumentException> { client.transportPing(0uL) }
        assertFailsWith<IllegalArgumentException> { client.restoreConnection(0uL) }
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun nativeFailuresPropagateUnchanged() = runTest {
        val failure = IllegalStateException("transport error: restore deadline exceeded after 10 ms")
        val client = JsonRpcClient(LifecycleClient(failure = failure))

        assertEquals(failure.message, assertFailsWith<IllegalStateException> { client.restoreConnection(10uL) }.message)
        assertEquals(failure.message, assertFailsWith<IllegalStateException> { client.transportPing(10uL) }.message)
    }

    @Test
    fun boundedOperationsFailAfterDisconnect() = runTest {
        val native = LifecycleClient()
        val client = JsonRpcClient(native)
        client.disconnect()

        assertFailsWith<IllegalStateException> { client.transportPing(100uL) }
        assertFailsWith<IllegalStateException> { client.restoreConnection(100uL) }
        assertEquals(listOf("disconnect"), native.calls)
    }

    @Test
    fun connectTimeoutIsValidatedAndEncodedOnlyWhenSet() {
        assertFailsWith<IllegalArgumentException> {
            JsonRpcClientBuilder.builder(RpcTransport.TCP, "localhost", 1).connectTimeout(0uL)
        }
        val base = NativeConnectionOptions(
            transport = RpcTransport.TCP,
            host = "localhost",
            port = 9000u,
            caCertificatePem = null,
            serverName = null,
            srtPassphrase = null,
            udpLiteSendCoverage = null,
            udpLiteReceiveCoverage = null,
            broker = BrokerOptions(),
        )
        assertFalse("connectTimeoutMs" in base.toJson())
        val bounded = base.copy(connectTimeoutMillis = ULong.MAX_VALUE).toJson()
        assertTrue(bounded.endsWith(""","connectTimeoutMs":18446744073709551615}"""), bounded)
    }

    private class LifecycleClient(private val failure: Throwable? = null) : PlatformRpcClient {
        val calls = mutableListOf<String>()

        override fun call(method: String, paramsJson: String?) = "{}"
        override fun notify(method: String, paramsJson: String?) = Unit
        override fun transportPing() {
            calls += "ping"
        }

        override fun transportPing(timeoutMillis: ULong) {
            failure?.let { throw it }
            calls += "ping $timeoutMillis"
        }

        override fun restoreConnection(timeoutMillis: ULong) {
            failure?.let { throw it }
            calls += "restore $timeoutMillis"
        }

        override fun setPingFrequency(frequencyMillis: ULong) = Unit
        override fun setMaxMissedPongs(count: UInt) = Unit
        override fun setRequestTimeout(timeoutMillis: ULong) = Unit
        override fun takeLivenessLog() = ""
        override fun disconnect() {
            calls += "disconnect"
        }

        override fun release() = Unit
    }
}
