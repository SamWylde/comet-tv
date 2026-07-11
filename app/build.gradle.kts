import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load local signing config if present (keystore.properties is gitignored).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.tdarby.comet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tdarby.comet"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "1.0.7"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Per-ABI APKs plus a universal APK. armeabi-v7a covers 32-bit Android TV (e.g. Chromecast with
    // Google TV HD); arm64-v8a for most TV boxes; x86_64 for the emulator. The universal APK installs
    // on any ABI (avoids "app not compatible" on sideload).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        // The newest androidx releases require AGP 9.1+ (we're on 8.9); upgrading dependencies is
        // gated on that major AGP bump, so don't flag the available-but-incompatible versions.
        disable += "GradleDependency"
        // The adaptive icon must stay in mipmap-anydpi-v26 for AAPT (moving it to mipmap-anydpi
        // breaks resource linking), so the "v26 is unnecessary" hint isn't actionable.
        disable += "ObsoleteSdkInt"
        warningsAsErrors = false
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// After a release build, also emit the universal APK under the legacy filename the permanent
// Downloader code (2453488) and the update manifest point at, so releases never break if a manual
// copy is forgotten. A registered Copy task (vs. a doLast closure) keeps the configuration cache happy.
// Output to a separate dir (not AGP's apk/release) to avoid overlapping-output task conflicts.
val copyLegacyUniversalApk = tasks.register<Copy>("copyLegacyUniversalApk") {
    dependsOn("packageRelease") // declare the producer of app-universal-release.apk
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("app-universal-release.apk") }
    into(layout.buildDirectory.dir("outputs/apk-legacy"))
    rename("app-universal-release.apk", "app-webview-universal-release.apk")
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyLegacyUniversalApk) }

dependencies {
    // Pinned to the newest versions compatible with the current Android Gradle plugin (8.9). The
    // absolute-latest androidx releases require AGP 9.1+; see the `lint { disable GradleDependency }`
    // note below — bumping these is gated on an AGP major upgrade.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
