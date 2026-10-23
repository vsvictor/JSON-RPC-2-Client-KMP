package com.vsvictor.jsonrpckmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Host of a running `JSON_RPC_2 server` (see README), or null when the platform cannot reach it. */
internal expect fun integrationServerHost(): String?

/**
 * End-to-end tests against the local json-rpc-2 server. They are skipped when no server
 * host is configured or the TCP endpoint is unreachable.
 */
class ServerIntegrationTest {
    private suspend fun serverHost(): String? {
        val host = integrationServerHost() ?: return null.also { println("SKIPPED: no integration host") }
        return try {
            JsonRpcClientBuilder.builder(RpcTransport.TCP, host, TCP_PORT).connect().disconnect()
            host
        } catch (error: Throwable) {
            println("SKIPPED: json-rpc-2 server is unreachable at $host:$TCP_PORT ($error)")
            null
        }
    }

    private suspend fun <T> withTcpClient(block: suspend (JsonRpcClient) -> T): T? {
        val host = serverHost() ?: return null
        val client = JsonRpcClientBuilder.builder(RpcTransport.TCP, host, TCP_PORT).connect()
        return try {
            block(client)
        } finally {
            client.disconnect()
        }
    }

    private suspend fun realDelay(millis: Long) =
        withContext(Dispatchers.Default) { delay(millis.milliseconds) }

    @Test
    fun tcpEchoReturnsParams() = runTest(timeout = 60.seconds) {
        withTcpClient { client ->
            assertEquals("""{"message":"hi"}""", client.call("echo", """{"message":"hi"}"""))
            assertEquals("[1,2,3]", client.call("echo", "[1,2,3]"))
        }
    }

    @Test
    fun echoPreservesUnicodeAndLargePayloads() = runTest(timeout = 60.seconds) {
        withTcpClient { client ->
            val text = "Привіт 🙂 \\\" " + "x".repeat(32 * 1024)
            val params = """{"text":"${text.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
            assertEquals(params, client.call("echo", params))
        }
    }

    @Test
    fun notificationsAndTransportPingKeepTheConnectionUsable() = runTest(timeout = 60.seconds) {
        withTcpClient { client ->
            client.notify("echo", """{"n":1}""")
            client.transportPing()
            assertEquals("""{"after":true}""", client.call("echo", """{"after":true}"""))
        }
    }

    @Test
    fun unknownMethodIsReportedAsError() = runTest(timeout = 60.seconds) {
        withTcpClient { client ->
            val error = assertFailsWith<Throwable> { client.call("method.that.does.not.exist", "{}") }
            println("Unknown method error: $error")
            assertEquals("""{"ok":1}""", client.call("echo", """{"ok":1}"""))
        }
    }

    @Test
    fun concurrentCallsGetTheirOwnResponses() = runTest(timeout = 60.seconds) {
        withTcpClient { client ->
            val results = (1..25).map { index ->
                async(Dispatchers.Default) { index to client.call("echo", """{"i":$index}""") }
            }.awaitAll()
            results.forEach { (index, result) -> assertEquals("""{"i":$index}""", result) }
        }
    }

    @Test
    fun runtimeSettingsProducePingPongLiveness() = runTest(timeout = 60.seconds) {
        withTcpClient { client ->
            client.setRequestTimeout(5_000uL)
            client.setMaxMissedPongs(3u)
            client.setPingFrequency(200uL)
            client.takeLivenessLog()
            realDelay(1_500)
            val log = client.takeLivenessLog()
            assertTrue(log.any { it.startsWith("PING") }, "Expected PING entries in $log")
            assertTrue(log.any { it.startsWith("PONG") }, "Expected PONG entries in $log")
            assertTrue(log.none { it.startsWith("RECONNECT") }, "Unexpected reconnect in $log")
            assertTrue(client.takeLivenessLog().size < log.size, "Liveness log must be drained")
        }
    }

    @Test
    fun disconnectedClientRejectsCalls() = runTest(timeout = 60.seconds) {
        val host = serverHost() ?: return@runTest
        val client = JsonRpcClientBuilder.builder(RpcTransport.TCP, host, TCP_PORT).connect()
        client.disconnect()
        client.disconnect()
        assertFailsWith<IllegalStateException> { client.call("echo", "{}") }
    }

    @Test
    fun closedPortFailsToConnect() = runTest(timeout = 60.seconds) {
        val host = serverHost() ?: return@runTest
        val error = assertFailsWith<Throwable> {
            JsonRpcClientBuilder.builder(RpcTransport.TCP, host, CLOSED_PORT).connect()
        }
        println("Closed port error: $error")
    }

    @Test
    fun tlsRequiresTheServerCertificate() = runTest(timeout = 60.seconds) {
        val host = serverHost() ?: return@runTest
        assertFailsWith<Throwable> {
            JsonRpcClientBuilder.builder(RpcTransport.TLS, host, 8445).serverName("localhost").connect()
        }
        val client = JsonRpcClientBuilder.builder(RpcTransport.TLS, host, 8445)
            .caCertificatePem(TEST_SERVER_CA_PEM)
            .serverName("localhost")
            .connect()
        try {
            assertEquals("""{"secure":true}""", client.call("echo", """{"secure":true}"""))
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun everyServerTransportEchoes() = runTest(timeout = 180.seconds) {
        val host = serverHost() ?: return@runTest
        val failures = mutableListOf<String>()
        SERVER_TRANSPORTS.forEach { (transport, port) ->
            val result = runCatching {
                val builder = JsonRpcClientBuilder.builder(transport, host, port)
                if (transport in SECURE_TRANSPORTS) {
                    builder.caCertificatePem(TEST_SERVER_CA_PEM).serverName("localhost")
                }
                val client = builder.connect()
                try {
                    client.call("echo", """{"transport":"${transport.wireName}"}""")
                } finally {
                    client.disconnect()
                }
            }
            val expected = """{"transport":"${transport.wireName}"}"""
            val outcome = result.fold({ if (it == expected) "OK" else "unexpected result $it" }, { "FAILED $it" })
            println("Transport ${transport.name}:$port -> $outcome")
            if (outcome != "OK") failures += "${transport.name}: $outcome"
        }
        if (failures.isNotEmpty()) fail("Transports failed:\n" + failures.joinToString("\n"))
    }

    private companion object {
        const val TCP_PORT = 8000
        const val CLOSED_PORT = 8999

        val SECURE_TRANSPORTS = setOf(
            RpcTransport.TLS,
            RpcTransport.QUIC,
            RpcTransport.HTTP3,
            RpcTransport.WS_QUIC,
            RpcTransport.WS_HTTP3,
        )

        val SERVER_TRANSPORTS = listOf(
            RpcTransport.TCP to 8000,
            RpcTransport.TLS to 8445,
            RpcTransport.UDP to 8100,
            RpcTransport.RTP to 8106,
            RpcTransport.RUDP to 8107,
            RpcTransport.COAP to 8109,
            RpcTransport.QUIC to 8200,
            RpcTransport.HTTP1 to 8081,
            RpcTransport.HTTP3 to 8443,
            RpcTransport.WS_TCP to 8002,
            RpcTransport.WS_UDP to 8101,
            RpcTransport.WS_RTP to 8110,
            RpcTransport.WS_RUDP to 8111,
            RpcTransport.WS_COAP to 8113,
            RpcTransport.WS_QUIC to 8444,
            RpcTransport.WS_HTTP1 to 8001,
            RpcTransport.WS_HTTP3 to 8043,
        )
    }
}
