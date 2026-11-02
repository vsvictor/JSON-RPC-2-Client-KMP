#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM_URL="https://github.com/vsvictor/JSON-RPC-2.git"
UPSTREAM_REF="${JSON_RPC_2_REF:-start}"
UPSTREAM_DIR="${JSON_RPC_2_DIR:-$PROJECT_ROOT/build/upstream/JSON-RPC-2}"
if [[ ! -f "$UPSTREAM_DIR/Cargo.toml" && -e "$UPSTREAM_DIR" ]]; then
    UPSTREAM_DIR="${UPSTREAM_DIR}-refreshed"
fi
LIVENESS_PATCH="$PROJECT_ROOT/scripts/mobile-liveness.patch"
GENERATED="$PROJECT_ROOT/build/generated"
ANDROID_OUT="$PROJECT_ROOT/src/androidMain/jniLibs"
IOS_OUT="$PROJECT_ROOT/build/native/ios"

usage() {
    echo "Usage: $0 [android|ios|all]" >&2
    exit 2
}

TARGET="${1:-all}"
case "$TARGET" in
    android|ios|all) ;;
    *) usage ;;
esac

for tool in git cargo rustup python3; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Missing required tool: $tool" >&2
        exit 1
    fi
done

if [[ "$TARGET" == ios || "$TARGET" == all ]]; then
    case "$(uname -s)" in
        Darwin) ;;
        *) echo "The iOS build requires macOS and Xcode." >&2; exit 1 ;;
    esac
    for tool in xcrun swiftc; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            echo "Missing required iOS tool: $tool" >&2
            exit 1
        fi
    done
fi

if [[ "$TARGET" == android || "$TARGET" == all ]] && ! command -v cargo-ndk >/dev/null 2>&1; then
    echo "Install cargo-ndk (`cargo install cargo-ndk`) before building Android artifacts." >&2
    exit 1
fi

if [[ "$TARGET" == android || "$TARGET" == all ]]; then
    ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
    if [[ -z "$ANDROID_NDK_HOME" ]]; then
        echo "Set ANDROID_NDK_HOME to the installed Android NDK directory." >&2
        exit 1
    fi
    export ANDROID_NDK_HOME
    export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
fi

mkdir -p "$(dirname "$UPSTREAM_DIR")" "$GENERATED"
if [[ ! -d "$UPSTREAM_DIR/.git" ]]; then
    git clone --filter=blob:none --no-checkout "$UPSTREAM_URL" "$UPSTREAM_DIR"
fi
git -C "$UPSTREAM_DIR" fetch --quiet --depth 1 origin "$UPSTREAM_REF"
git -C "$UPSTREAM_DIR" checkout --quiet --force --detach FETCH_HEAD
UPSTREAM_REVISION="$(git -C "$UPSTREAM_DIR" rev-parse HEAD)"
echo "Using JSON-RPC-2 revision $UPSTREAM_REVISION"
git -C "$UPSTREAM_DIR" apply --check "$LIVENESS_PATCH"
git -C "$UPSTREAM_DIR" apply "$LIVENESS_PATCH"

# The FFI crate disables tstrans-srt-sys default features. Enable its
# bundled mbedTLS feature in this disposable checkout so SRT passphrases
# remain encrypted in the mobile libraries.
python3 - "$UPSTREAM_DIR/json-rpc-2-datagram/Cargo.toml" <<'PY'
from pathlib import Path
import sys

manifest = Path(sys.argv[1])
text = manifest.read_text()
old = 'libsrt-sys = { package = "tstrans-srt-sys", version = "0.8", default-features = false, optional = true }'
new = 'libsrt-sys = { package = "tstrans-srt-sys", version = "0.8", default-features = false, features = ["mbedtls"], optional = true }'
if new not in text:
    if old not in text:
        raise SystemExit(f"Could not find expected SRT dependency in {manifest}; update this build script.")
    manifest.write_text(text.replace(old, new, 1))
PY

