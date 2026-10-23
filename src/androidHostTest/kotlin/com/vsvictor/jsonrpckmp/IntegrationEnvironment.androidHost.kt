package com.vsvictor.jsonrpckmp

// The host JVM cannot load the Android native library, so integration tests are skipped here.
internal actual fun integrationServerHost(): String? = null
