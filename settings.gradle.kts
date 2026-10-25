// Echo Music for Windows Desktop - Compose Multiplatform (JVM) port.
// Shared modules source their Kotlin straight from the upstream clone so this
// port stays diff-able against https://github.com/EchoMusicApp/Echo-Music
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { setUrl("https://maven.google.com/") }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { setUrl("https://maven.google.com/") }
        maven { setUrl("https://jitpack.io") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "echomusic-desktop"

include(":metadata")
include(":domain")
include(":innertube")
include(":lrclib")
include(":kugou")
include(":betterlyrics")
include(":simpmusic")
include(":youlyplus")
include(":unison")
include(":desktop")
