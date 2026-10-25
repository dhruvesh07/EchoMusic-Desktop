plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
}

allprojects {
    group = "fun.echomusic.desktop"
    version = "1.4.4"
}
