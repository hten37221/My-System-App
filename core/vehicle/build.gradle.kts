import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("mysystemapp.android.library")
    id("mysystemapp.android.hilt")
}

android {
    namespace = "com.android.mysystemapp.core.vehicle"
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.coroutines.core)
    implementation(libs.dagger.hilt.android)

    compileOnly(fileTree(rootProject.file("libs/platform")) { include("*.jar") })

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}

afterEvaluate {
    tasks.withType<JavaCompile>().configureEach {
        val platformJars = fileTree(rootProject.file("libs/platform")) { include("*.jar") }
        options.bootstrapClasspath = platformJars
        classpath = platformJars + classpath
    }
}
