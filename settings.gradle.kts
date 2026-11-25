pluginManagement {
    val kotlyshKotlinVersion = providers
        .gradleProperty("kotlyshKotlinVersion")
        .orElse(providers.environmentVariable("KOTLYSH_KOTLIN_VERSION"))
        .orElse("2.1.0")
    plugins {
        id("org.jetbrains.kotlin.jvm") version kotlyshKotlinVersion.get()
        id("org.jetbrains.kotlin.multiplatform") version kotlyshKotlinVersion.get()
        id("org.jetbrains.kotlin.plugin.serialization") version kotlyshKotlinVersion.get()
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kotlysh"

include(":kotlysh-core")
include(":kotlysh-native-sh")
include(":kotlysh-samples")
