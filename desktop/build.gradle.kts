plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Upstream business logic reused verbatim.
    implementation(project(":metadata"))
    implementation(project(":domain"))
    implementation(project(":innertube"))
    implementation(project(":lrclib"))
    implementation(project(":kugou"))
    implementation(project(":betterlyrics"))
    implementation(project(":simpmusic"))
    implementation(project(":youlyplus"))
    implementation(project(":unison"))

    // Desktop UI
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3.desktop)
    implementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.material.kolor)
}

// Headless playback verification: gradlew :desktop:smokeTest
tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Resolves a YouTube stream and decodes 3 s of audio through ffmpeg."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "echo.music.desktop.tools.PlaybackSmokeTestKt"
}

// Seek-latency isolation (ffmpeg trace + range probes): gradlew :desktop:seekDiag
tasks.register<JavaExec>("seekDiag") {
    group = "verification"
    description = "Isolates where seek latency goes: ffmpeg -ss, HTTP ranges, engine path."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "echo.music.desktop.tools.SeekDiagKt"
}

// Automated screenshot generator: gradlew :desktop:captureScreenshots
tasks.register<JavaExec>("captureScreenshots") {
    group = "verification"
    description = "Captures high-resolution desktop screenshots of Echo Music UI."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "echo.music.desktop.tools.CaptureScreenshotsKt"
}

compose.desktop {
    application {
        mainClass = "echo.music.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
            )
            packageName = "Echo Music"
            packageVersion = "1.4.4"
            description = "Echo Music — ad-free YouTube Music client"
            vendor = "Echo Music"
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "Echo Music"
                upgradeUuid = "8f3a1c2e-4b7d-4a9f-9c6e-1d2f3a4b5c6d"
            }
        }
    }
}
