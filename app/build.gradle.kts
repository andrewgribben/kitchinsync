plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.andrewgribben.synccloud"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.andrewgribben.synccloud"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.dav4jvm) {
        exclude(group = "org.ogce", module = "xpp3") // Android ships its own XmlPullParser
    }
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // JVM unit tests have no platform XmlPullParser, so they get dav4jvm's xpp3 back
    testImplementation(libs.dav4jvm)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
