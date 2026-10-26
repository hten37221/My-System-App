import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("mysystemapp.android.library")
    id("mysystemapp.android.navigation.compose")
    id("mysystemapp.android.hilt")
}

android { namespace = "com.android.mysystemapp.feature.vehiclelighting" }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:vehicle"))
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.coroutines.core)
    compileOnly(fileTree(rootProject.file("libs/platform")) { include("*.jar") })
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testCompileOnly(fileTree(rootProject.file("libs/platform")) { include("*.jar") })
    androidTestImplementation(libs.androidx.junit)
}

afterEvaluate {
    tasks.withType<JavaCompile>().configureEach {
        val platformJars = fileTree(rootProject.file("libs/platform")) { include("*.jar") }
        options.bootstrapClasspath = platformJars
        classpath = platformJars + classpath
    }
}
