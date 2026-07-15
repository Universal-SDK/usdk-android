plugins {
    id("com.android.library")
}

android {
    namespace = "org.bidon.bidon"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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
}

dependencies {
    implementation(project(":bidon-rendering"))
    //api("org.bidon:bidon-sdk:0.4.29")
    implementation("org.bidon:bidon-sdk:0.4.29")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}