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
    // GeckoView (full flavor) pulls androidx.core 1.18, which requires compiling against API 36.
    compileSdk = 36
    // Enables stripping of bundled native libs (GeckoView's libxul.so etc.) in release builds.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.tdarby.comet"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    // Two engine builds: `webview` (slim, System WebView only) and `full` (also bundles
    // GeckoView). Both share one codebase; engine-specific code lives in per-flavor source sets.
    flavorDimensions += "engine"
    productFlavors {
        create("webview") {
            dimension = "engine"
            versionNameSuffix = "-webview"
            buildConfigField("String", "FLAVOR_LABEL", "\"webview\"")
        }
        create("full") {
            dimension = "engine"
            versionNameSuffix = "-full"
            buildConfigField("String", "FLAVOR_LABEL", "\"full\"")
            // ABI selection is handled by the `splits` block below (arm64-v8a + x86_64), which keeps
            // the GeckoView-bundled native libs to a sane size per APK.
        }
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

    // Per-ABI APKs keep download size down (esp. the GeckoView-bundled `full` flavor).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // GeckoView is only bundled into the `full` flavor (keeps the `webview` APK slim).
    "fullImplementation"("org.mozilla.geckoview:geckoview:151.0.20260608154138")

    testImplementation("junit:junit:4.13.2")
}
