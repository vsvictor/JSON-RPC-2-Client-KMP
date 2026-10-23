package com.vsvictor.jsonrpckmp

import android.content.Context
import uniffi.json_rpc_2_ffi.ConnectOptions
import uniffi.json_rpc_2_ffi.FfiTransportKind
import uniffi.json_rpc_2_ffi.RpcClient as RustRpcClient
import uniffi.json_rpc_2_ffi.RpcClientBuilder as RustRpcClientBuilder
import java.io.File

private var applicationContext: Context? = null

@Suppress("unused")
object JsonRpcClientKmp {
    fun installAndroidContext(context: Context) {
        applicationContext = context.applicationContext
    }
}

internal actual val isIosPlatform: Boolean = false

internal actual fun connectPlatform(options: NativeConnectionOptions): PlatformRpcClient {
    val ffiOptions = ConnectOptions(
        host = options.host,
        port = options.port,
        caPem = options.caCertificatePem,
        serverName = options.serverName,
        srtPassphrase = options.srtPassphrase,
        udpliteSendCoverage = options.udpLiteSendCoverage,
        udpliteRecvCoverage = options.udpLiteReceiveCoverage,
        brokerUrl = options.broker.url,
        brokerRoute = options.broker.route,
        brokerCaPem = options.broker.caCertificatePem,
        brokerOperationTimeoutMs = options.broker.operationTimeoutMillis,
        brokerResponseTimeoutMs = options.broker.responseTimeoutMillis,
    )
    val ffiKind = options.transport.wireName
        .replace(Regex("([a-z])([A-Z])")) { match ->
            "${match.groupValues[1]}_${match.groupValues[2]}"
        }
        .uppercase()
    val builder = RustRpcClientBuilder(FfiTransportKind.valueOf(ffiKind), ffiOptions)
    options.caCertificatePem?.let(builder::setCaCertificatePem)
    return AndroidRpcClient(builder.connect())
}

internal actual fun readCertificateFile(path: String): ByteArray = File(path).readBytes()

internal actual fun readCertificateAsset(name: String): ByteArray {
    val context = checkNotNull(applicationContext) {
        "Call JsonRpcClientKmp.installAndroidContext(context) before loading an Android asset"
    }
    return context.assets.open(name).use { it.readBytes() }
}

private class AndroidRpcClient(private val client: RustRpcClient) : PlatformRpcClient {
    override fun call(method: String, paramsJson: String?) = client.call(method, paramsJson)
    override fun notify(method: String, paramsJson: String?) = client.notify(method, paramsJson)
    override fun transportPing() = client.transportPing()
    override fun setPingFrequency(frequencyMillis: ULong) = client.setPingFrequency(frequencyMillis)
    override fun setMaxMissedPongs(count: UInt) = client.setMaxMissedPongs(count)
    override fun setRequestTimeout(timeoutMillis: ULong) = client.setRequestTimeoutMs(timeoutMillis)
    override fun takeLivenessLog() = client.takeLivenessLog()
    override fun disconnect() = client.disconnect()
    override fun release() = client.close()
}
