import org.gradle.api.GradleException
import org.gradle.api.tasks.Copy
import java.util.Properties

plugins {
    id("mysystemapp.android.application")
    id("mysystemapp.android.navigation.compose")
    id("mysystemapp.android.hilt")
    alias(libs.plugins.androidx.baselineprofile)
}

val platformKeystorePropertiesFile =
    providers
        .environmentVariable("MY_SYSTEM_APP_KEYSTORE_PROPERTIES")
        .orNull
        ?.let(rootProject::file)
        ?: rootProject.file("keystore.properties")
val platformKeystoreLoadResult =
    runCatching {
        platformKeystorePropertiesFile
            .takeIf { it.isFile }
            ?.let { file ->
                Properties().apply {
                    file.inputStream().use(::load)
                }
            }
    }
val platformKeystoreProperties = platformKeystoreLoadResult.getOrNull()
val requiredPlatformKeystoreProperties =
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword", "storeType")
val missingPlatformKeystoreProperties =
    requiredPlatformKeystoreProperties.filter { property ->
        platformKeystoreProperties?.getProperty(property).isNullOrBlank()
    }
val platformStoreFile =
    platformKeystoreProperties
        ?.getProperty("storeFile")
        ?.takeIf { it.isNotBlank() }
        ?.let(rootProject::file)
val platformSigningIssue =
    when {
        !platformKeystorePropertiesFile.isFile ->
            "Missing ${platformKeystorePropertiesFile.path}."
        platformKeystoreLoadResult.isFailure ->
            "Cannot read ${platformKeystorePropertiesFile.path}: " +
                platformKeystoreLoadResult.exceptionOrNull()?.message.orEmpty()
        missingPlatformKeystoreProperties.isNotEmpty() ->
            "Missing or blank properties in ${platformKeystorePropertiesFile.path}: " +
                missingPlatformKeystoreProperties.joinToString()
        platformStoreFile?.isFile != true ->
            "Signing storeFile does not exist or is not a file: ${platformStoreFile?.path}."
        else -> null
    }

val platformApiJars =
    fileTree(rootProject.file("libs/platform")) {
        include("*.jar")
    }
val systemServerJars =
    fileTree(rootProject.file("libs/system-server")) {
        include("*.jar")
    }

android {
    namespace = "com.android.mysystemapp"

    lint {
        // This APK is staged as an AOSP privileged/system app; platform signing and
        // privapp-permissions.xml grant the signature permissions below at integration time.
        disable += setOf("ProtectedPermissions", "QueryAllPackagesPermission")
        // Dependency update checks perform network lookups even for an otherwise offline build.
        // App correctness is covered by the module lint tasks and the dependency versions are
        // recorded separately in the audit report; keep this gate deterministic on air-gapped
        // AOSP workstations.
        checkDependencies = false
    }

    defaultConfig {
        applicationId = "com.android.mysystemapp"
        versionCode = 52
        versionName = "3.0.0"
    }

    val platformSigningConfig =
        if (platformSigningIssue == null) {
            signingConfigs.create("platform") {
                storeFile = platformStoreFile
                storePassword = platformKeystoreProperties?.getProperty("storePassword")
                keyAlias = platformKeystoreProperties?.getProperty("keyAlias")
                keyPassword = platformKeystoreProperties?.getProperty("keyPassword")
                storeType = platformKeystoreProperties?.getProperty("storeType")
            }
        } else {
            null
        }

    buildTypes {
        debug {
            // Keep connected tests installable over the platform-signed system app when local
            // signing material exists. A normal debug build remains independent of that material.
            platformSigningConfig?.let { signingConfig = it }
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            platformSigningConfig?.let { signingConfig = it }
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            // The Automotive system image requires a platform-signed update when local signing
            // material is present. Keep a debug-key fallback so benchmark APKs remain buildable
            // on contributor machines that only compile the performance infrastructure.
            signingConfig = platformSigningConfig ?: signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            res.srcDirs("res")
        }
    }
}

baselineProfile {
    automaticGenerationDuringBuild = false
    mergeIntoMain = true
    saveInSrc = true
}

val generatedBaselineProfileFiles =
    listOf(
        layout.projectDirectory.file("src/main/generated/baselineProfiles/baseline-prof.txt"),
        layout.projectDirectory.file("src/main/generated/baselineProfiles/startup-prof.txt"),
    )

