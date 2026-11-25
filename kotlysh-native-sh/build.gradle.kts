import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    kotlin("multiplatform")
}

@Suppress("UNCHECKED_CAST")
val androidNdkRoot = rootProject.extra["kotlyshAndroidNdkRoot"] as Provider<String>

kotlin {
    linuxX64("native") {
        compilations.getByName("main").cinterops.create("nativeProcess") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/nativeProcess.def"))
            includeDirs("src/nativeInterop/cinterop")
        }
    }

    val androidTargets = mutableListOf(androidNativeArm64("androidNativeArm64"))
    if (providers.gradleProperty("nativeAndroidDeviceChecks").isPresent) {
        androidTargets += androidNativeX64("androidNativeX64")
    }
    androidTargets.forEach { target ->
        target.compilations.getByName("main").cinterops.create("nativeProcess") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/nativeProcess.def"))
            includeDirs("src/nativeInterop/cinterop")
        }
        target.binaries {
            sharedLib()
            test(listOf(NativeBuildType.RELEASE))
        }
        listOf(NativeBuildType.DEBUG, NativeBuildType.RELEASE).forEach { mode ->
            val modeName = mode.name.lowercase()
            val taskMode = modeName.replaceFirstChar { it.uppercaseChar() }
            val taskTarget = target.name.replaceFirstChar { it.uppercaseChar() }
            val architecture = if (target.name == "androidNativeArm64") "aarch64" else "x86_64"
            val compiler = androidNdkRoot.map {
                "$it/toolchains/llvm/prebuilt/linux-x86_64/bin/$architecture-linux-android21-clang"
            }
            val shared = target.binaries.getSharedLib(mode)
            val headerDirectory = layout.buildDirectory.dir("bin/${target.name}/${modeName}Shared")
            val client = layout.buildDirectory.file("bin/${target.name}/${modeName}SharedApiCheck/native-shared-api-check")
            tasks.register<Exec>("compile${taskMode}SharedApiCheck$taskTarget") {
                dependsOn(shared.linkTaskProvider)
                inputs.file("src/androidTest/c/NativeSharedApiCheck.c")
                inputs.file(headerDirectory.map { it.file("libkotlysh_native_sh_api.h") })
                inputs.file(compiler)
                outputs.file(client)
                doFirst {
                    val output = client.get().asFile
                    output.parentFile.mkdirs()
                    commandLine(compiler.get(), "-std=c11", "-Wall", "-Wextra", "-Werror",
                        "-isystem", headerDirectory.get().asFile.absolutePath,
                        file("src/androidTest/c/NativeSharedApiCheck.c").absolutePath,
                        "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
                        "-Wl,--no-undefined", "-ldl", "-o", output.absolutePath)
                }
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }

        nativeTest.dependencies {
            implementation(kotlin("test"))
        }

        androidTargets.forEach { target ->
            getByName("${target.name}Main") {
                kotlin.srcDir("src/nativeMain/kotlin")
                dependencies {
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                }
            }
            getByName("${target.name}Test") {
                kotlin.srcDir("src/androidTest/kotlin")
                dependencies { implementation(kotlin("test")) }
            }
        }
    }
}
