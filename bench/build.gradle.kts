plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.caffeine)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "io.github.shashankpatildev.kaffeine.bench.SimulatorKt"
    // The big traces have tens of millions of requests, and the caches hold millions of entries.
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}

tasks.named<JavaExec>("run") {
    // Run from the repo root, so paths like traces/DS1.lis just work.
    workingDir = rootDir
}

tasks.test {
    useJUnitPlatform()
}
