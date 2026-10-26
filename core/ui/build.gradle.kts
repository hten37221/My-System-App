plugins {
    id("mysystemapp.android.library")
    id("mysystemapp.android.compose")
}

android {
    namespace = "com.android.mysystemapp.core.ui"
}

dependencies {
    implementation(project(":core:vehicle"))
    implementation(libs.androidx.core.ktx)

    // B-Material is the design-system source of truth for the automotive UI. Keep this list
    // deliberately focused on components consumed by the shared UI layer.
    implementation(libs.bmaterial.ui.core.tokens)
    implementation(libs.bmaterial.ui.core.resources)
    implementation(libs.bmaterial.ui.core.foundation)
    implementation(libs.bmaterial.ui.components.button)
    implementation(libs.bmaterial.ui.components.dialog)
    implementation(libs.bmaterial.ui.components.card)
    implementation(libs.bmaterial.ui.components.chip)
    implementation(libs.bmaterial.ui.components.listitem)
    implementation(libs.bmaterial.ui.components.slider)
    implementation(libs.bmaterial.ui.components.scrollbar)
    implementation(libs.bmaterial.ui.components.switch)
    implementation(libs.bmaterial.ui.components.text)
    implementation(libs.bmaterial.ui.components.textfield)
    implementation(project(":third_party:bmaterial-ccp-rotary-focus"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    // Compose's transitive Espresso 3.5.x reflects InputManager.getInstance(),
    // which is no longer present on the API 37 emulator. Pin the repository's
    // API-37-compatible Espresso version for connected Compose tests.
    androidTestImplementation(libs.androidx.espresso.core)
}
