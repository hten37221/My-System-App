plugins {
    // AGP is provided by the included convention build. AGP 9 has built-in
    // Kotlin support, so no Kotlin Gradle plugin is applied here.
    id("com.android.test")
}

android {
    namespace = "com.android.mysystemapp.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The connected Automotive emulator is valuable for regression trends but
        // is not representative of production hardware. The reports retain that
        // limitation; this only allows the test framework to collect the data.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "EMULATOR,NOT-SELF-INSTRUMENTING,SHELL-ACCESS-DENIED"
    }

    targetProjectPath = ":app"
    // Macrobenchmark tests must run in their own process. The Automotive image also blocks
    // shell access to app data; the runner argument above records that limitation as a warning.
    experimentalProperties["android.experimental.self-instrumenting"] = "true"

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            matchingFallbacks += listOf("release")
            // AGP does not infer a signing config for the standalone com.android.test variant.
            // The connected AVD requires a signed test APK before it can collect metrics.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.rules)
}
