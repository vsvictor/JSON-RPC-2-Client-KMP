package com.vsvictor.jsonrpckmp

import com.vsvictor.jsonrpckmp.ffi.jsonrpc_client_call
import com.vsvictor.jsonrpckmp.ffi.jsonrpc_client_connect
import com.vsvictor.jsonrpckmp.ffi.jsonrpc_client_disconnect
import com.vsvictor.jsonrpckmp.ffi.jsonrpc_client_notify
import com.vsvictor.jsonrpckmp.ffi.jsonrpc_client_release
import com.vsvictor.jsonrpckmp.ffi.jsonrpc_client_set_max_missed_pongs
import com.vsvictor.jsonrpckmp.ffi.jsonrpc_client_set_ping_frequency
import com.vsvictor.jsonrpckmp.ffi.jsonrpc_client_set_request_timeout
import com.vsvictor.jsonrpckmp.ffi.jsonrpc_client_transport_ping
import com.vsvictor.jsonrpckmp.ffi.jsonrpc_client_take_liveness_log
import com.vsvictor.jsonrpckmp.ffi.jsonrpc_string_free
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

internal actual val isIosPlatform: Boolean = true

@OptIn(ExperimentalForeignApi::class)
internal actual fun connectPlatform(options: NativeConnectionOptions): PlatformRpcClient = memScoped {
    val error = alloc<CPointerVar<ByteVar>>()
    error.value = null
    val handle = jsonrpc_client_connect(options.toJson().cstr, error.ptr)
        ?: throw ffiException(error.value)
    IosRpcClient(handle)
}

internal actual fun readCertificateFile(path: String): ByteArray {
    val data = NSData.dataWithContentsOfFile(path)
        ?: throw IllegalArgumentException("Unable to read certificate file: $path")
    return data.toByteArray()
}

internal actual fun readCertificateAsset(name: String): ByteArray {
    val extensionStart = name.lastIndexOf('.')
    val fileName = if (extensionStart >= 0) name.substring(0, extensionStart) else name
    val extension = if (extensionStart >= 0) name.substring(extensionStart + 1) else null
    val path = NSBundle.mainBundle.pathForResource(
        name = fileName,
        ofType = extension,
    ) ?: throw IllegalArgumentException("Certificate asset not found in the main bundle: $name")
    val data = NSData.dataWithContentsOfFile(path)
        ?: throw IllegalArgumentException("Unable to read certificate asset: $name")
    return data.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val output = ByteArray(size)
    if (size > 0) {
        val source = bytes ?: throw IllegalStateException("Certificate data has no byte buffer")
        output.usePinned { pinned -> memcpy(pinned.addressOf(0), source, size.toULong()) }
    }
    return output
}

@OptIn(ExperimentalForeignApi::class)
private class IosRpcClient(private val handle: COpaquePointer) : PlatformRpcClient {
    override fun call(method: String, paramsJson: String?): String = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        error.value = null
        val result = jsonrpc_client_call(handle, method.cstr, paramsJson?.cstr, error.ptr)
            ?: throw ffiException(error.value)
        try {
            result.toKString()
        } finally {
            jsonrpc_string_free(result)
        }
    }

    override fun notify(method: String, paramsJson: String?) = checked { error ->
        jsonrpc_client_notify(handle, method.cstr, paramsJson?.cstr, error)
    }

    override fun transportPing() = checked { error -> jsonrpc_client_transport_ping(handle, error) }
    override fun setPingFrequency(frequencyMillis: ULong) = checked { error ->
        jsonrpc_client_set_ping_frequency(handle, frequencyMillis, error)
    }
    override fun setMaxMissedPongs(count: UInt) = checked { error ->
        jsonrpc_client_set_max_missed_pongs(handle, count, error)
    }
    override fun setRequestTimeout(timeoutMillis: ULong) = checked { error ->
        jsonrpc_client_set_request_timeout(handle, timeoutMillis, error)
    }
    override fun disconnect() = checked { error -> jsonrpc_client_disconnect(handle, error) }
    override fun takeLivenessLog(): String = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        error.value = null
        val result = jsonrpc_client_take_liveness_log(handle, error.ptr)
            ?: throw ffiException(error.value)
        try {
            result.toKString()
        } finally {
            jsonrpc_string_free(result)
        }
    }
    override fun release() = jsonrpc_client_release(handle)

    private inline fun checked(block: (CValuesRef<CPointerVar<ByteVar>>) -> Int) = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        error.value = null
        if (block(error.ptr) != 0) throw ffiException(error.value)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ffiException(error: CPointer<ByteVar>?): IllegalStateException {
    val message = error?.toKString() ?: "JSON-RPC native call failed without an error message"
    if (error != null) jsonrpc_string_free(error)
    return IllegalStateException(message)
}
