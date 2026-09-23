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

// Read-only GitHub token for the private Jordan prayer-times data repo. Kept out of the repo
// (secrets.properties is gitignored, see secrets.properties.example). When it is absent the app
// still builds and simply falls back to the adhan calculation everywhere.
val secretsFile = rootProject.file("secrets.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
}
val jordanApiToken = secrets.getProperty("jordanApiToken").orEmpty().trim()

android {
    namespace = "com.example.customtvscreensaver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.customtvscreensaver"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField(
            "String",
            "JORDAN_API_TOKEN",
            "\"" + jordanApiToken.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        )
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
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
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
            // Advisory only, and both would force a compileSdk bump to satisfy:
            // OldTargetApi wants targetSdk > 34, which is this project's stated target,
            // and GradleDependency flags newer AndroidX releases that require compileSdk 35+.
            "OldTargetApi",
            "GradleDependency",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.batoulapps.adhan:adhan:1.2.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Already on the classpath transitively via Coil; declared because we use it directly.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
