plugins {
    id("mysystemapp.android.library")
    id("mysystemapp.android.hilt")
}
android { namespace = "com.android.mysystemapp.feature.vehiclemodes" }
dependencies {
    implementation(project(":core:vehicle"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
