package com.vsvictor.jsonrpckmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionOptionsTest {
    @Test
    fun jsonEscapesStringsAndKeepsNullOptions() {
        val json = NativeConnectionOptions(
            transport = RpcTransport.TCP,
            host = "rpc\"host\\local",
            port = 9000u,
            caCertificatePem = null,
            serverName = null,
            srtPassphrase = null,
            udpLiteSendCoverage = null,
            udpLiteReceiveCoverage = null,
            broker = BrokerOptions(),
        ).toJson()

        assertTrue(json.contains("\"host\":\"rpc\\\"host\\\\local\""))
        assertTrue(json.contains("\"caPem\":null"))
        assertTrue(json.contains("\"port\":9000"))
    }

    @Test
    fun exposesEveryTransportFromTheRustFfi() {
        assertEquals(30, RpcTransport.entries.size)
    }
}
