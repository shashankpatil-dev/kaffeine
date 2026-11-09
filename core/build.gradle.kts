plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
    // Every public class and function must say "public" on purpose, so nothing leaks by accident.
    explicitApi()
}

dependencies {
    // api, because loadContext in the public config takes a coroutine dispatcher
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.lincheck)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "kaffeine"
            from(components["java"])
            pom {
                name = "Kaffeine"
                description = "A coroutine-native in-memory cache for Kotlin, with W-TinyLFU eviction and timing-wheel expiry"
                url = "https://github.com/shashankpatil-dev/kaffeine"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                scm {
                    url = "https://github.com/shashankpatil-dev/kaffeine"
                }
            }
        }
    }
}

kover {
    reports {
        verify {
            rule {
                minBound(85) // percent of lines covered by tests; the build fails below this
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // Lincheck's model checker keeps a lot of state while it explores thread interleavings.
    maxHeapSize = "2g"
}