# Cargo builds Android/iOS targets with a sysroot root path. The upstream
# tstrans-srt-sys build script needs BOTH search modes to find its bundled
# mbedTLS prefix during cross compilation. Patch a disposable local copy rather
# than editing Cargo's registry cache.
cargo fetch --manifest-path "$UPSTREAM_DIR/Cargo.toml"
python3 - "$UPSTREAM_DIR" "${CARGO_HOME:-$HOME/.cargo}/registry/src" <<'PY'
from pathlib import Path
import shutil
import sys

workspace = Path(sys.argv[1])
registry = Path(sys.argv[2])
sources = sorted(registry.glob("*/tstrans-srt-sys-0.8.*"))
if not sources:
    raise SystemExit("Could not locate the fetched tstrans-srt-sys 0.8 source.")

overlay = workspace / "build/mobile-overlays/tstrans-srt-sys"
if overlay.exists():
    shutil.rmtree(overlay)
shutil.copytree(sources[-1], overlay)

build_script = overlay / "build.rs"
text = build_script.read_text()
helper = '''fn apply_android_prefix_search(cfg: &mut cmake::Config, target: &str) {
    if target.contains("android") {
        cfg.define("CMAKE_FIND_ROOT_PATH_MODE_LIBRARY", "BOTH")
            .define("CMAKE_FIND_ROOT_PATH_MODE_INCLUDE", "BOTH")
            .define("CMAKE_FIND_ROOT_PATH_MODE_PACKAGE", "BOTH");
    }
}

'''
if helper not in text:
    marker = "fn main() {"
    if marker not in text:
        raise SystemExit(f"Could not patch Android CMake search paths in {build_script}.")
    text = text.replace(marker, helper + marker, 1)
old_call = "    apply_apple_ios(&mut cfg, &target);\n\n    let dst = cfg.build();"
new_call = (
    "    apply_apple_ios(&mut cfg, &target);\n"
    "    apply_android_prefix_search(&mut cfg, &target);\n\n"
    "    let dst = cfg.build();"
)
if new_call not in text:
    if old_call not in text:
        raise SystemExit(f"Could not locate the vendored libsrt CMake config in {build_script}.")
    text = text.replace(old_call, new_call, 1)
bindgen_marker = '    let out_path = PathBuf::from(env::var("OUT_DIR").unwrap());'
bindgen_patch = '''    let target = env::var("TARGET").unwrap_or_default();
    let bindgen_target = match target.as_str() {
        "aarch64-linux-android" => Some("aarch64-linux-android21"),
        "armv7-linux-androideabi" => Some("armv7a-linux-androideabi21"),
        "i686-linux-android" => Some("i686-linux-android21"),
        "x86_64-linux-android" => Some("x86_64-linux-android21"),
        _ => None,
    };
    if let Some(target) = bindgen_target {
        builder = builder.clang_arg(format!("--target={target}"));
    }

'''
if bindgen_patch not in text:
    if bindgen_marker not in text:
        raise SystemExit(f"Could not add the Android bindgen target to {build_script}.")
    text = text.replace(bindgen_marker, bindgen_patch + bindgen_marker, 1)
build_script.write_text(text)

manifest = workspace / "Cargo.toml"
manifest_text = manifest.read_text()
patch = 'tstrans-srt-sys = { path = "build/mobile-overlays/tstrans-srt-sys" }'
if patch not in manifest_text:
    header = "[patch.crates-io]"
    if header in manifest_text:
        manifest_text = manifest_text.replace(header, header + "\n" + patch, 1)
    else:
        manifest_text = manifest_text.rstrip() + "\n\n" + header + "\n" + patch + "\n"
    manifest.write_text(manifest_text)
PY

cd "$UPSTREAM_DIR"
cargo build --quiet -p json-rpc-2-ffi

case "$(uname -s)" in
    Darwin) HOST_LIBRARY="$UPSTREAM_DIR/target/debug/libjson_rpc_2_ffi.dylib" ;;
    Linux) HOST_LIBRARY="$UPSTREAM_DIR/target/debug/libjson_rpc_2_ffi.so" ;;
    *) echo "Unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac
