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

// The "Station to Station" app registration. The app now redirects to
// station-to-station://callback; the old setlist2spotify://callback is still on
// the registration's redirect list, so links shared before the rename keep working.
val spotifyClientId = credential("SPOTIFY_CLIENT_ID", default = "4d0ca5e417a54b599b07bfac99671644")
val setlistFmApiKey = credential("SETLISTFM_API_KEY")

// Which commit is on the phone. Builds are made in CI and installed over Wi-Fi, so
// "is this the build I just pushed?" was being answered by hashing APKs — and once
// by a truncated push that silently left the old one running.
val gitSha: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
        .standardOutput.asText.get().trim()
}.getOrDefault("").ifBlank { "nogit" }

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
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
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
        // The debug APK is the only artifact the loop produces, and it is ~100x
        // janker than the same code non-debuggable — measured, see
        // docs/measuring-on-device.md. Never read a performance number off it.
        // Flipping `isDebuggable` here also switches off BuildConfig.DEBUG, and
        // with it the Woven geometry dump: AGP derives DEBUG from the flag, not
        // from the build type's name.
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
    // Android-to-Android discovery and the card swap. Raw GATT is still coming for
    // iOS interop (#13/#18) — Nearby is the Android-only fast path, not a
    // replacement for it.
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    testImplementation("junit:junit:4.13.2")
}
