plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.jupiterns.drivetime"
    compileSdk = 34

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
        targetSdk = 34
        // Monotonic versionCode from the CI run number so every CI build is an upgrade
        // over the last; local builds fall back to 1. versionName mirrors it.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    buildFeatures {
        viewBinding = true
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
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
