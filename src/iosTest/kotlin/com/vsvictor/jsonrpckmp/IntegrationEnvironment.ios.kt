package com.vsvictor.jsonrpckmp

import platform.Foundation.NSProcessInfo

// The iOS simulator shares the Mac network stack. Override with SIMCTL_CHILD_JSONRPC_TEST_HOST.
internal actual fun integrationServerHost(): String? =
    NSProcessInfo.processInfo.environment["JSONRPC_TEST_HOST"] as? String ?: "127.0.0.1"