if [[ ! -f "$HOST_LIBRARY" ]]; then
    echo "Host UniFFI library was not produced: $HOST_LIBRARY" >&2
    exit 1
fi

KOTLIN_BINDINGS="$GENERATED/kotlin"
SWIFT_BINDINGS="$GENERATED/swift"
rm -rf "$KOTLIN_BINDINGS" "$SWIFT_BINDINGS"
cargo run --quiet -p json-rpc-2-ffi --features uniffi-bindgen --bin uniffi-bindgen -- \
    generate --library "$HOST_LIBRARY" --language kotlin --out-dir "$KOTLIN_BINDINGS" --no-format
cargo run --quiet -p json-rpc-2-ffi --features uniffi-bindgen --bin uniffi-bindgen -- \
    generate --library "$HOST_LIBRARY" --language swift --out-dir "$SWIFT_BINDINGS" --no-format

ANDROID_PACKAGE="$PROJECT_ROOT/src/androidMain/kotlin/uniffi/json_rpc_2_ffi"
mkdir -p "$ANDROID_PACKAGE"
cp "$KOTLIN_BINDINGS/uniffi/json_rpc_2_ffi/json_rpc_2_ffi.kt" "$ANDROID_PACKAGE/"

if [[ "$TARGET" == android || "$TARGET" == all ]]; then
    rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
    rm -rf "$ANDROID_OUT"
    IFS=',' read -r -a android_abis <<< "${ANDROID_ABIS:-arm64-v8a,armeabi-v7a,x86,x86_64}"
    for abi in "${android_abis[@]}"; do
        echo "Building Android ABI $abi"
        SRT_NO_PKG_CONFIG=1 cargo ndk -t "$abi" -o "$ANDROID_OUT" \
            build --release -p json-rpc-2-ffi
    done
fi

if [[ "$TARGET" == ios || "$TARGET" == all ]]; then
    export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-15.0}"
    rustup target add aarch64-apple-ios aarch64-apple-ios-sim
    rm -rf "$IOS_OUT"
    for triple in aarch64-apple-ios aarch64-apple-ios-sim; do
        echo "Building iOS target $triple"
        SRT_NO_PKG_CONFIG=1 cargo build --release -p json-rpc-2-ffi --target "$triple"
        if [[ "$triple" == aarch64-apple-ios ]]; then
            sdk=iphoneos
            slice=ios-arm64
            swift_target="arm64-apple-ios${IPHONEOS_DEPLOYMENT_TARGET}"
        else
            sdk=iphonesimulator
            slice=ios-arm64-simulator
            swift_target="arm64-apple-ios${IPHONEOS_DEPLOYMENT_TARGET}-simulator"
        fi
        sdk_path="$(xcrun --sdk "$sdk" --show-sdk-path)"
        slice_dir="$IOS_OUT/$slice"
        mkdir -p "$slice_dir"
        swift_archive="$slice_dir/libjsonrpc_bridge_swift.a"
        swiftc \
            -target "$swift_target" \
            -sdk "$sdk_path" \
            -swift-version 5 \
            -parse-as-library \
            -emit-library -static \
            -module-name JsonRpcBridge \
            -Xcc -fmodule-map-file="$SWIFT_BINDINGS/json_rpc_2_ffiFFI.modulemap" \
            -I "$SWIFT_BINDINGS" \
            -o "$swift_archive" \
            "$SWIFT_BINDINGS/json_rpc_2_ffi.swift" \
            "$PROJECT_ROOT/src/iosMain/swift/JsonRpcBridge.swift"
        xcrun libtool -static \
            -no_warning_for_no_symbols \
            -o "$slice_dir/libjsonrpc_bridge.a" \
            "$swift_archive" \
            "$UPSTREAM_DIR/target/$triple/release/libjson_rpc_2_ffi.a"
        rm "$swift_archive"
    done
fi

echo "Native client artifacts and generated UniFFI bindings are ready."
