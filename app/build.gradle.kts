plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.jupiterns.drivetime"
    compileSdk = 36

    // A fixed signing key, committed (app/signing/) so every build — CI and local —
    // shares one signature. Android only installs an update *in place* (keeping app
    // data/settings) when the new APK is signed with the SAME key as the installed
    // one; the old setup let CI mint a throwaway debug key per run, so each build was
    // a different signature → "update not installed" → reinstall → settings wiped.
    // Debug-grade key, PKCS12 (openssl -legacy); password is intentionally trivial.
    signingConfigs {
        create("stable") {
            storeFile = file("signing/drivetime-signing.p12")
            storePassword = "drivetime"
            keyAlias = "drivetime"
            keyPassword = "drivetime"
            storeType = "PKCS12"
        }
    }

    defaultConfig {
        applicationId = "org.jupiterns.drivetime"
        minSdk = 26
        // Play's floor: 34 is already too low to upload. 35 is accepted today; API 36
        // becomes mandatory for new apps AND updates on 2026-08-31.
        //
        // Deliberately 35 and not 36 yet: targeting 35 opts into ENFORCED edge-to-edge but
        // still honours `windowOptOutEdgeToEdgeEnforcement` (themes.xml), which we use.
        // At targetSdk 36 that escape hatch is ignored and the WebView would draw under
        // the status bar — a layout change nobody can verify without a device. So: ship to
        // testers on 35 now, then bump to 36 + real inset handling before the deadline,
        // with the app on a real phone. See CLAUDE.md → Next up.
        targetSdk = 35
        // Monotonic versionCode from the CI run number so every CI build is an upgrade
        // over the last; local builds fall back to 1. versionName mirrors it.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Two distribution channels from one source tree.
    //
    //   github — the sideload/self-update channel this app was born on. Keeps the in-app
    //            Updater and REQUEST_INSTALL_PACKAGES (declared in src/github's manifest).
    //   play   — Google Play. The updater is COMPILED OUT, because Play's Device and
    //            Network Abuse policy forbids an app updating itself by any route other
    //            than Play, and REQUEST_INSTALL_PACKAGES may not be used for self-updates.
    //            Shipping the updater here is not a lint nit; it is a removal.
    //
    // Same applicationId on purpose — one app, two ways of getting it. But Play re-signs
    // with its own app signing key, so a Play install and a sideload install have DIFFERENT
    // signatures and cannot upgrade into each other: switching channels means uninstall →
    // reinstall, which wipes on-device data. Back up first (CLAUDE.md → Distribution).
    flavorDimensions += "channel"
    productFlavors {
        create("github") {
            dimension = "channel"
            buildConfigField("boolean", "UPDATER_ENABLED", "true")
        }
        create("play") {
            dimension = "channel"
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
        }
    }

    buildFeatures {
        viewBinding = true
        // The in-app updater compares the server's advertised versionCode against this
        // build's own (BuildConfig.VERSION_CODE), so BuildConfig must be generated.
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        debug {
            // Sign the debug build (CI ships assembleDebug) with the stable key so
            // sideloaded updates install over the top instead of as a new app.
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // WebView hybrid shell: ServiceWorkerController + algorithmic-darkening feature checks.
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.car.app:app:1.4.0")
    // Android Auto projection support — required alongside the core artifact for the
    // phone-projected (head unit) host; without it Auto won't bind the CarAppService.
    implementation("androidx.car.app:app-projected:1.4.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Backup destination folder (BACKUP.md): background writes into a persisted SAF tree.
    implementation("androidx.documentfile:documentfile:1.0.1")
    // QR pairing (AUTH.md): scan the server's device-token QR. Self-contained scanner
    // Activity, no Play Services / camera-permission plumbing of our own.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    // Robolectric picks its android-all jar from targetSdk, so it moves WITH the toolchain:
    // 4.13 has no jar for SDK 35 and every Robolectric test dies at init (DefaultSdkPicker
    // → IllegalArgumentException), which is not an assertion failure and reads nothing like
    // one. 4.16 carries both 35 and 36, so the August targetSdk 36 bump won't re-break it.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
