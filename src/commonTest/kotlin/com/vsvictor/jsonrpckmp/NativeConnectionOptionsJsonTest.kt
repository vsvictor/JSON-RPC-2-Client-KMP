package com.vsvictor.jsonrpckmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeConnectionOptionsJsonTest {
    private fun options(
        transport: RpcTransport = RpcTransport.TCP,
        host: String = "localhost",
        broker: BrokerOptions = BrokerOptions(),
        udpSend: UShort? = null,
        udpReceive: UShort? = null,
    ) = NativeConnectionOptions(
        transport = transport,
        host = host,
        port = 8000u,
        caCertificatePem = "-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----\n",
        serverName = "server",
        srtPassphrase = "secret",
        udpLiteSendCoverage = udpSend,
        udpLiteReceiveCoverage = udpReceive,
        broker = broker,
    )

    @Test
    fun serializesEveryFieldInTheFfiContract() {
        val json = options(
            transport = RpcTransport.WS_MQTT,
            broker = BrokerOptions("mqtt://b", "rpc/topic", "CA", 10uL, 20uL),
            udpSend = 8u,
            udpReceive = 16u,
        ).toJson()

        val expected = """{"transport":"wsMqtt","host":"localhost","port":8000,""" +
            """"caPem":"-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----\n",""" +
            """"serverName":"server","srtPassphrase":"secret",""" +
            """"udpliteSendCoverage":8,"udpliteRecvCoverage":16,""" +
            """"brokerUrl":"mqtt://b","brokerRoute":"rpc/topic","brokerCaPem":"CA",""" +
            """"brokerOperationTimeoutMs":10,"brokerResponseTimeoutMs":20}"""
        assertEquals(expected, json)
    }

    @Test
    fun nullNumbersAreEncodedAsJsonNull() {
        val json = options().toJson()
        assertTrue(""""udpliteSendCoverage":null""" in json)
        assertTrue(""""brokerOperationTimeoutMs":null""" in json)
        assertTrue(""""brokerUrl":null""" in json)
    }

    @Test
    fun escapesControlAndSpecialCharacters() {
        val json = options(host = "a\"b\\c\b\u000C\n\r\t\u0001д🙂").toJson()
        assertTrue(""""host":"a\"b\\c\b\f\n\r\t\u0001д🙂"""" in json, json)
    }

    @Test
    fun ulongValuesAboveLongRangeAreNotNegative() {
        val json = options(broker = BrokerOptions(operationTimeoutMillis = ULong.MAX_VALUE)).toJson()
        assertTrue(""""brokerOperationTimeoutMs":18446744073709551615""" in json)
        assertFalse("-" in json.substringAfter("brokerOperationTimeoutMs"))
    }

    @Test
    fun transportWireNamesAreUnique() {
        val names = RpcTransport.entries.map { it.wireName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun onlyKernelSpecificTransportsAreUnavailableOnIos() {
        val unsupported = RpcTransport.entries.filterNot { it.supportedOnIos }.toSet()
        assertEquals(
            setOf(
                RpcTransport.UDP_LITE,
                RpcTransport.DCCP,
                RpcTransport.SCTP,
                RpcTransport.WS_UDP_LITE,
                RpcTransport.WS_DCCP,
                RpcTransport.WS_SCTP,
            ),
            unsupported,
        )
    }
}
