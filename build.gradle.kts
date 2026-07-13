plugins {
    // AGP 8.9.1 is the floor for compileSdk 36; 8.10 requires Gradle 8.11.1+. CI generates
    // the wrapper, so these two move together — bumping one alone fails at configuration.
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
