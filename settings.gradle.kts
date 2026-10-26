pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "BundledBMaterial"
                    url = uri("$rootDir/libs/bmaterial/maven")
                    metadataSources {
                        gradleMetadata()
                        mavenPom()
                        artifact()
                    }
                }
            }
            filter {
                includeGroup("com.b231001.bmaterial")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "My-System-App"
include(":app")
include(":benchmark")
include(":baselineprofile")
include(":core:common")
include(":core:ui")
include(":core:vehicle")
include(":core:vehicle-model")
include(":feature:doorcontrol")
include(":feature:driverassistance")
include(":feature:hvac")
include(":feature:seatcontrol")
include(":feature:vehiclelighting")
include(":feature:vehiclecontrol")
include(":feature:vehiclemodes")
include(":third_party:bmaterial-ccp-rotary-focus")
