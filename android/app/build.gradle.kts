plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Bundled credentials so users get one-tap "Log in with Spotify" and never
// have to enter a setlist.fm API key. Supplied via gradle property, env var
// (CI secrets), or left blank — the app then falls back to manual entry in
// Settings. PKCE needs no client secret, so shipping the client ID is safe.
// CI sets these env vars even when the backing secret is missing, so blank
// values must count as absent or they mask the built-in default.
fun credential(name: String, default: String = ""): String =
    (project.findProperty(name) as String?)?.takeUnless { it.isBlank() }
        ?: System.getenv(name)?.takeUnless { it.isBlank() }
        ?: default

// The "Station to Station" app registration. Its redirect list still includes
// setlist2spotify://callback, so the existing deep-link scheme keeps working.
val spotifyClientId = credential("SPOTIFY_CLIENT_ID", default = "4d0ca5e417a54b599b07bfac99671644")
val setlistFmApiKey = credential("SETLISTFM_API_KEY")

android {
    namespace = "io.github.magnusencoded.setlist2spotify"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.magnusencoded.setlist2spotify"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.1" + (System.getenv("GITHUB_RUN_NUMBER")?.let { ".$it" } ?: "")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SETLISTFM_API_KEY", "\"$setlistFmApiKey\"")
    }

    signingConfigs {
        // Committed debug key so every machine and CI build signs identically,
        // letting `adb install -r` update a device without wiping app data.
        // Debug keystores are not secret (the password is the well-known "android").
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Phone cameras record orientation in EXIF rather than rotating the pixels,
    // so gallery photos need it applied before they are shown or uploaded.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Encodes the friend card as a QR the other phone's camera can open — the
    // deep link is already registered, so no in-app scanner is needed.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
