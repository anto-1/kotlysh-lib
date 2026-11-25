plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
    id("org.jetbrains.kotlinx.kover")
}

val kotlyshJvmToolchain = rootProject.extra["kotlyshJvmToolchain"] as Int

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(kotlyshJvmToolchain)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "kotlysh-core"
            from(components["java"])
            pom {
                name.set("Kotlysh core")
                description.set("The universal polyglot DSL, powered by coroutines. Kotlin as the control plane, for every language.")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// Measure the core JVM suite without excluding runtime classes or branches.
// Native drivers and Kotlin/Native targets need their own execution evidence.
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}
