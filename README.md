# mosaicast-plugin-sdk

> Versioned plugin contract SDK (Java `plugin-api` + `@mosaicast/plugin-sdk` TS) plus a test kit.

[![CI](https://github.com/Mosaicast/mosaicast-plugin-sdk/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/Mosaicast/mosaicast-plugin-sdk/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/@mosaicast/plugin-sdk?logo=npm)](https://www.npmjs.com/package/@mosaicast/plugin-sdk)
[![GitHub Packages](https://img.shields.io/github/v/release/Mosaicast/mosaicast-plugin-sdk?label=github%20packages&logo=github&color=2ea44f)](https://github.com/Mosaicast/mosaicast-plugin-sdk/packages)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Mosaicast/mosaicast-plugin-sdk/badge)](https://scorecard.dev/viewer/?uri=github.com/Mosaicast/mosaicast-plugin-sdk)
[![License](https://img.shields.io/github/license/Mosaicast/mosaicast-plugin-sdk?color=blue)](LICENSE)

<!-- Badges are dynamic: CI reflects the latest master run; npm/GitHub-Packages track the published
     version (GitHub Packages via the release tag — Maven coords and releases move together); the
     OpenSSF Scorecard badge is populated by .github/workflows/scorecard.yml. No version is hardcoded. -->

Part of **[Mosaicast](https://github.com/mosaicast)** — an extensible website platform for podcasts. Status: **v1 in development**.

This repo is the **hard contract boundary** of the whole system: core AND every plugin compile against it, and it depends on neither. See `docs/ARCHITECTURE.md` for the big picture and `docs/BRIEF.md` for this repo's scope.

## Layout

```
plugin-api/        Java: the plugin contract (interfaces + records, no impl)   → dev.mosaicast.plugin.api.*
plugin-testkit/    Java: test doubles (FakePluginContext, InMemoryDocStore, …) → dev.mosaicast.plugin.testkit.*
src/index.ts       TS:  @mosaicast/plugin-sdk — PluginContext, defineMosaicastElement, createPluginI18n
src/testing.ts     TS:  @mosaicast/plugin-sdk/testing — makeMockCtx
```

The contract version is a **single SemVer anchor** mirrored in four places that MUST move together
(CI enforces it): `build.gradle.kts` · `package.json` · `PlatformApi.VERSION` · `PLATFORM_API_VERSION`.
A breaking change is a major bump; the host rejects plugins with an incompatible `platformApi` at startup.

## Build & test
```bash
./gradlew build publishToMavenLocal   # Java: both JARs (+ sources & javadoc) → ~/.m2
npm ci && npm run build               # TypeScript: src → dist (.js + .d.ts)
./gradlew test  &&  npm test          # all tests
```

## Using it
- **Java (plugin backend)** — three ways to obtain the artifacts:
  - Composite build (nicest during parallel dev): `includeBuild("../mosaicast-plugin-sdk")`.
  - Local: `./gradlew publishToMavenLocal`, then depend on `dev.mosaicast:plugin-api:<version>`.
  - Released: from **GitHub Packages** (see below).
  ```kotlin
  dependencies {
      compileOnly("dev.mosaicast:plugin-api:0.2.0")           // contract, provided by the host
      testImplementation("dev.mosaicast:plugin-testkit:0.2.0") // test doubles only
  }
  ```
  Sources + Javadoc JARs give IDE hover docs automatically.
- **TypeScript (plugin frontend):** `npm install @mosaicast/plugin-sdk` (published on npm), or `npm link` / a tarball for local dev. `.d.ts` + TSDoc give IDE hover docs.
- **The source of truth for signatures** is this built SDK + its docs.

### Consuming released Java artifacts (GitHub Packages)
GitHub Packages requires authentication even for public reads. In the consumer's `build.gradle.kts`:
```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/Mosaicast/mosaicast-plugin-sdk")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
```
The token is any GitHub PAT with `read:packages`. (Anonymous-pull via Maven Central is a possible future move.)

## Releasing (maintainers)
Publishing is automated and fires on a **published GitHub Release**, not on PR merge
(`.github/workflows/release.yml`).
1. `scripts/set-version.sh <version>` — bumps the SemVer anchor in all four sources at once.
2. Commit on a branch, open a PR, merge to `master` (CI `version-parity` guards drift).
3. Draft a **GitHub Release** with tag `v<version>` and publish it.
4. The release workflow verifies the tag equals the anchor, rebuilds + tests, then publishes
   `@mosaicast/plugin-sdk` to npm (OIDC trusted publishing, with provenance) and the Java artifacts to
   GitHub Packages. A tag/anchor mismatch fails the release before anything is published.

## Test kit — mini examples

**Java** (`plugin-testkit`, no core, no DB):
```java
var ctx = new FakePluginContext();               // in-memory store, config, feeds; sync onSchedule
myPlugin.register(ctx);                            // exercise the backend
assertEquals(Optional.of("world"),
        ctx.store().get(Scope.site("main"), "hello", String.class));
```

**TypeScript** (`@mosaicast/plugin-sdk/testing`, jsdom):
```ts
import { makeMockCtx } from '@mosaicast/plugin-sdk/testing';

const el = document.createElement('bingo-episode-card') as HTMLElement & { ctx: PluginContext };
el.ctx = makeMockCtx({ scope: { type: 'episode', id: 'ep-1' } });
document.body.appendChild(el);
expect(el.shadowRoot!.querySelector('.card')).not.toBeNull();
// el.ctx.api is a MockApiClient: assert el.ctx.api.calls after interactions
```

## Contributing
Contributions welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md). In short: `git commit -s` (DCO, required), SPDX header in new files, add tests.

## License
**Apache License 2.0** — see [`LICENSE`](LICENSE). Header per source file:
```
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors
```

## Name & trademark
"Mosaicast" and the logo denote the official project. Please rename forks.
