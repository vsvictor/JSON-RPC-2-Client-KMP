# JsonRPCClientKMP

A single Kotlin Multiplatform client for the Rust [`json-rpc-2`](https://github.com/vsvictor/JSON-RPC-2) client and its UniFFI API. The public API and client configuration live in `commonMain`; Android calls the generated UniFFI Kotlin/JNA binding, and iOS calls the same Rust FFI through a small Swift-to-C ABI bridge.

The project is a single Gradle KMP module at the repository root. No Rust crate sources are vendored: `scripts/build-mobile.sh` fetches the selected revision from the remote GitHub repository before generating bindings and native libraries. Set `JSON_RPC_2_REF` to a tag, branch, or commit to select a different revision; it defaults to the upstream `start` branch.

## Requirements

- JDK 17+
- Rust toolchain and `rustup`
- Xcode command-line tools
- Android SDK/NDK and `cargo-ndk` for Android builds

### Android C++ runtime

The client module automatically packages `libc++_shared.so` for all four Android ABIs into its AAR. Apps depending on this module receive the runtime transitively; no app-side copy step or manual script invocation is needed.

The `prepareAndroidCppRuntime` Gradle task reads the NDK from `-PjsonRpcNdkDirectory=/path/to/ndk`, then `ANDROID_NDK_HOME`, then `ANDROID_NDK_ROOT`. If none is set, it uses Android SDK's `ndk/30.0.15729638`, the version used for the supplied native artifacts. Select the same NDK used to compile the Rust FFI when rebuilding native libraries. A missing runtime fails the build rather than producing an APK that crashes at load time.

On macOS, build platform artifacts with:

```sh
./scripts/build-android.sh
./scripts/build-ios.sh
```

Or use `./scripts/build-mobile.sh` to build both targets. The Android build produces `jniLibs` for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. The iOS build produces static client archives for arm64 device and arm64 simulator. The generated UniFFI Kotlin binding is refreshed under `src/androidMain/kotlin/uniffi/json_rpc_2_ffi`.

Build the KMP module after generating native artifacts:

```sh
./gradlew assemble
./gradlew compileKotlinIosArm64 compileKotlinIosSimulatorArm64
```

## Usage

```kotlin
val client = JsonRpcClientBuilder.builder(
    transport = RpcTransport.TLS,
    host = "localhost",
    port = 12345,
)
    .caCertificatePem(serverCertificatePem)
    .serverName("localhost")
    .connect()

val resultJson = client.call("echo", """{"message":"hello"}""")
client.disconnect()
```

`call` returns the JSON-encoded `result`; `paramsJson` must be a JSON object or array, as required by the Rust FFI. `notify`, ping controls, and request-timeout configuration are available on the same client. Native blocking calls are dispatched off the caller thread.

The sample app records connection setup stages, operation outcomes, and full error stack traces in its **Connection and error log** panel and standard output. Certificate contents and passphrases are not included in the log.

## Connection monitoring and recovery

After `connect()`, the native client starts periodic transport ping/pong checks every 1000 ms by default. Configure the initial interval with `builder.pingFrequency(milliseconds)` or change it on an existing connection with `client.setPingFrequency(milliseconds)`. Setting it to `0` disables periodic checks.

A successful Pong resets the consecutive-miss counter. After 3 consecutive missed Pongs (configurable with `builder.maxMissedPongs(count)` or `client.setMaxMissedPongs(count)`), the native client makes up to 3 attempts to restore the transport. A successful restore stops these attempts and resets the counter. If all 3 attempts fail, it reconnects using the transport's original connection settings. If reconnection fails, monitoring continues and recovery is retried on subsequent failed checks. `disconnect()` stops the native monitoring task.

Each periodic ping and each restore attempt has a deadline equal to the configured ping interval, so a server that accepts a connection but never sends Pong cannot stall monitoring indefinitely. Reconnection is bounded by the client's request timeout.

In **Runtime controls**, **Log every periodic ping-pong** only controls visibility of native Ping/Pong events; it never starts a separate loop or disables recovery. Errors and recovery/reconnection events remain visible regardless of the checkbox. The panel retains the latest 500 entries. The native log buffer retains 512 entries and reports any overflow when drained. `client.takeLivenessLog()` drains these events without sending network traffic.

`scripts/mobile-liveness.patch` supplies the native recovery/logging changes and is reapplied by `scripts/build-mobile.sh` before building the FFI and generating Android/iOS bindings.

## Certificates and platform assets

Provide certificate contents directly with `caCertificatePem`, or use `caCertificate(bytes, CertificateFormat.PEM/DER)`. DER X.509 bytes are base64-wrapped as a PEM `CERTIFICATE` block before they reach the Rust API.

```kotlin
val builder = JsonRpcClientBuilder(RpcTransport.QUIC, "localhost", 443)
    .caCertificateAsset("server-ca.der", CertificateFormat.DER)
```

For file access, use `caCertificateFile(path, format)`. These assets belong to the **application that consumes this KMP library**, not to this library's own source sets. In an Android app, place the file in the app module's `src/main/assets/` and call `JsonRpcClientKmp.installAndroidContext(applicationContext)` once before loading it. In an iOS app, add the file to the app target's **Copy Bundle Resources**; the library resolves it from the main app bundle. Filesystem paths are interpreted by the platform as native paths.

For a Compose Multiplatform app, the portable option is a shared Compose resource: place the certificate in the consuming app's `src/commonMain/composeResources/files/` (not in the library module), then pass its bytes to `caCertificateResource`. Use the generated `Res.readBytes` accessor from the module that owns the resource:

```kotlin
import jsonrpcclientkmpexample.shared.generated.resources.Res

val builder = JsonRpcClientBuilder.builder(RpcTransport.TLS, "localhost", 12345)
    .caCertificateResource(
        readBytes = { Res.readBytes("files/server-ca.der") },
        format = CertificateFormat.DER,
    )
```

For a PEM file, use `format = CertificateFormat.PEM`. The `readBytes` lambda is suspendable and can call the generated Compose `Res.readBytes` API directly.

## Transports

All 30 `FfiTransportKind` values exposed by the Rust FFI are represented by `RpcTransport`, including raw sockets, TLS/QUIC/HTTP, broker transports, and the WebSocket variants. TLS, QUIC, HTTP/3, and secure WebSocket transports use the configured CA PEM. SRT transports additionally require `srtPassphrase`; MQTT and AMQP accept `BrokerOptions`; UDP-Lite accepts checksum coverage options.

The Rust library itself limits UDP-Lite, DCCP, and SCTP (including their WebSocket variants) to Linux/Android. These enum values remain available in the common API, but connection attempts on iOS are rejected with a clear unsupported-platform error by the Rust FFI.

## Updating the Rust library

```sh
JSON_RPC_2_REF=<tag-or-commit> ./scripts/build-mobile.sh
```

The build scripts work from a clean checkout and keep the upstream client implementation external. They regenerate the native artifacts and UniFFI bindings from the chosen remote revision; do not edit generated binding sources by hand.

For mobile builds, the helper enables the upstream SRT crate's bundled mbedTLS feature in its disposable checkout, preserving SRT passphrase encryption without modifying the remote repository. It applies an Android CMake search-path patch to a disposable copy of `tstrans-srt-sys` rather than changing Cargo's registry cache. Android builds use the NDK selected by `ANDROID_NDK_HOME` (or `ANDROID_NDK_ROOT` when the former is unset).
