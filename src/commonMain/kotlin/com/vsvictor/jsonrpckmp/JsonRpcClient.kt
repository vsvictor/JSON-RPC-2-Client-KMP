package com.vsvictor.jsonrpckmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64

enum class RpcTransport(
    internal val wireName: String,
    val supportedOnIos: Boolean = true,
) {
    TCP("tcp"),
    TLS("tls"),
    UDP("udp"),
    RTP("rtp"),
    RUDP("rudp"),
    SRT("srt"),
    UDP_LITE("udpLite", supportedOnIos = false),
    DCCP("dccp", supportedOnIos = false),
    SCTP("sctp", supportedOnIos = false),
    MQTT("mqtt"),
    COAP("coap"),
    AMQP("amqp"),
    HTTP1("http1"),
    WS_TCP("wsTcp"),
    WS_UDP("wsUdp"),
    WS_RTP("wsRtp"),
    WS_RUDP("wsRudp"),
    WS_SRT("wsSrt"),
    WS_UDP_LITE("wsUdpLite", supportedOnIos = false),
    WS_DCCP("wsDccp", supportedOnIos = false),
    WS_SCTP("wsSctp", supportedOnIos = false),
    WS_MQTT("wsMqtt"),
    WS_COAP("wsCoap"),
    WS_AMQP("wsAmqp"),
    WS_HTTP1("wsHttp1"),
    WS_TLS("wsTls"),
    QUIC("quic"),
    HTTP3("http3"),
    WS_QUIC("wsQuic"),
    WS_HTTP3("wsHttp3"),
}

enum class CertificateFormat { PEM, DER }

data class BrokerOptions(
    val url: String? = null,
    val route: String? = null,
    val caCertificatePem: String? = null,
    val operationTimeoutMillis: ULong? = null,
    val responseTimeoutMillis: ULong? = null,
)

internal data class NativeConnectionOptions(
    val transport: RpcTransport,
    val host: String,
    val port: UShort,
    val caCertificatePem: String?,
    val serverName: String?,
    val srtPassphrase: String?,
    val udpLiteSendCoverage: UShort?,
    val udpLiteReceiveCoverage: UShort?,
    val broker: BrokerOptions,
) {
    fun toJson(): String = buildString {
        append('{')
        appendJson("transport", transport.wireName)
        appendJson("host", host)
        appendNumber("port", port.toString())
        appendJson("caPem", caCertificatePem)
        appendJson("serverName", serverName)
        appendJson("srtPassphrase", srtPassphrase)
        appendNumber("udpliteSendCoverage", udpLiteSendCoverage?.toString())
        appendNumber("udpliteRecvCoverage", udpLiteReceiveCoverage?.toString())
        appendJson("brokerUrl", broker.url)
        appendJson("brokerRoute", broker.route)
        appendJson("brokerCaPem", broker.caCertificatePem)
        appendNumber("brokerOperationTimeoutMs", broker.operationTimeoutMillis?.toString())
        appendNumber("brokerResponseTimeoutMs", broker.responseTimeoutMillis?.toString())
        append('}')
    }

    private fun StringBuilder.appendJson(key: String, value: String?) {
        if (length > 1) append(',')
        append('"').append(key).append("\":")
        if (value == null) {
            append("null")
        } else {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') append(
                        "\\u${
                            char.code.toString(16).padStart(4, '0')
                        }"
                    ) else append(char)
                }
            }
            append('"')
        }
    }

    private fun StringBuilder.appendNumber(key: String, value: String?) {
        if (length > 1) append(',')
        append('"').append(key).append("\":").append(value ?: "null")
    }
}

internal interface PlatformRpcClient {
    fun call(method: String, paramsJson: String?): String
    fun notify(method: String, paramsJson: String?)
    fun transportPing()
    fun setPingFrequency(frequencyMillis: ULong)
    fun setMaxMissedPongs(count: UInt)
    fun setRequestTimeout(timeoutMillis: ULong)
    fun takeLivenessLog(): String
    fun disconnect()
    fun release()
}

internal expect fun connectPlatform(options: NativeConnectionOptions): PlatformRpcClient
internal expect fun readCertificateFile(path: String): ByteArray
internal expect fun readCertificateAsset(name: String): ByteArray

class JsonRpcClient internal constructor(private val native: PlatformRpcClient) {
    private var disconnected = false
    private val lifecycleMutex = Mutex()

    suspend fun call(method: String, paramsJson: String? = null): String =
        withContext(Dispatchers.Default) {
            checkConnected()
            native.call(method, paramsJson)
        }

    suspend fun notify(method: String, paramsJson: String? = null) =
        withContext(Dispatchers.Default) {
            checkConnected()
            native.notify(method, paramsJson)
        }

    suspend fun transportPing() = withContext(Dispatchers.Default) {
        checkConnected()
        native.transportPing()
    }

    suspend fun setPingFrequency(frequencyMillis: ULong) = withContext(Dispatchers.Default) {
        checkConnected()
        native.setPingFrequency(frequencyMillis)
    }

    suspend fun setMaxMissedPongs(count: UInt) = withContext(Dispatchers.Default) {
        checkConnected()
        native.setMaxMissedPongs(count)
    }

