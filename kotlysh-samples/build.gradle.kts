plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

val kotlyshJvmToolchain = rootProject.extra["kotlyshJvmToolchain"] as Int

dependencies {
    implementation(project(":kotlysh-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(kotlyshJvmToolchain)
}

application {
    mainClass.set("com.kotlysh.samples.PolyglotDemoKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runHotspots") {
    group = "application"
    description = "Rank a git repository's files by size and churn: the README's closing tour example."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.kotlysh.samples.HotspotsKt")
    // Surveys this repository unless -Prepo=/path/to/another names a different one.
    workingDir = file(providers.gradleProperty("repo").orNull ?: rootProject.projectDir)
}

tasks.register<JavaExec>("runOrchestra") {
    group = "application"
    description = "Survey a repository across parallel Shell, Python and Node sessions, charted from the run's own trace."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.kotlysh.samples.OrchestraKt")
}
