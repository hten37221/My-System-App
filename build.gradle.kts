import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.Exec

plugins {
    id("mysystemapp.root.ktlint")
    id("mysystemapp.root.detekt")
    id("mysystemapp.root.jacoco")
}

private val bMaterialGroup = "com.b231001.bmaterial"
private val pythonCommand =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3"

private val publishedBMaterialArtifacts =
    setOf(
        "ui-core-tokens",
        "ui-core-resources",
        "ui-core-foundation",
        "ui-components-button",
        "ui-components-dialog",
        "ui-components-card",
        "ui-components-chip",
        "ui-components-listitem",
        "ui-components-slider",
        "ui-components-scrollbar",
        "ui-components-switch",
        "ui-components-text",
        "ui-components-textfield",
    )

val verifyBmaterialBundle by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates checksums and metadata for the bundled B-Material local Maven repository."
    commandLine(pythonCommand, "scripts/verify_bmaterial_local.py")
}

tasks.register("verifyBmaterialLocal") {
    group = "verification"
    description = "Verifies that app and core UI resolve B-Material only from the bundled local AAR set."
    dependsOn(verifyBmaterialBundle)

    doLast {
        val projectsToCheck = listOf(":core:ui", ":app")
        projectsToCheck.forEach { path ->
            val configuration = project(path).configurations.getByName("debugRuntimeClasspath")
            val artifacts =
                configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
                    (component.id as? ModuleComponentIdentifier)
                        ?.takeIf { it.group == bMaterialGroup }
                        ?.module
                }
            val names = artifacts.toSet()
            check(names == publishedBMaterialArtifacts) {
                "$path resolved unexpected B-Material artifacts: ${names.sorted()}"
            }
            check("ccp-rotary-focus" !in names) {
                "$path must use the vendored CCP source module, not the B-Material AAR."
            }
        }
        logger.lifecycle(
            "B-Material resolution PASS: ${publishedBMaterialArtifacts.size} local AARs, CCP from source project.",
        )
    }
}

tasks.register<Exec>("verifyVehiclePropertyCatalog") {
    group = "verification"
    description = "Parses and verifies the checked-in Vehicle Control property catalog."
    commandLine(pythonCommand, "vehicle/catalog/validate_catalog.py")
}

val verifyFirstPartyResources by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks first-party EN/VI parity and rejects mojibake in production sources."
    commandLine(pythonCommand, "scripts/validate_first_party_resources.py")
}

tasks.register("auditCheck") {
    group = "verification"
    description = "Runs repository-level resource, catalog, and bundled dependency audits."
    dependsOn("verifyBmaterialLocal", "verifyVehiclePropertyCatalog", verifyFirstPartyResources)
}
