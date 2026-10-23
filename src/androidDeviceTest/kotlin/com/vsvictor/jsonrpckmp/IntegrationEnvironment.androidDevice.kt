package com.vsvictor.jsonrpckmp

import androidx.test.platform.app.InstrumentationRegistry

// Override with: -Pandroid.testInstrumentationRunnerArguments.jsonrpcHost=<ip>
internal actual fun integrationServerHost(): String? =
    InstrumentationRegistry.getArguments().getString("jsonrpcHost") ?: "10.1.0.196"
