# mosaicast-plugin-sdk

> Versioned plugin contract SDK (Java `plugin-api` + `@mosaicast/plugin-sdk` TS) plus a test kit.

[![CI](https://github.com/Mosaicast/mosaicast-plugin-sdk/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/Mosaicast/mosaicast-plugin-sdk/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/@mosaicast/plugin-sdk?logo=npm)](https://www.npmjs.com/package/@mosaicast/plugin-sdk)
[![GitHub Packages](https://img.shields.io/github/v/release/Mosaicast/mosaicast-plugin-sdk?include_prereleases&label=github%20packages&logo=github&color=2ea44f)](https://github.com/Mosaicast/mosaicast-plugin-sdk/packages)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Mosaicast/mosaicast-plugin-sdk/badge)](https://scorecard.dev/viewer/?uri=github.com/Mosaicast/mosaicast-plugin-sdk)
[![License](https://img.shields.io/github/license/Mosaicast/mosaicast-plugin-sdk?color=blue)](LICENSE)

<!-- Badges are dynamic: CI reflects the latest master run; npm/GitHub-Packages track the published
     version (GitHub Packages via the release tag — Maven coords and releases move together); the
     OpenSSF Scorecard badge is populated by .github/workflows/scorecard.yml. No version is hardcoded.
     The GitHub Packages badge uses include_prereleases because releases are tagged as pre-releases
     while the SDK is pre-1.0. -->

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

**How the host matches it:** core compares `major.minor` **exactly**. A plugin declaring `0.3.x` is
rejected the moment the host runs `0.4.0` — with the reason shown in the admin log viewer. While the SDK
is pre-1.0 a breaking change is therefore a **minor** bump (`0.3.0` → `0.4.0`), not a major one; from
`1.0.0` on, normal SemVer applies and breaking means major.

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
      compileOnly("dev.mosaicast:plugin-api:0.4.0")           // contract, provided by the host
      testImplementation("dev.mosaicast:plugin-testkit:0.4.0") // test doubles only
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

## Plugin data access (v1) — plugins do not define HTTP routes

This is the part plugin authors most often guess wrong, so it is stated plainly. **A plugin's server side is `register(ctx)` — that is the whole of it.** There is no route-registration or HTTP-handler API in the contract, and its absence is a design decision, not a gap (ARCHITECTURE §7.4/§7.5; the generic doc store is the default).

**Backend.** A plugin persists *everything* through `ctx.store()` — the hard-scoped `DocStore`, addressed by `(Scope, key)` — and does any aggregation in `ctx.onSchedule(...)`. (The only alternative is a relational schema *declared in the manifest* and reached via `ctx.schema()`; still not a route.)

**Frontend.** A Web Component reaches plugin data through `ctx.api` (`PluginApiClient`). Those calls do **not** hit plugin-authored routes — they hit a fixed, generic surface the **host** exposes over that same doc store, namespaced per plugin. It mirrors `DocStore` one-to-one — `get` / `put` / `list` / `delete`, and no more:

```text
GET    /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}
         → one JSON doc; 404 if absent
GET    /api/plugins/{id}/data/{scopeType}/{scopeId}?prefix=&page=&size=
         → { items: [{ key, value }], page, size, totalElements, totalPages }
PUT    /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}   (JSON body)
         → upsert, last-write-wins
DELETE /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}
         → remove; idempotent
```

| Operation | Backend (Java) | Frontend (TS) |
|---|---|---|
| read one | `store().get(scope, key, T.class)` → `Optional<T>` | `ctx.api.get<T>('data/…/{key}')` |
| list by prefix | `store().query(scope, prefix)` → `List<DocEntry>` | `ctx.api.get<PagedDocs<T>>('data/…?prefix=…')` |
| upsert | `store().put(scope, key, value)` | `ctx.api.put('data/…/{key}', value)` |
| remove | `store().delete(scope, key)` → `boolean` | `ctx.api.delete('data/…/{key}')` |

- `scopeType` is `site | feed | season | episode` and `scopeId` is that entity's id — mirroring `Scope`/`ScopeType`, the same addressing the backend uses. **`site`'s `scopeId` is always `main`** (`Scope.SITE_ID`): there is only one site, so both the SDK and the host normalize every site scope to that singleton — `Scope.site()` and `…/data/site/main/{key}` address the same document, and the path always has four non-empty segments.
- `key` must match `DocStore.KEY_PATTERN` = `^[A-Za-z0-9._:-]{1,200}$` and is the final path segment, verbatim — no `/`, and no percent-encoded slash either (servlet containers reject `%2F` there). Structure keys with `:` / `.` / `-` instead, e.g. `mark:userId:cell`. The host answers 400 on a bad key, and `InMemoryDocStore` throws `IllegalArgumentException` — so it fails in your tests, not in production.
- The list is **paginated** (core's standard `PagedResponse` envelope) and **carries keys** (`DocEntry`), because neither end can address a doc without one.
- `delete` is **idempotent**: removing an absent doc is not an error. The Java call returns whether anything was actually removed.

**The two ends see one store.** The doc a backend writes with `ctx.store().put(scope, key, value)` is exactly what the frontend reads at `GET /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}`.

**What the host enforces**, so a plugin doesn't have to:
- Data is **hard-scoped to the plugin id** — a plugin can only ever see its own data.
- **Reads** are gated by the slot's `visibleTo`; **writes** require the mapped role. The host validates that the scope exists (and that the feed is enabled). `ctx.api` carries the user's auth (session or personal access token).

**No request-time server logic.** A write is plain persistence — no plugin code runs on the request. Anything derived, validated or aggregated server-side is **precomputed** in `register`/`onSchedule` and read back from the store.

> Roadmap: custom plugin-defined server routes may arrive in a later `platformApi` version; plugins use the doc store.

## Relational storage — `ctx.schema()` (since 0.4.0)

The doc store is the default and covers nearly everything. Declare a **schema** when you need what a JSON
document cannot give you: full-text search, revisions, backlinks. Any plugin may; most declare nothing.

You declare entities and fields in the manifest, and the **platform** provisions namespaced tables
(`plugin_<id>_*`) through its managed migration runner, dropping them when an admin purges the plugin.
**The plugin never writes DDL and never names a table:**

```json
"storage": { "schema": { "page": {
    "slug": "string:indexed:unique", "title": "string",
    "markdown": "text:fulltext", "updatedAt": "timestamp:indexed" } } }
```

```java
record Page(long id, String slug, String title, String markdown, Instant updatedAt) {}

SchemaStore schema = ctx.schema();                      // null unless the manifest declares one

long id = schema.insert("page", Map.of(
        "slug", "getting-started", "title", "Getting started",
        "markdown", "# Hello", "updatedAt", Instant.now()));

Optional<Page> byId = schema.find("page", id, Page.class);
List<Page> recent   = schema.select("page",
        Criteria.all().orderBy("updatedAt", Direction.DESC).limit(20), Page.class);
List<Page> hits     = schema.search("page", "markdown", "lighthouse", Criteria.all(), Page.class);
long total          = schema.count("page", Criteria.all());
schema.update("page", id, Map.of("markdown", "# Hello again"));
schema.delete("page", Criteria.where("slug", Op.EQ, "getting-started"));
```

- Everything is addressed by **declared entity and field name — never SQL, never a table**. That is the
  scoping guarantee: reaching another plugin's tables isn't blocked, it's inexpressible. The host binds
  every value as a JDBC parameter and checks every field name against your manifest.
- Rows map to **your own record types**, component names matching field names — the same convention as
  `store().get(...)` and `config().get(...)`. Each entity carries a platform-assigned `long id`.
- `Criteria` is immutable; predicates combine with **AND** (no `or` in 0.4.0). `search` needs a field
  declared `:fulltext`; for a plain match use `Op.LIKE` with `select`.
- Naming an undeclared entity or field throws `IllegalArgumentException` — against the test kit too, so a
  manifest that drifted from the code fails in your tests.

Test it with `FakeSchemaStore`, declaring the same entities your manifest does. Its `search` is a
substring match, **not** Postgres full-text: no stemming, no ranking. Assert on which rows come back, not
on their order.

## Logging — `ctx.logger()` / `ctx.log()` (since 0.4.0)

```java
ctx.logger().info("indexed {} pages", count);          // org.slf4j.Logger, named plugin.<pluginId>
```
```ts
ctx.log('warn', `no board for episode ${ctx.scope.id}`);
```

A plain SLF4J `Logger` on the backend: authors know the API, and parameterised messages and throwables
come free. **Attribution rides in the logger name**, which is why the host hands you a named logger rather
than offering `log(level, message)` — the name travels with the event, so output is still attributed to
your plugin from a thread you started or from an `onSchedule` task, where a thread-local MDC arrives
empty. Don't build your own `LoggerFactory.getLogger(...)`: it won't sit under the `plugin.` prefix and
the host can't attribute it.

The host stores `info` and above, surfaces `warn` and above in the admin log viewer, and **rate-limits**
this path. On the frontend, `ctx.log` replaces POSTing to `/api/plugins/{id}/log` through `ctx.api`.
`FakePluginContext.logger()` returns a `RecordingLogger`, so a test asserts on logging the way it asserts
on stored documents; `makeMockCtx` collects `ctx.logs`.

## Consent & the manifest (core-owned schema)

Core runs **banner-free** — strictly necessary cookies need no consent. The only reason a visitor sees a
consent prompt is a plugin that asked for one, so treat it as the cost it is.

### `ctx.consent` (frontend)

```ts
if (ctx.consent.has('analytics')) mountChart(root);
else button.onclick = async () => {
  if (await ctx.consent.request('analytics')) mountChart(root);   // from a user gesture, never on mount
};

const off = ctx.consent.onChange(() => rerender());               // fires on every change
return off;                                                       // detach on cleanup
```

`request(category)` opens the host's settings for that one purpose and resolves with the visitor's answer
(`false` if dismissed). `granted()` lists everything currently granted. `onChange` fires on **every**
change — including a withdrawal made in the settings page while you are mounted — and returns an
unsubscribe. Since 0.4.0 `filter`, `route`, `locale` and `player.on` return one too.

**Services describe, categories decide.** The manifest is per-service (`provider`, `privacyUrl`,
`thirdCountryTransfer`, each `storage[]` item) but every `ctx.consent` method takes a **category**. The
per-service detail exists so the notice can name who stores what for how long; what the visitor toggles is
the category. So if two plugins each declare an `analytics` service with different providers, the visitor
sees **one** decision listing both plugins, and granting it grants both — there is no way to accept one
provider and refuse the other. If you need your own services to be refusable independently, declare them
under **different categories** (a plugin-declared category is fine; the shell just has no translated label
for it). That is the only lever the contract gives you.

**`necessary` is never asked about.** `has('necessary')` is always `true` and the host never prompts for
it — it is the category the core itself uses, and a banner-free site stays banner-free. Declaring a
service `necessary` means "this loads unconditionally"; use it only for what genuinely cannot be refused.

**Concurrent `request()` calls.** A page of plugin tiles will produce them, so the contract is explicit:
there is **one consent surface host-wide** (a second call joins the one in flight rather than opening
another); the visitor decides **all** categories in one interaction, so other categories may move too;
every call resolves exactly once and is never dropped; and grants are **shared across plugins** — another
plugin's accepted `request('analytics')` resolves yours too. Don't serialize calls or build a queue, and
re-read `has(...)` after a change instead of caching what a request resolved with.

### `consent.services[]` in `plugin.json`

The manifest type is **core-owned** — the SDK has no manifest type and will not grow one — but this is
where plugin authors look, so the required shape is documented here. **From `0.4.0` the legacy
`{ "categories": [...], "externalSources": [...] }` form is rejected** and the plugin will not load.

```json
"consent": {
  "services": [{
    "id": "plausible",
    "name": "Plausible Analytics",
    "provider": "Plausible Insights OÜ",
    "category": "necessary | functional | analytics | <plugin-declared>",
    "privacyUrl": "https://plausible.io/privacy",
    "hosts": ["https://plausible.example"],
    "thirdCountryTransfer": false,
    "storage": [
      { "name": "plausible_ignore", "type": "cookie | localStorage | sessionStorage",
        "purpose": "Remembers that you opted out of statistics", "duration": "persistent | session | 12 months" }
    ]
  }]
}
```

| Field | Meaning |
|---|---|
| `id` | Stable identifier for this service within your plugin. |
| `name` | The service as a visitor would recognise it, e.g. "Plausible Analytics". |
| `provider` | The **legal entity** operating it — the company name, not your plugin's. |
| `category` | The consent category the service falls under; what `ctx.consent.has(...)` gates on. **`necessary` is always granted and never prompted for.** Two services sharing a category are decided together. |
| `privacyUrl` | The provider's own privacy policy. |
| `hosts` | Every origin the service is contacted on. **Also the CSP allow-list** — see below. |
| `thirdCountryTransfer` | Whether personal data leaves the EU/EEA. |
| `storage[]` | Each item the service stores on the device: `name`, `type`, `purpose`, `duration`. |

**Why the shape changed.** A cookie notice that satisfies §25 TDDDG / Art. 5(3) ePD has to name each
stored item, what it is for, how long it lasts, who the provider is, and whether data leaves the country.
Category slugs and bare hostnames cannot produce that — and they force the notice to talk about "plugins"
to visitors who only care about cookies and named companies.

**`hosts` doubles as the CSP allow-list.** Core widens `script-src` / `frame-src` / `connect-src` by
exactly these origins. An origin you did not declare **stays blocked even after consent is given** — if a
third-party embed silently fails to load with consent granted, an undeclared host is the first thing to
check.

**Authoring help:** the SDK exports `ConsentServiceDeclaration` (and `ConsentStorageDeclaration`) as a
**documentation-only** type. Nothing in the SDK reads `plugin.json` or validates it — the host owns and
enforces the manifest — but typing a literal against it gives you completion and catches a typo before
core rejects the plugin at load, which is otherwise your first feedback. If the type and core ever
disagree, **core wins**; treat the mismatch as an SDK bug. The manifest as a whole stays core-owned.

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
        ctx.store().get(Scope.site(), "hello", String.class));
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
