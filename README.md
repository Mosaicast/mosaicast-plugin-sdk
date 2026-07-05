# mosaicast-plugin-sdk

> Versioned plugin contract SDK (Java `plugin-api` + `@mosaicast/plugin-sdk` TS) plus a test kit.

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
- **Java (plugin backend):** `./gradlew publishToMavenLocal`, then in the plugin build:
  ```kotlin
  dependencies {
      compileOnly("dev.mosaicast:plugin-api:0.1.0")           // contract, provided by the host
      testImplementation("dev.mosaicast:plugin-testkit:0.1.0") // test doubles only
  }
  ```
  Or, nicer during parallel development, a composite build: `includeBuild("../mosaicast-plugin-sdk")`.
  Sources + Javadoc JARs give IDE hover docs automatically.
- **TypeScript (plugin frontend):** `npm link @mosaicast/plugin-sdk` (or a tarball). `.d.ts` + TSDoc give IDE hover docs.
- **The source of truth for signatures** is this built SDK + its docs.

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
