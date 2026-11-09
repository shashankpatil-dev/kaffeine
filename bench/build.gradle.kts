plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.jmh)
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

jmh {
    jmhVersion = libs.versions.jmh.core.get()
}

// CI never runs the benchmarks, but it should still catch them failing to compile.
tasks.check {
    dependsOn("jmhClasses")
}
