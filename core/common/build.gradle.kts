plugins {
    id("mysystemapp.android.library")
    id("mysystemapp.android.hilt")
}

android {
    namespace = "com.android.mysystemapp.core.common"
}

dependencies {
    implementation(libs.dagger.hilt.android)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}
