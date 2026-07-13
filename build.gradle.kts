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

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    group = "dev.mosaicast"
    version = "0.3.0"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        // DoD: IntelliJ shows Javadoc/sources automatically for consumers.
        withSourcesJar()
        withJavadocJar()
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.1.1"))
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

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
        repositories {
            // Remote target for CI releases. Local devs use `publishToMavenLocal` (mavenLocal, no creds).
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
