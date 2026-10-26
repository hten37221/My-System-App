plugins {
    `kotlin-dsl`
}

group = "com.android.mysystemapp.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "mysystemapp.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "mysystemapp.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "mysystemapp.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidNavigationCompose") {
            id = "mysystemapp.android.navigation.compose"
            implementationClass = "AndroidNavigationComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "mysystemapp.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidKtlint") {
            id = "mysystemapp.android.ktlint"
            implementationClass = "AndroidKtlintConventionPlugin"
        }
        register("androidDetekt") {
            id = "mysystemapp.android.detekt"
            implementationClass = "AndroidDetektConventionPlugin"
        }
        register("androidJacoco") {
            id = "mysystemapp.android.jacoco"
            implementationClass = "AndroidJacocoConventionPlugin"
        }
        register("rootKtlint") {
            id = "mysystemapp.root.ktlint"
            implementationClass = "RootKtlintConventionPlugin"
        }
        register("rootDetekt") {
            id = "mysystemapp.root.detekt"
            implementationClass = "RootDetektConventionPlugin"
        }
        register("rootJacoco") {
            id = "mysystemapp.root.jacoco"
            implementationClass = "RootJacocoConventionPlugin"
        }
    }
}
