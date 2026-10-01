plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    // Pins the public API in api/bidon-rendering.api: a change to it fails apiCheck until the file is regenerated.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3"
}

android {
    namespace = "org.bidon.rendering"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("io.coil-kt:coil:2.7.0")

    testImplementation(libs.junit)
    // org.json in the Android jar is a stub on the JVM; tests need a real implementation.
    testImplementation("org.json:json:20240303")
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
}