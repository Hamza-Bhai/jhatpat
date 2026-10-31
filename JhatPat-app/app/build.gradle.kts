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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

// ---- The JhatPat server (Go, ../web) packaged as lib/<abi>/libjhatpat.so ----
// Android refuses to run programs from an app's data folder, but allows the native library
// folder, so the server binary ships as a "native library" and is started as a child process.
// No NDK is needed: the server is pure Go (CGO_ENABLED=0).
// Go can build android/arm64 without cgo, but android/arm and android/amd64 need the NDK, so
// those two ABIs get a static linux binary instead (Android runs on the Linux kernel and a
// static, pure-Go binary does not need Android's libc).
class GoTarget(val goos: String, val goarch: String, val goarm: String = "")

val goTargets = mapOf(
    "arm64-v8a" to GoTarget("android", "arm64"),
    "armeabi-v7a" to GoTarget("linux", "arm", goarm = "7"),
    "x86_64" to GoTarget("linux", "amd64"),
)

val buildGoServer by tasks.registering {
    val webDir = rootProject.projectDir.resolve("../web").canonicalFile
    val jniLibs = layout.projectDirectory.dir("src/main/jniLibs").asFile
    val version = providers.gradleProperty("versionName").getOrElse("0.2.0-dev")
    inputs.files(fileTree(webDir) {
        include("**/*.go", "go.mod", "go.sum", "web/static/**", "web/*.html")
        exclude("dist/**")
    })
    inputs.property("version", version)
    outputs.dir(jniLibs)
    doLast {
        goTargets.forEach { (abi, target) ->
            val out = File(jniLibs, "$abi/libjhatpat.so")
            out.parentFile.mkdirs()
            try {
                exec {
                    workingDir = webDir
                    environment("GOOS", target.goos)
                    environment("GOARCH", target.goarch)
                    if (target.goarm.isNotEmpty()) environment("GOARM", target.goarm)
                    environment("CGO_ENABLED", "0")
                    commandLine("go", "build", "-trimpath", "-ldflags", "-s -w -X main.version=$version", "-o", out.absolutePath, "./cmd/jhatpat")
                }
            } catch (e: Exception) {
                throw GradleException(
                    "Could not build the JhatPat server for $abi. Go 1.24 or newer must be installed and on PATH " +
                        "(https://go.dev/dl/). Details: ${e.message}",
                    e,
                )
            }
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildGoServer) }
