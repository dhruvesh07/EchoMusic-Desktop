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

// Portable, installer-free distribution: gradlew :desktop:packagePortableZip
tasks.register<Zip>("packagePortableZip") {
    group = "compose desktop"
    description = "Zips the self-contained app image for users who do not want an installer."
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    archiveFileName.set("Echo-Music-Windows-x64-v${project.version}-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
}

compose.desktop {
    application {
        mainClass = "echo.music.desktop.MainKt"
        nativeDistributions {
            // MSI only. The EXE target produced a self-extracting WiX "msiwrapper"
            // stub (small PE + ~90 MB embedded MSI), which is a known heuristic and
            // ML false-positive trigger. An MSI is installed by the trusted Windows
            // Installer service and has no such wrapper.
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
            )
            packageName = "Echo Music"
            packageVersion = project.version.toString()
            description = "Echo Music — ad-free YouTube Music client"
            vendor = "Echo Music"
            copyright = "Copyright © 2026 Echo Music"
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                // Keep the machine-wide install scope used by v1.4.4. Windows
                // Installer refuses cross-scope major upgrades, so a per-user
                // package would install side-by-side with the old version
                // instead of replacing it.
                perUserInstall = false
                dirChooser = true
                shortcut = true
                menu = true
                menuGroup = "Echo Music"
                upgradeUuid = "8f3a1c2e-4b7d-4a9f-9c6e-1d2f3a4b5c6d"
            }
        }
    }
}
