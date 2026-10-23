plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    // Pins the public API in api/bidon-rendering.api: a change to it fails apiCheck until the file is regenerated.
    alias(libs.plugins.binary.compatibility.validator)
}

android {
    namespace = "org.bidon.rendering"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // The test app runs as a current host app does. Left unset it takes minSdk, and Android then puts a warning
    // dialog over the test app's screens that takes their focus and the first Back.
    testOptions {
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        // Interface methods with a body become JVM default methods, so a callback added to AdRenderer.Listener later,
        // with a body, does not break a host compiled against an earlier version.
        freeCompilerArgs += "-Xjvm-default=all"
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
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}