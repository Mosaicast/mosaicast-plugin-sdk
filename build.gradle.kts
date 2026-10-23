// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

// Root build for the Mosaicast plugin SDK.
//
// Two published artifacts share this configuration:
//   :plugin-api      — the versioned plugin contract (pure interfaces/records, no impl).
//   :plugin-testkit  — test doubles so plugins are testable without core/DB.
//
// The single `version` below is the SemVer anchor mirrored by:
//   - dev.mosaicast.plugin.api.PlatformApi.VERSION (Java)
//   - PLATFORM_API_VERSION in the TS package (src/index.ts)
//   - the npm package version (package.json)
// They MUST move together — a breaking contract change is a major bump.
//
// Publishing: Maven Central (Central Portal) through com.vanniktech.maven.publish — plain maven-publish cannot
// talk to the Portal API — plus GitHub Packages, kept while plugins move their builds to mavenCentral().
// Releases are signed; `publishToMavenLocal` needs no keys or credentials.

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.plugins.signing.SigningExtension

plugins {
    id("com.vanniktech.maven.publish.base") version "0.37.0" apply false
}

/** The POM name and one-line description of each published module; Central requires both. */
val pomInfo = mapOf(
    "plugin-api" to ("Mosaicast Plugin API" to
        "The Mosaicast plugin contract: the interfaces and records a plugin backend builds against."),
    "plugin-testkit" to ("Mosaicast Plugin Test Kit" to
        "Test doubles for the Mosaicast plugin contract, for testing a plugin without core or a database."),
)

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.vanniktech.maven.publish.base")

    group = "dev.mosaicast"
    version = "0.20.0"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        // DoD: IntelliJ shows Javadoc/sources automatically for consumers — the -sources and -javadoc jars
        // come from the publishing configuration below (JavaLibrary), which Central requires anyway.
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:all")
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            addStringOption("Xdoclint:all,-missing", "-quiet")
        }
    }

    extensions.configure<MavenPublishBaseExtension> {
        configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = SourcesJar.Sources()))
        // Manual release: `publishToMavenCentral` uploads a deployment that waits in the Portal to be checked and
        // then published (or dropped) by hand. A Central release is permanent — it can never be deleted or
        // overwritten — so automatic release stays off until a release has been through this once.
        publishToMavenCentral(automaticRelease = false)
        // Keys come from ORG_GRADLE_PROJECT_signingInMemoryKey{,Id,Password} in CI, or signing.* locally.
        signAllPublications()
        pom {
            name.set(pomInfo.getValue(project.name).first)
            description.set(pomInfo.getValue(project.name).second)
            inceptionYear.set("2026")
            url.set("https://github.com/Mosaicast/mosaicast-plugin-sdk")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("mosaicast")
                    name.set("The Mosaicast Authors")
                    url.set("https://github.com/Mosaicast")
                }
            }
            scm {
                url.set("https://github.com/Mosaicast/mosaicast-plugin-sdk")
                connection.set("scm:git:https://github.com/Mosaicast/mosaicast-plugin-sdk.git")
                developerConnection.set("scm:git:ssh://git@github.com/Mosaicast/mosaicast-plugin-sdk.git")
            }
            issueManagement {
                system.set("GitHub")
                url.set("https://github.com/Mosaicast/mosaicast-plugin-sdk/issues")
            }
        }
    }

    // Signing is required only on the way to Central, which refuses unsigned artifacts: `publishToMavenLocal`
    // and a contributor's build need no key, while a Central upload without one fails here instead of in the
    // Portal's validation.
    extensions.configure<SigningExtension> {
        setRequired({ gradle.taskGraph.allTasks.any { it.name.contains("MavenCentral") } })
    }

    extensions.configure<PublishingExtension> {
        repositories {
            // Transitional second target, until plugins have moved to mavenCentral(). Local devs use
            // `publishToMavenLocal` (mavenLocal, no creds).
            // In GitHub Actions the built-in GITHUB_ACTOR/GITHUB_TOKEN are used; locally set gpr.user/gpr.token
            // (or the same env vars) with a PAT that has `write:packages`.
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Mosaicast/mosaicast-plugin-sdk")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: (findProperty("gpr.user") as String?)
                    password = System.getenv("GITHUB_TOKEN") ?: (findProperty("gpr.token") as String?)
                }
            }
        }
    }
}
