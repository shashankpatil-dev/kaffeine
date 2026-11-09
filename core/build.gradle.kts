plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    // Every public class and function must say "public" on purpose, so nothing leaks by accident.
    explicitApi()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.lincheck)
}

tasks.test {
    useJUnitPlatform()
    // Lincheck's model checker keeps a lot of state while it explores thread interleavings.
    maxHeapSize = "2g"
}