// API 37's connected UTP runner can append its shell sentinel to a collected profile rule. The
// marker is transport metadata, not ART profile syntax, and is not reliably the final file token
// when multiple profile tests are merged. Strip the marker itself after the AndroidX plugin copies
// generated profiles into source while preserving the surrounding ART rule and line structure.
val sanitizeGeneratedBaselineProfile by tasks.registering {
    group = "verification"
    description = "Removes connected-UTP transport metadata from generated ART profile rules."
    doLast {
        generatedBaselineProfileFiles.forEach { profileFile ->
            val file = profileFile.asFile
            if (!file.isFile) return@forEach
            val original = file.readText()
            val sanitized = original.replace(Regex("utp_shell_exit_code=\\d+"), "")
            if (sanitized != original) file.writeText(sanitized)
        }
    }
}

tasks.matching { it.name == "copyBaselineProfileIntoSrc" }.configureEach {
    finalizedBy(sanitizeGeneratedBaselineProfile)
}

val verifyEmbeddedBaselineProfile by tasks.registering {
    group = "verification"
    description = "Validates the generated Baseline/Startup Profiles committed with the app."
    inputs.files(generatedBaselineProfileFiles)
    doLast {
        generatedBaselineProfileFiles.forEach { profileFile ->
            val file = profileFile.asFile
            if (!file.isFile || file.length() == 0L) {
                throw GradleException("Missing generated profile: ${file.path}")
            }
            val text = file.readText()
            if ("utp_shell_exit_code" in text) {
                throw GradleException("Generated profile contains UTP transport metadata: ${file.path}")
            }
            if ("Lcom/android/mysystemapp/" !in text) {
                throw GradleException("Generated profile contains no first-party app rules: ${file.path}")
            }
        }
    }
}

tasks.matching { it.name in setOf("check", "assembleBenchmark", "assembleRelease") }.configureEach {
    dependsOn(verifyEmbeddedBaselineProfile)
}

val validatePlatformSigning by tasks.registering {
    group = "verification"
    description = "Validates platform signing material required by release and AOSP staging."
    doLast {
        if (platformSigningIssue != null) {
            throw GradleException(
                "Platform signing is required for release and AOSP staging. " +
                    "$platformSigningIssue Copy keystore.properties.example to " +
                    "keystore.properties and configure a local platform keystore. " +
                    "Debug, unit-test, and static-analysis tasks do not require it.",
            )
        }
    }
}

tasks
    .matching {
        it.name in
            setOf(
                "assembleRelease",
                "bundleRelease",
                "installRelease",
                "packageRelease",
                "stageAospApk",
            )
    }.configureEach {
        dependsOn(validatePlatformSigning)
    }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:vehicle"))
    implementation(project(":feature:vehiclemodes"))
    implementation(project(":feature:vehiclecontrol"))
    implementation(project(":feature:doorcontrol"))
    implementation(project(":feature:driverassistance"))
    implementation(project(":feature:hvac"))
    implementation(project(":feature:seatcontrol"))
    implementation(project(":feature:vehiclelighting"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(project(":third_party:bmaterial-ccp-rotary-focus"))
    implementation(libs.androidx.profileinstaller)

    baselineProfile(project(":baselineprofile"))

    compileOnly(platformApiJars)
    compileOnly(systemServerJars)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(project(":core:vehicle"))
    androidTestImplementation(project(":core:common"))
    androidTestImplementation(project(":core:vehicle-model"))
    androidTestImplementation(project(":feature:vehiclecontrol"))
    androidTestImplementation(project(":feature:vehiclemodes"))
    androidTestImplementation(project(":feature:doorcontrol"))
    androidTestImplementation(project(":feature:seatcontrol"))
    androidTestImplementation(project(":feature:hvac"))
    androidTestImplementation(project(":feature:vehiclelighting"))
    androidTestImplementation(project(":feature:driverassistance"))
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestCompileOnly(platformApiJars)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

tasks.register<Copy>("stageAospApk") {
    group = "distribution"
    description = "Builds and stages the platform-signed release APK consumed by Android.bp."
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("prebuilts"))
    rename { "MySystemApp.apk" }
}

tasks.register("reportReleaseApkSize") {
    group = "verification"
    description = "Builds the optimized release APK and reports its size against a 100 MB budget."
    dependsOn("assembleRelease")

    doLast {
        val apk =
            layout.buildDirectory
                .file("outputs/apk/release/app-release.apk")
                .get()
                .asFile
        check(apk.isFile) { "Release APK was not produced at ${apk.path}" }
        val warningThresholdBytes = 100_000_000L
        val sizeBytes = apk.length()
        val sizeMiB = sizeBytes.toDouble() / (1024 * 1024)
        logger.lifecycle("RELEASE_APK_SIZE: %.2f MiB (%d bytes)".format(sizeMiB, sizeBytes))
        if (sizeBytes > warningThresholdBytes) {
            logger.warn(
                "Release APK exceeds the 100 MB warning budget by ${sizeBytes - warningThresholdBytes} bytes.",
            )
        }
    }
}
