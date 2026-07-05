# Brief: mosaicast-plugin-sdk

> Prerequisite: read `docs/ARCHITECTURE.md`, especially §7 (plugin system).
> **This repo is the hard boundary of the whole system.** It defines the contract that core AND all plugins build against. It must have **no** dependency on core or on plugins.

## Purpose
The versioned, slim **plugin contract package** in two artifacts:
- **Java:** `plugin-api` JAR — extension points + context interfaces that plugin backends implement/use.
- **TypeScript:** `@mosaicast/plugin-sdk` npm package — types + helpers for the frontend `ctx` and a Web Component base.

## Content – Java (`plugin-api`)
Exactly the interfaces from ARCHITECTURE §7.4 as pure contracts, no implementation:
- `PluginBackend extends ExtensionPoint { void register(PluginContext ctx); }`
- `PluginContext` with `store()`, `schema()`, `config()`, `feeds()`, `onSchedule(...)`.
- `DocStore` (get/put/query, hard-scoped via `Scope`), `SchemaStore`, `PluginConfig`, `FeedAccess`.
- Value types: `Scope(ScopeType, id)` with `ScopeType ∈ {SITE,FEED,SEASON,EPISODE}`, `Access`, `DisplaySnapshot`, `Role`.
- A **`platformApi` version constant** (SemVer) — single source for manifest compatibility.
- **`ShareMetadataProvider`** (optional extension point, ARCHITECTURE §6.4/§7.4): `metaFor(subpath) → Optional<OgMeta(title, description, imageUrl?)>` — lets a plugin provide share metadata for its deep links under `/p/<id>/…`.
- **`SitemapProvider`** (optional extension point, ARCHITECTURE §6.6/§7.4): `urls() → List<SitemapUrl(loc, lastModified?)>` — lets a plugin contribute its pages to the site's `sitemap.xml`.
- No Spring/PF4J-specific dependencies beyond the `ExtensionPoint` marker (PF4J API as `compileOnly`/`api`, document the choice).

## Content – TypeScript (`@mosaicast/plugin-sdk`)
- Type `PluginContext` exactly as ARCHITECTURE §7.5 (scope, episodes, episode?, user, api, consent, filter, player, **route**, **locale**, **progress**, theme).
- Types: `Role`, `Scope`, `FilterState`, `ThemeTokens`, `PluginApiClient`.
- A small **base helper** `defineMosaicastElement(...)` that registers a Web Component, creates a shadow root, accepts `ctx` as a property and injects the theme tokens as CSS custom properties into the shadow root. So a plugin author only writes render logic.
- A small **i18n helper** `createPluginI18n(catalogs)` (ARCHITECTURE §12.7): plugins ship their own `locales/*.json` and translate against `ctx.locale` (incl. `onChange`) — same convention as the shell, no extra library required in the plugin.
- Re-export of the `platformApi` version (must match the Java constant).

## Test kit (deliverable, ARCHITECTURE §13.5)
The contract ships its test doubles so plugins are testable without core/DB. Production code does not bundle them.
- **Java `plugin-testkit`** — separate artifact (consumer uses `testImplementation`): `FakePluginContext`, `InMemoryDocStore`, `FakeFeedAccess(Map<Scope,List<String>>)`, `MapPluginConfig`, synchronous `onSchedule`.
- **TS `@mosaicast/plugin-sdk/testing`** — dev subpath export: `makeMockCtx(overrides)` → `PluginContext` with a fake `api` (call recording/canned responses), in-memory `consent`/`filter`/`player`, theme tokens.

## Public contract / stability
- **SemVer is sacred.** Breaking changes to interfaces = major bump of `platformApi`. core rejects plugins with an incompatible `platformApi` at startup.
- Everything here is API surface. Add nothing that is core-internal.

## Build, distribution & docs (ARCHITECTURE §3.5)
- **Java:** Gradle. `./gradlew build publishToMavenLocal` → consumers pull from `mavenLocal()`. Alternatively (nicer during parallel development) repos consume the SDK via a **composite build** `includeBuild`. Enable **`withSourcesJar()` + `withJavadocJar()`** → IntelliJ shows Javadoc/sources automatically.
- **TS:** `npm run build` (tsc → `dist` with `.d.ts`), consumable via `npm link` or tarball. TSDoc comments on all types (IDE hover). Optional TypeDoc HTML.
- Keep both version numbers in sync (one `version`, mirrored).
- **Documentation is mandatory:** complete **Javadoc/TSDoc** on all public types/methods incl. nullability + error behavior. The built SDK + its docs are the **source of truth** for signatures.
- **License: Apache-2.0** (permissive, with patent clause). SPDX header in every source file.

## Definition of Done
- Both artifacts build, export exactly the interfaces from §7.4/§7.5, contain the `platformApi` constant, and are locally consumable (mavenLocal/includeBuild, npm link). README explains how to build against them.
- **Sources + Javadoc JAR** are built/published; TS ships `.d.ts` with TSDoc. Hover docs work in the IDE.
- All public types carry Javadoc/TSDoc.
- **Test kit** (Java `plugin-testkit` + TS `/testing`) available; README shows a mini test example.
- `LICENSE` = Apache-2.0, SPDX headers set, `CONTRIBUTING.md` + DCO workflow present.
