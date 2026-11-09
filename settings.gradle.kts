rootProject.name = "kaffeine"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// core = the library we publish, bench = simulator and benchmarks (never published)
include("core", "bench")
