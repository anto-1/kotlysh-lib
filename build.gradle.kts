import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    kotlin("jvm") apply false
    kotlin("multiplatform") apply false
    kotlin("plugin.serialization") apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.1" apply false
}

val kotlyshKotlinVersion = providers
    .gradleProperty("kotlyshKotlinVersion")
    .orElse(providers.environmentVariable("KOTLYSH_KOTLIN_VERSION"))
    .orElse("2.1.0")
    .get()
val kotlyshJvmToolchain = providers
    .gradleProperty("kotlyshJvmToolchain")
    .orElse(providers.environmentVariable("KOTLYSH_JVM_TOOLCHAIN"))
    .orElse("21")
    .get()
    .toIntOrNull()
    ?: error("kotlyshJvmToolchain must be an integer")
require(kotlyshJvmToolchain > 0) { "kotlyshJvmToolchain must be positive" }
extra["kotlyshKotlinVersion"] = kotlyshKotlinVersion
extra["kotlyshJvmToolchain"] = kotlyshJvmToolchain

val androidNdkRoot = providers.gradleProperty("androidNdkRoot")
    .orElse(providers.environmentVariable("ANDROID_NDK_HOME"))
    .orElse(providers.environmentVariable("ANDROID_HOME").map { "$it/ndk/27.0.12077973" })
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT").map { "$it/ndk/27.0.12077973" })
    .orElse("${System.getProperty("user.home")}/Android/Sdk/ndk/27.0.12077973")
extra["kotlyshAndroidNdkRoot"] = androidNdkRoot

subprojects {
    group = "com.kotlysh"
    version = "0.1.0"

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            targets.withType<KotlinNativeTarget>().configureEach {
                if (konanTarget.family == Family.ANDROID) {
                    val architecture = when (konanTarget) {
                        KonanTarget.ANDROID_ARM64 -> "aarch64"
                        KonanTarget.ANDROID_X64 -> "x86_64"
                        else -> null
                    }
                    val builtins = architecture?.let { name -> androidNdkRoot.map {
                        "$it/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18/lib/linux/libclang_rt.builtins-$name-android.a"
                    } }
                    // Align both load segments and the RELRO boundary for 16 KiB devices.
                    binaries.configureEach {
                        linkerOpts("-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384", "-Wl,--no-undefined")
                        if (builtins != null) {
                            linkerOpts(builtins.get())
                            linkTaskProvider.configure {
                                inputs.file(builtins)
                                doFirst {
                                    require(file(builtins.get()).isFile) {
                                        "Install Android NDK 27 or set -PandroidNdkRoot / ANDROID_NDK_HOME to its directory: ${builtins.get()}"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
