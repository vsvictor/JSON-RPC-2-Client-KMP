import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess

plugins {
    kotlin("multiplatform") version "2.4.20"
    id("com.android.kotlin.multiplatform.library") version "9.1.1"
}

tasks.withType<CInteropProcess>().configureEach {
    inputs.file(project.file("src/iosMain/cinterop/jsonrpc_bridge.h"))
    inputs.file(project.file("src/iosMain/cinterop/jsonrpc_bridge.def"))
    inputs.dir(layout.buildDirectory.dir("native/ios")).optional()
}

abstract class PrepareAndroidCppRuntime : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeLibraries: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val abiByTriple = mapOf(
            "aarch64-linux-android" to "arm64-v8a",
            "arm-linux-androideabi" to "armeabi-v7a",
            "i686-linux-android" to "x86",
            "x86_64-linux-android" to "x86_64",
        )
        val libraries = runtimeLibraries.files
        val librariesByTriple = libraries.groupBy { it.parentFile.name }
        abiByTriple.forEach { (triple, abi) ->
            val library = librariesByTriple[triple]?.singleOrNull()
                ?: error("Expected one libc++_shared.so for $abi in the selected NDK. Set -PjsonRpcNdkDirectory to the NDK used to build the Rust FFI.")
            val destination = outputDirectory.file("$abi/libc++_shared.so").get().asFile
            destination.parentFile.mkdirs()
            library.copyTo(destination, overwrite = true)
        }
    }
}

extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
    val ndk = providers.gradleProperty("jsonRpcNdkDirectory")
        .orElse(providers.environmentVariable("ANDROID_NDK_HOME"))
        .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
        .map { File(it) }
        .orElse(sdkComponents.sdkDirectory.map { it.dir("ndk/30.0.15729638").asFile })
    val prepareCppRuntime = tasks.register<PrepareAndroidCppRuntime>("prepareAndroidCppRuntime") {
        runtimeLibraries.from(
            fileTree(ndk.map { it.resolve("toolchains/llvm/prebuilt") }) {
                include("*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
                include("*/sysroot/usr/lib/arm-linux-androideabi/libc++_shared.so")
                include("*/sysroot/usr/lib/i686-linux-android/libc++_shared.so")
                include("*/sysroot/usr/lib/x86_64-linux-android/libc++_shared.so")
            }
        )
        outputDirectory.set(layout.buildDirectory.dir("generated/androidCppRuntime"))
    }
    onVariants { variant ->
        checkNotNull(variant.sources.jniLibs).addGeneratedSourceDirectory(
            prepareCppRuntime,
            PrepareAndroidCppRuntime::outputDirectory,
        )
    }
}

kotlin {
    android {
        namespace = "com.vsvictor.jsonrpckmp"
        compileSdk = 37
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        withHostTest {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    val iosDevice = iosArm64()
    val iosSimulator = iosSimulatorArm64()
    listOf(iosDevice, iosSimulator).forEach { target ->
        target.binaries.framework {
            baseName = "JsonRPCClientKMP"
            isStatic = true
            val slice = if (target.name == "iosArm64") "ios-arm64" else "ios-arm64-simulator"
            linkerOpts(
                "-L${layout.buildDirectory.get()}/native/ios/$slice",
                "-ljsonrpc_bridge",
                "-framework", "Foundation",
                "-framework", "Security",
                "-framework", "Network",
            )
        }
        // Test executables are linked by Kotlin/Native (not Xcode), so the Swift runtime that the
        // native bridge depends on must be added explicitly.
        target.binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
            val sdk = if (target.name == "iosArm64") "iphoneos" else "iphonesimulator"
            val developerDir = providers.exec { commandLine("xcode-select", "-p") }
                .standardOutput.asText.get().trim()
            val sdkPath = providers.exec { commandLine("xcrun", "--sdk", sdk, "--show-sdk-path") }
                .standardOutput.asText.get().trim()
            linkerOpts(
                "-L$developerDir/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$sdk",
                "-L$sdkPath/usr/lib/swift",
                "-rpath", "/usr/lib/swift",
            )
        }
        target.compilations.getByName("main").cinterops.create("jsonrpcBridge") {
            defFile(project.file("src/iosMain/cinterop/jsonrpc_bridge.def"))
            compilerOpts("-I${project.projectDir}/src/iosMain/cinterop")
            val slice = if (target.name == "iosArm64") "ios-arm64" else "ios-arm64-simulator"
            // Bundles the native bridge into the klib so consumers' static frameworks link it too.
            extraOpts("-libraryPath", "${layout.buildDirectory.get()}/native/ios/$slice")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidMain.dependencies {
            implementation("net.java.dev.jna:jna:5.17.0@aar")
        }
        getByName("androidDeviceTest").dependencies {
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.3.0")
        }
    }
}
