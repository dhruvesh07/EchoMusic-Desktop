// Upstream :innertube — the entire YouTube Music InnerTube client.
// Declared an Android library upstream but contains zero android.* imports,
// so it compiles here as plain JVM Kotlin.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    implementation(libs.brotli)
    implementation(libs.newpipe.extractor) {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
    implementation(libs.pipepipe.extractor) {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
    implementation("com.github.TeamNewPipe:nanojson:c7a6c1c08d16b6d5ecded34758e6415e07be2166")
    implementation(project(":metadata"))
    testImplementation("junit:junit:4.13.2")
}
