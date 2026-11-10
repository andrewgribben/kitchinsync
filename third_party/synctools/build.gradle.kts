// Vendored from davx5-ose synctools (GPL-3.0-or-later). See UPSTREAM.md.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "at.bitfire.synctools"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        aarMetadata {
            minCompileSdk = 29
        }
        buildConfigField("String", "version_ical4j", "\"${libs.versions.ical4j.get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
        }
    }

    lint {
        disable += listOf("AllowBackup", "InvalidPackage")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    coreLibraryDesugaring(libs.android.desugaring)

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.guava)
    implementation(libs.kotlinx.coroutines)

    // iCalendar / vCard parsing and generation
    api(libs.ical4j)
    implementation(libs.slf4j.jdk) // ical4j uses slf4j; synctools itself uses java.util.logging
    api(libs.ezvcard)

    // pin transitive versions down to what minSdk 26 actually needs (matches upstream catalog)
    implementation(libs.commons.codec)
    implementation(libs.commons.lang)

    compileOnly(libs.spotbugs.annotations)

    testImplementation(libs.junit)
}