    suspend fun setRequestTimeout(timeoutMillis: ULong) = withContext(Dispatchers.Default) {
        checkConnected()
        require(timeoutMillis > 0uL) { "Request timeout must be greater than zero" }
        native.setRequestTimeout(timeoutMillis)
    }

    suspend fun takeLivenessLog(): List<String> = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            checkConnected()
            native.takeLivenessLog().lineSequence().filter(String::isNotEmpty).toList()
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            if (!disconnected) {
                try {
                    native.disconnect()
                } finally {
                    disconnected = true
                    native.release()
                }
            }
        }
    }

    private fun checkConnected() {
        check(!disconnected) { "JSON-RPC client is disconnected" }
    }
}

@Suppress("unused")
class JsonRpcClientBuilder(
    private val transport: RpcTransport,
    private val host: String,
    private val port: Int,
) {
    private var caCertificatePem: String? = null
    private var serverName: String? = null
    private var srtPassphrase: String? = null
    private var udpLiteSendCoverage: UShort? = null
    private var udpLiteReceiveCoverage: UShort? = null
    private var brokerOptions = BrokerOptions()
    private var pingFrequencyMillis = 1000uL
    private var maxMissedPongs = 3u

    companion object {
        fun builder(transport: RpcTransport, host: String, port: Int) =
            JsonRpcClientBuilder(transport, host, port)
    }

    fun caCertificatePem(pem: String) = apply {
        require(pem.contains("-----BEGIN CERTIFICATE-----")) {
            "The certificate must be PEM encoded"
        }
        caCertificatePem = pem
    }

    fun caCertificate(bytes: ByteArray, format: CertificateFormat) = apply {
        caCertificatePem = certificateToPem(bytes, format)
    }

    suspend fun caCertificateFile(
        path: String,
        format: CertificateFormat = CertificateFormat.PEM
    ): JsonRpcClientBuilder {
        val bytes = withContext(Dispatchers.Default) { readCertificateFile(path) }
        return caCertificate(bytes, format)
    }

    suspend fun caCertificateAsset(
        name: String,
        format: CertificateFormat = CertificateFormat.PEM
    ): JsonRpcClientBuilder {
        val bytes = withContext(Dispatchers.Default) { readCertificateAsset(name) }
        return caCertificate(bytes, format)
    }

    suspend fun caCertificateResource(
        readBytes: suspend () -> ByteArray,
        format: CertificateFormat = CertificateFormat.PEM,
    ): JsonRpcClientBuilder = caCertificate(readBytes(), format)

    fun serverName(name: String) = apply { serverName = name }
    fun srtPassphrase(passphrase: String) = apply { srtPassphrase = passphrase }
    fun udpLiteChecksumCoverage(send: UShort? = null, receive: UShort? = null) = apply {
        udpLiteSendCoverage = send
        udpLiteReceiveCoverage = receive
    }

    fun broker(options: BrokerOptions) = apply { brokerOptions = options }
    fun pingFrequency(frequencyMillis: ULong) = apply { pingFrequencyMillis = frequencyMillis }
    fun maxMissedPongs(count: UInt) = apply {
        require(count > 0u) { "Maximum missed pongs must be greater than zero" }
        maxMissedPongs = count
    }

    suspend fun connect(): JsonRpcClient = withContext(Dispatchers.Default) {
        require(host.isNotBlank()) { "Host must not be blank" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(transport != RpcTransport.SRT && transport != RpcTransport.WS_SRT || !srtPassphrase.isNullOrBlank()) {
            "SRT transport requires a passphrase"
        }
        if (!transport.supportedOnIos && isIosPlatform) {
            throw UnsupportedOperationException("${transport.name} is not available on iOS in json-rpc-2")
        }
        val native = connectPlatform(
            NativeConnectionOptions(
                transport = transport,
                host = host,
                port = port.toUShort(),
                caCertificatePem = caCertificatePem,
                serverName = serverName,
                srtPassphrase = srtPassphrase,
                udpLiteSendCoverage = udpLiteSendCoverage,
                udpLiteReceiveCoverage = udpLiteReceiveCoverage,
                broker = brokerOptions,
            )
        )
        try {
            native.setMaxMissedPongs(maxMissedPongs)
            native.setPingFrequency(pingFrequencyMillis)
            JsonRpcClient(native)
        } catch (error: Throwable) {
            try {
                native.disconnect()
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            try {
                native.release()
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }
}

internal fun certificateToPem(bytes: ByteArray, format: CertificateFormat): String {
    require(bytes.isNotEmpty()) { "Certificate data is empty" }
    val pem = when (format) {
        CertificateFormat.PEM -> bytes.decodeToString()
        CertificateFormat.DER -> {
            val base64 = Base64.encode(bytes)
            buildString {
                append("-----BEGIN CERTIFICATE-----\n")
                base64.chunked(64).forEach { append(it).append('\n') }
                append("-----END CERTIFICATE-----\n")
            }
        }
    }
    require(pem.contains("-----BEGIN CERTIFICATE-----")) { "Invalid PEM certificate" }
    return pem
}

internal expect val isIosPlatform: Boolean
