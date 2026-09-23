import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is opt-in: drop a keystore.properties in the project root (it is
// gitignored) and the release variant gets signed. Without it the release build still
// succeeds, just unsigned. See keystore.properties.example for the expected keys.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.example.customtvscreensaver"
    compileSdk = 36

    defaultConfig {
        // Same listing as the phone and watch apps, so it must match theirs exactly. The
        // namespace (Kotlin package, R class) is independent and stays as it is.
        applicationId = "com.mbf.jordan_prayer_times_app"
        minSdk = 26
        // Google Play requires API 36 for new releases.
        targetSdk = 36
        // Shares a Play listing with the phone and Wear apps: every upload needs a code Play has not seen.
        versionCode = 27
        versionName = "3.0.0"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Mirrors the phone app, so a debug build installs beside the released app.
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            // Same names as the phone app, so the two builds are told apart on the launcher.
            resValue("string", "app_name", "Jordan Prayer Times (Debug)")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
            resValue("string", "app_name", "Jordan Prayer Times")
        }
    }

    // The in-app language switch needs every language in every install, so the Play bundle must
    // not split by language (lint AppBundleLocaleChanges).
    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf(
            // Advisory only: flags newer AndroidX releases, which are not needed.
            "GradleDependency",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.leanback:leanback:1.0.0")
    // Launch splash, as the Wear OS app does (installSplashScreen in PrayerTimesActivity).
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Already on the classpath transitively via Coil; declared because we use it directly.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
