pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "TideWidget"
includeBuild("tidecore")   // pure-Kotlin tide logic, substituted for com.example.tidewidget:tidecore
include(":app")
