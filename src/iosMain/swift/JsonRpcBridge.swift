import Foundation
import json_rpc_2_ffiFFI

private final class JsonRpcNativeClient {
    let client: RpcClient

    init(_ client: RpcClient) {
        self.client = client
    }
}

private enum JsonRpcBridgeError: Error, LocalizedError {
    case invalidOptions(String)
    case unsupportedTransport(String)

    var errorDescription: String? {
        switch self {
        case .invalidOptions(let details), .unsupportedTransport(let details):
            return details
        }
    }
}

private func writeError(_ error: Error, to output: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?) {
    output?.pointee = strdup(error.localizedDescription)
}

private func nativeClient(_ pointer: UnsafeMutableRawPointer) -> JsonRpcNativeClient {
    Unmanaged<JsonRpcNativeClient>.fromOpaque(pointer).takeUnretainedValue()
}

private func transportKind(_ value: String) throws -> FfiTransportKind {
    switch value {
    case "tcp": .tcp
    case "tls": .tls
    case "udp": .udp
    case "rtp": .rtp
    case "rudp": .rudp
    case "srt": .srt
    case "udpLite": .udpLite
    case "dccp": .dccp
    case "sctp": .sctp
    case "mqtt": .mqtt
    case "coap": .coap
    case "amqp": .amqp
    case "http1": .http1
    case "wsTcp": .wsTcp
    case "wsUdp": .wsUdp
    case "wsRtp": .wsRtp
    case "wsRudp": .wsRudp
    case "wsSrt": .wsSrt
    case "wsUdpLite": .wsUdpLite
    case "wsDccp": .wsDccp
    case "wsSctp": .wsSctp
    case "wsMqtt": .wsMqtt
    case "wsCoap": .wsCoap
    case "wsAmqp": .wsAmqp
    case "wsHttp1": .wsHttp1
    case "wsTls": .wsTls
    case "quic": .quic
    case "http3": .http3
    case "wsQuic": .wsQuic
    case "wsHttp3": .wsHttp3
    default: throw JsonRpcBridgeError.unsupportedTransport("Unknown transport: \(value)")
    }
}

private func string(_ values: [String: Any], _ key: String, required: Bool = false) throws -> String? {
    guard let value = values[key] as? String else {
        if required { throw JsonRpcBridgeError.invalidOptions("Missing string option '\(key)'") }
        return nil
    }
    return value
}

private func unsigned<T: FixedWidthInteger>(
    _ values: [String: Any],
    _ key: String,
    as: T.Type,
    required: Bool = false
) throws -> T? {
    guard let number = values[key] as? NSNumber, let value = T(exactly: number.int64Value) else {
        if required { throw JsonRpcBridgeError.invalidOptions("Missing or invalid numeric option '\(key)'") }
        return nil
    }
    return value
}

@_cdecl("jsonrpc_client_connect")
public func jsonrpc_client_connect(
    _ optionsJSON: UnsafePointer<CChar>?,
    _ errorOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> UnsafeMutableRawPointer? {
    errorOut?.pointee = nil
    do {
        guard let optionsJSON,
              let data = String(cString: optionsJSON).data(using: .utf8),
              let values = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            throw JsonRpcBridgeError.invalidOptions("Connection options must be a JSON object")
        }

        guard let transportName = try string(values, "transport", required: true),
              let host = try string(values, "host", required: true),
              let port = try unsigned(values, "port", as: UInt16.self, required: true)
        else {
            throw JsonRpcBridgeError.invalidOptions("Transport, host, and port are required")
        }

        let options = ConnectOptions(
            host: host,
            port: port,
            caPem: try string(values, "caPem"),
            serverName: try string(values, "serverName"),
            srtPassphrase: try string(values, "srtPassphrase"),
            udpliteSendCoverage: try unsigned(values, "udpliteSendCoverage", as: UInt16.self),
            udpliteRecvCoverage: try unsigned(values, "udpliteRecvCoverage", as: UInt16.self),
            brokerUrl: try string(values, "brokerUrl"),
            brokerRoute: try string(values, "brokerRoute"),
            brokerCaPem: try string(values, "brokerCaPem"),
            brokerOperationTimeoutMs: try unsigned(values, "brokerOperationTimeoutMs", as: UInt64.self),
            brokerResponseTimeoutMs: try unsigned(values, "brokerResponseTimeoutMs", as: UInt64.self)
        )
        let builder = RpcClientBuilder(kind: try transportKind(transportName), options: options)
        if let pem = options.caPem {
            try builder.setCaCertificatePem(pem: pem)
        }
        let client = try builder.connect()
        let retained = JsonRpcNativeClient(client)
        return Unmanaged.passRetained(retained).toOpaque()
    } catch {
        writeError(error, to: errorOut)
        return nil
    }
}

