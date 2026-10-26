plugins {
    id("mysystemapp.android.library")
    id("mysystemapp.android.compose")
    id("mysystemapp.android.hilt")
}
android { namespace = "com.android.mysystemapp.feature.vehiclecontrol" }
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:vehicle"))
    implementation(project(":core:vehicle-model"))
    implementation(project(":feature:doorcontrol"))
    implementation(project(":feature:seatcontrol"))
    implementation(project(":feature:hvac"))
    implementation(project(":feature:vehiclelighting"))
    implementation(project(":feature:driverassistance"))
    implementation(project(":feature:vehiclemodes"))
    implementation(project(":third_party:bmaterial-ccp-rotary-focus"))
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
