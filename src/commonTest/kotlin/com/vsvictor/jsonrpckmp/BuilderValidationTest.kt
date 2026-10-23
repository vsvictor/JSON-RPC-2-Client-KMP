package com.vsvictor.jsonrpckmp

import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BuilderValidationTest {
    private val pem = "-----BEGIN CERTIFICATE-----\nAQID\n-----END CERTIFICATE-----\n"

    @Test
    fun rejectsBlankHostBeforeConnecting() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            JsonRpcClientBuilder.builder(RpcTransport.TCP, "  ", 8000).connect()
        }
        assertEquals("Host must not be blank", error.message)
    }

    @Test
    fun rejectsPortsOutsideTheTcpRange() = runTest {
        listOf(0, -1, 65536).forEach { port ->
            assertFailsWith<IllegalArgumentException> {
                JsonRpcClientBuilder.builder(RpcTransport.TCP, "localhost", port).connect()
            }
        }
    }

    @Test
    fun srtTransportsRequirePassphrase() = runTest {
        listOf(RpcTransport.SRT, RpcTransport.WS_SRT).forEach { transport ->
            val error = assertFailsWith<IllegalArgumentException> {
                JsonRpcClientBuilder.builder(transport, "localhost", 9000).srtPassphrase(" ").connect()
            }
            assertEquals("SRT transport requires a passphrase", error.message)
        }
    }

    @Test
    fun kernelOnlyTransportsAreRejectedOnIos() = runTest {
        if (!isIosPlatform) return@runTest
        RpcTransport.entries.filterNot { it.supportedOnIos }.forEach { transport ->
            assertFailsWith<UnsupportedOperationException> {
                JsonRpcClientBuilder.builder(transport, "localhost", 9000).connect()
            }
        }
    }

    @Test
    fun maxMissedPongsMustBePositive() {
        assertFailsWith<IllegalArgumentException> {
            JsonRpcClientBuilder.builder(RpcTransport.TCP, "localhost", 1).maxMissedPongs(0u)
        }
    }

    @Test
    fun pemCertificateTextIsValidated() {
        val builder = JsonRpcClientBuilder.builder(RpcTransport.TLS, "localhost", 1)
        builder.caCertificatePem(pem)
        assertFailsWith<IllegalArgumentException> { builder.caCertificatePem("not a certificate") }
    }

    @Test
    fun emptyOrInvalidCertificateBytesAreRejected() = runTest {
        val builder = JsonRpcClientBuilder.builder(RpcTransport.TLS, "localhost", 1)
        assertFailsWith<IllegalArgumentException> { builder.caCertificate(ByteArray(0), CertificateFormat.DER) }
        assertFailsWith<IllegalArgumentException> {
            builder.caCertificate("garbage".encodeToByteArray(), CertificateFormat.PEM)
        }
        assertFailsWith<IllegalArgumentException> {
            builder.caCertificateResource({ ByteArray(0) }, CertificateFormat.PEM)
        }
    }

    @Test
    fun derCertificateIsWrappedAsPemWith64ColumnLines() {
        val der = ByteArray(100) { it.toByte() }
        val converted = certificateToPem(der, CertificateFormat.DER)
        val lines = converted.trimEnd().lines()

        assertEquals("-----BEGIN CERTIFICATE-----", lines.first())
        assertEquals("-----END CERTIFICATE-----", lines.last())
        val body = lines.drop(1).dropLast(1)
        assertTrue(body.dropLast(1).all { it.length == 64 })
        assertEquals(der.toList(), Base64.decode(body.joinToString("")).toList())
    }

    @Test
    fun pemBytesArePassedThroughUnchanged() {
        assertEquals(pem, certificateToPem(pem.encodeToByteArray(), CertificateFormat.PEM))
    }

    @Test
    fun missingCertificateFileFails() = runTest {
        assertFailsWith<Throwable> {
            JsonRpcClientBuilder.builder(RpcTransport.TLS, "localhost", 1)
                .caCertificateFile("/definitely/missing/ca.pem")
        }
    }
}