@_cdecl("jsonrpc_client_call")
public func jsonrpc_client_call(
    _ pointer: UnsafeMutableRawPointer?,
    _ method: UnsafePointer<CChar>?,
    _ paramsJSON: UnsafePointer<CChar>?,
    _ errorOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> UnsafeMutablePointer<CChar>? {
    errorOut?.pointee = nil
    do {
        guard let pointer, let method else {
            throw JsonRpcBridgeError.invalidOptions("Client and method are required")
        }
        let result = try nativeClient(pointer).client.call(
            method: String(cString: method),
            paramsJson: paramsJSON.map(String.init(cString:))
        )
        guard let allocated = strdup(result) else {
            throw JsonRpcBridgeError.invalidOptions("Unable to allocate RPC result")
        }
        return allocated
    } catch {
        writeError(error, to: errorOut)
        return nil
    }
}

@_cdecl("jsonrpc_client_take_liveness_log")
public func jsonrpc_client_take_liveness_log(
    _ pointer: UnsafeMutableRawPointer?,
    _ errorOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> UnsafeMutablePointer<CChar>? {
    errorOut?.pointee = nil
    do {
        guard let pointer else {
            throw JsonRpcBridgeError.invalidOptions("Client handle is null")
        }
        guard let result = strdup(nativeClient(pointer).client.takeLivenessLog()) else {
            throw JsonRpcBridgeError.invalidOptions("Unable to allocate liveness log")
        }
        return result
    } catch {
        writeError(error, to: errorOut)
        return nil
    }
}

@_cdecl("jsonrpc_client_notify")
public func jsonrpc_client_notify(
    _ pointer: UnsafeMutableRawPointer?,
    _ method: UnsafePointer<CChar>?,
    _ paramsJSON: UnsafePointer<CChar>?,
    _ errorOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    errorOut?.pointee = nil
    do {
        guard let pointer, let method else {
            throw JsonRpcBridgeError.invalidOptions("Client and method are required")
        }
        try nativeClient(pointer).client.notify(
            method: String(cString: method),
            paramsJson: paramsJSON.map(String.init(cString:))
        )
        return 0
    } catch {
        writeError(error, to: errorOut)
        return 1
    }
}

@_cdecl("jsonrpc_client_transport_ping")
public func jsonrpc_client_transport_ping(
    _ pointer: UnsafeMutableRawPointer?,
    _ errorOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    perform(pointer, errorOut) { try $0.transportPing() }
}

@_cdecl("jsonrpc_client_set_ping_frequency")
public func jsonrpc_client_set_ping_frequency(
    _ pointer: UnsafeMutableRawPointer?,
    _ frequency: UInt64,
    _ errorOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    perform(pointer, errorOut) { try $0.setPingFrequency(frequencyMs: frequency) }
}

@_cdecl("jsonrpc_client_set_max_missed_pongs")
public func jsonrpc_client_set_max_missed_pongs(
    _ pointer: UnsafeMutableRawPointer?,
    _ count: UInt32,
    _ errorOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    perform(pointer, errorOut) { try $0.setMaxMissedPongs(count: count) }
}

@_cdecl("jsonrpc_client_set_request_timeout")
public func jsonrpc_client_set_request_timeout(
    _ pointer: UnsafeMutableRawPointer?,
    _ timeout: UInt64,
    _ errorOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    perform(pointer, errorOut) { try $0.setRequestTimeoutMs(timeoutMs: timeout) }
}

@_cdecl("jsonrpc_client_disconnect")
public func jsonrpc_client_disconnect(
    _ pointer: UnsafeMutableRawPointer?,
    _ errorOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    perform(pointer, errorOut) { try $0.disconnect() }
}

private func perform(
    _ pointer: UnsafeMutableRawPointer?,
    _ errorOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?,
    _ operation: (RpcClient) throws -> Void
) -> Int32 {
    errorOut?.pointee = nil
    do {
        guard let pointer else {
            throw JsonRpcBridgeError.invalidOptions("Client handle is null")
        }
        try operation(nativeClient(pointer).client)
        return 0
    } catch {
        writeError(error, to: errorOut)
        return 1
    }
}

@_cdecl("jsonrpc_client_release")
public func jsonrpc_client_release(_ pointer: UnsafeMutableRawPointer?) {
    guard let pointer else { return }
    _ = Unmanaged<JsonRpcNativeClient>.fromOpaque(pointer).takeRetainedValue()
}

@_cdecl("jsonrpc_string_free")
public func jsonrpc_string_free(_ value: UnsafeMutablePointer<CChar>?) {
    free(value)
}
