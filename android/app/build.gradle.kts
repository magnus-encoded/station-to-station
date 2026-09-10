// Imported rather than written as `java.util.Properties`: inside the `android`
// block `java` resolves to Gradle's own Java extension, not the package.
import java.util.Properties

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
    namespace = "io.github.magnusencoded.stationtostation"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.magnusencoded.stationtostation"
        minSdk = 26
        targetSdk = 36
        // Play requires versionCode to be strictly increasing and never accepts the
        // same one twice, so on a release build it comes from the commit count —
        // an int that only climbs, resets never, and can be recovered from any
        // checkout with `git rev-list --count`. android-release.yml passes it in.
        //
        // Explicitly NOT the CI run number, which was the obvious candidate: it is
        // scoped per workflow file, so a new or renamed release workflow restarts
        // at 1 and every upload is refused until the count catches back up. It also
        // holds steady across a re-run, which is exactly when you need a new code.
        //
        // Absent the env var — any local or debug build — this falls back to
        // gradle.properties, so a developer build still has a sensible number.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
            ?: (property("appVersionCode") as String).toInt()
        versionName = property("appVersionName") as String
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

        // The Play upload key. Not the app signing key — Play holds that one and
        // re-signs every bundle we send, so this key only proves the upload is
        // ours. Losing it is recoverable (Play can reset an upload key); losing
        // the app signing key would not be, which is why we let Play keep it.
        //
        // Credentials come from a gitignored keystore.properties, or from env
        // vars for CI. Absent on a machine that has neither, the config is not
        // created at all and `assembleRelease` produces an unsigned build rather
        // than failing — a contributor without the key can still compile release.
        val keystoreProps = rootProject.file("keystore.properties")
        val storePath = if (keystoreProps.exists()) {
            Properties().apply { keystoreProps.inputStream().use(::load) }
        } else null
        val storeFileName = storePath?.getProperty("storeFile") ?: System.getenv("UPLOAD_STORE_FILE")
        // Having the file but not being able to read a key out of it means the
        // file is malformed, not that we are a contributor without the key — and
        // the difference is invisible later: an unsigned bundle builds green and
        // is only rejected at the Play Console. A UTF-8 BOM did exactly this once
        // (Java reads it as part of the first key's name).
        check(storePath == null || storeFileName != null) {
            "keystore.properties exists but has no readable storeFile — malformed? (a UTF-8 BOM will do it)"
        }
        if (storeFileName != null && rootProject.file(storeFileName).exists()) {
            create("release") {
                storeFile = rootProject.file(storeFileName)
                storePassword = storePath?.getProperty("storePassword")
                    ?: System.getenv("UPLOAD_STORE_PASSWORD")
                keyAlias = storePath?.getProperty("keyAlias") ?: System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = storePath?.getProperty("keyPassword")
                    ?: System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // The debug APK is the only artifact the loop produces, and it is ~100x
        // janker than the same code non-debuggable — measured, see
        // docs/measuring-on-device.md. Never read a performance number off it.
        // Flipping `isDebuggable` here also switches off BuildConfig.DEBUG, and
        // with it the Woven geometry dump: AGP derives DEBUG from the flag, not
        // from the build type's name.
        // The run number tells two debug APKs of the same release apart on a
        // device. It lives here rather than in defaultConfig because it must not
        // reach a shipped bundle: GITHUB_RUN_NUMBER is set in every workflow, so
        // from defaultConfig it would follow the release build to Play and testers
        // would see "1.2.7" in the listing. The tag is the release's provenance.
        debug {
            versionNameSuffix = System.getenv("GITHUB_RUN_NUMBER")?.let { ".$it" }
            // A distinct package id so a debug install coexists with a release/alpha-track
            // install on the same phone instead of colliding with it — Android refuses to
            // overwrite a higher versionCode, and Play's version code always outpaces the
            // CI debug build's. Context.packageName picks this up everywhere already
            // (FileProvider authority, etc.), so nothing else needs to know.
            applicationIdSuffix = ".debug"
        }

        // The debug loop's artifact with exactly one flag moved: the only build a
        // performance number may be read off (docs/measuring-on-device.md, which asks
        // for precisely this second artifact when performance stops being an
        // impression and becomes a question).
        //
        // Deliberately not `release`: that one carries the upload key and a versionCode
        // meant for Play, so comparing against it would move three things at once. This
        // keeps the debug package id and the committed debug key, so it installs
        // straight over a debug build without wiping data — and `-measure` in the
        // version on screen is how you tell which of the two is on the phone.
        //
        // Note what moving the flag costs, per the same doc: BuildConfig.DEBUG follows
        // isDebuggable and not the build type's name, so the Woven geometry dump is off
        // here. That is the price of a real number, not a bug to chase.
        create("measure") {
            initWith(getByName("debug"))
            isDebuggable = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "${System.getenv("GITHUB_RUN_NUMBER")?.let { ".$it" } ?: ""}-measure"
            // A build type other than `debug` is unsigned unless told otherwise, and an
            // unsigned APK does not install.
            signingConfig = signingConfigs.getByName("debug")
        }

        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    // One APK per ABI instead of one carrying every ABI (#437). The universal APK
    // shipped for v1.8.0 was 58,971,446 bytes, and 30,012,200 of that was native
    // code for three architectures the installing device cannot execute — it
    // downloads them and throws them away. Almost all of it is ML Kit's OCR
    // pipeline, which is ~11 MB per ABI.
    //
    // Native libraries are the only part of the artifact that pays full freight:
    // AGP 8 packages them `Stored` rather than `Deflated` (extractNativeLibs=false),
    // so the APK doubles as the on-disk copy and nothing is unpacked at install.
    // Good for install size, but it means these bytes do not compress on the wire
    // the way dex does — 54 MB of dex is 15 MB in the file, 41 MB of .so is 41 MB.
    //
    // Deliberately NOT `defaultConfig.ndk.abiFilters`, which is the more obvious
    // spelling: that drops the ABIs from every output including `bundleRelease`,
    // permanently narrowing who Play can serve. It would also be pointless, because
    // Play already generates per-device splits from the .aab — a store install never
    // paid for the extra ABIs. The 56 MiB was only ever the .apk on the Releases
    // page, which is the artifact someone installs from a link handed to them at a
    // gig, and the one #437 is about. The `splits` block is ignored for bundles, so
    // the .aab stays universal and the Play path is untouched.
    //
    // arm64-v8a alone, on every variant rather than only in CI. armeabi-v7a and x86
    // are 32-bit, minSdk is 26, and Play has required 64-bit since August 2019, so no
    // device that can install this app needs either.
    //
    // x86_64 was included in an earlier pass for the local emulator and then dropped,
    // because it turns out to cost nothing. The android-36 google_apis x86_64 system
    // image declares:
    //
    //     ro.system.product.cpu.abilist=x86_64,arm64-v8a
    //     ro.dalvik.vm.native.bridge=libndk_translation.so
    //
    // so an x86_64 AVD advertises arm64-v8a through ARM binary translation and
    // installs an arm64-only APK. That has held on x86_64 images since API 30. A
    // second artifact no phone should ever download is not worth carrying to serve
    // a case the emulator already handles.
    //
    // isUniversalApk stays false. Emitting the fat APK alongside these would keep
    // producing exactly the artifact this removes, and leave two files on the
    // Releases page where the larger one is never the right download.
    splits {
        abi {
            isEnable = true
            // Without reset() these are added to AGP's default "every ABI" list
            // rather than replacing it, which quietly builds all four again.
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

kotlin {
    // Use the JVM toolchain and the compilerOptions DSL instead of kotlinOptions.jvmTarget
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    // @Preview and the renderer behind it. The annotation ships in the main artifact
    // so it compiles in release; the tooling that draws it is debug-only, because it
    // pulls a chunk of the Studio renderer and must not reach a shipped APK.
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Phone cameras record orientation in EXIF rather than rotating the pixels,
    // so gallery photos need it applied before they are shown or uploaded.
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Encodes the friend card as a QR the other phone's camera can open — the
    // deep link is already registered, so no in-app scanner is needed. #411 is its
    // first *decoding* use, reading a ticket's QR back off a rasterized PDF page.
    implementation("com.google.zxing:core:3.5.4")
    // Best-effort OCR of the same rasterized ticket page (#411). On-device (no
    // network round trip, no server to fail best-effort enrichment's own rule),
    // and the "bundled" Latin model keeps the first run from needing a play-services
    // module download before it can read anything.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Android-to-Android discovery and the card swap. Raw GATT is still coming for
    // iOS interop (#13/#18) — Nearby is the Android-only fast path, not a
    // replacement for it.
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    testImplementation("junit:junit:4.13.2")
}
