plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.hamzabhai.jhatpat"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hamzabhai.jhatpat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = providers.gradleProperty("versionName").getOrElse("0.2.0-dev")
        // English only: drop the translations AndroidX libraries ship with.
        @Suppress("UnstableApiUsage")
        resourceConfigurations += "en"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // The Go server is shipped as libjhatpat.so and run from the native
            // library directory, so the libraries must be extracted on install.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
