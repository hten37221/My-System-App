plugins {
    id("mysystemapp.android.library")
    id("mysystemapp.android.compose")
}
android { namespace = "com.android.mysystemapp.core.vehiclemodel" }
dependencies {
    implementation(project(":core:common"))
    testImplementation(libs.junit)
}
