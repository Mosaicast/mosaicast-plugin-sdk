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
rejected the moment the host runs `0.7.0` — with the reason shown in the admin log viewer. While the SDK
is pre-1.0 a breaking change is therefore a **minor** bump (`0.6.0` → `0.7.0`), not a major one; from
`1.0.0` on, normal SemVer applies and breaking means major. **The patch floats** — a plugin declaring
`0.9.0` loads on a `0.9.1` host, which is what makes a purely additive release (a new optional extension
point, a test double) cheap: it rejects nothing already installed.

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
      compileOnly("dev.mosaicast:plugin-api:0.9.1")           // contract, provided by the host
      testImplementation("dev.mosaicast:plugin-testkit:0.9.1") // test doubles only
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

**Frontend.** A Web Component reaches plugin data through `ctx.api` (`PluginApiClient`), or — for a plugin that declares a relational schema — through `ctx.schema` (see [below](#relational-storage--ctxschema-since-040-frontend-since-070)). Neither hits plugin-authored routes: both hit fixed, generic surfaces the **host** exposes, namespaced per plugin. `ctx.api` mirrors `DocStore` one-to-one — `get` / `put` / `list` / `delete`, and no more:

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

- `scopeType` is `site | feed | season | episode | user` and `scopeId` is that entity's id — mirroring `Scope`/`ScopeType`, the same addressing the backend uses. Two are **singletons** whose id the SDK and the host both pin: `site`'s is always `main` (`Scope.SITE_ID`, one site) and `user`'s always `me` (`Scope.SELF_ID`, the calling user). So `Scope.site()` and `…/data/site/main/{key}` address the same document, and the path always has four non-empty segments.
- `key` must match `DocStore.KEY_PATTERN` = `^[A-Za-z0-9._:-]{1,200}$` and is the final path segment, verbatim — no `/`, and no percent-encoded slash either (servlet containers reject `%2F` there). Structure keys with `:` / `.` / `-` instead, e.g. `mark:s2e04:b3` (entity ids are slugs, not UUIDs). The host answers 400 on a bad key, and `InMemoryDocStore` throws `IllegalArgumentException` — so it fails in your tests, not in production.
- The list is **paginated** (core's standard `PagedResponse` envelope) and **carries keys** (`DocEntry`), because neither end can address a doc without one.
- `delete` is **idempotent**: removing an absent doc is not an error. The Java call returns whether anything was actually removed.

### The typed client — `ctx.docs` (since 0.9.0)

`ctx.api` is the raw surface and stays available. `ctx.docs` is the same endpoints with the path building,
the key validation and the 404 handling done for you — which removes a whole class of bug, since every doc
access above was string concatenation the plugin had to get right four segments at a time:

```ts
await ctx.docs.put('self', `mark:${ctx.scope.id}`, marks);   // data/user/me/mark:<slug>
const board = await ctx.docs.get<Board>('site', 'leaderboard');   // null when absent, not a rejection
const page  = await ctx.docs.list<Marks>('self', { prefix: 'mark:' });
await ctx.docs.remove(ctx.scope, 'draft');                    // any Scope addresses its own partition
```

- **`'self'` is `data/user/me`** and `'site'` is `data/site/main` — the two singletons. Making the
  per-user partition the *shortest* thing to write is deliberate: it is the most security-relevant
  convention in the contract, and a convention only sticks when it is also the easy path.
- **`get` resolves `null` on 404**, because a key nothing has written yet is a normal state rather than a
  failure. The same is true of `ctx.api.getOrNull(path)`.
- **A malformed key throws at the call site**, with `DOC_KEY_PATTERN` in the message, instead of costing a
  400 round-trip whose body you then have to read.
- Everything else is unchanged and still the host's: both access floors, `backendOwned`, the 400 on an
  unknown scope, the 401 on an anonymous `user` request.

### Typed failures — `PluginApiError` (since 0.9.0)

Every `ctx.api` method rejects with an error carrying the **status** and the RFC 7807 body. Before 0.9.0 the
rejection was untyped, so the only way to survive a 404 was `catch(() => undefined)` — which also swallowed
the 500, the 403 and the network failure, and showed the visitor an empty widget for all four:

```ts
import { isPluginApiError } from '@mosaicast/plugin-sdk';

try {
  await ctx.docs.put('site', 'stats', computed);
} catch (e) {
  if (isPluginApiError(e) && e.status === 403) {
    ctx.log('warn', e.problem?.detail ?? 'refused');  // backendOwned, or the write floor
    return;
  }
  throw e;                                            // a real failure — surface it
}
```

Use `isPluginApiError`, not `instanceof`: the error is constructed by the host and crosses a bundle
boundary, so it does not share a constructor with anything in your plugin.

### Per-user data — the `user` scope (since 0.5.0)

**Per-user data belongs in the `user` scope, never in the key.** A key is client-supplied, so the convention this README used to suggest — `mark:<userId>:cell` under an episode scope — was an access-control decision the host could not enforce: any caller past the plugin's read floor could address someone else's key directly, and scope ids are public slugs, so nothing had to be guessed.

`Scope.user()` / `…/data/user/me/{key}` fixes that by construction:

- The id is always the sentinel `me` (`Scope.SELF_ID`), resolved **server-side from the session**. The canonical `Scope` constructor normalizes any `USER` id to it, so there is no expression in the Java API that names another person's partition — and there is deliberately **no `Scope.user(String)` overload**.
- Naming any other `user` id over HTTP is a **400**, never a silent substitution. An anonymous `user` request is a **401** whatever the read floor says: no session, no partition.
- The partition is **flat** — one per user, not one per user and entity — so the entity goes in the key: `mark:<episodeSlug>:cell`.
- `readableBy` does not apply to it. No floor makes someone else's partition readable.

**A backend has no calling user**, so every `DocStore` method throws `UnsupportedOperationException` for a `USER` scope — reads included, since resolving "me" without a caller would have to pick someone. Aggregate instead:

```java
// Backend-only, read-only, and no HTTP surface: no visitor's request can reach another's data.
List<OwnedDocEntry> marks = ctx.store().queryAcrossUsers("mark:");
// record OwnedDocEntry(UUID userId, String key, JsonNode value) — the owner is host-resolved, never
// a value the browser supplied, which is what makes a leaderboard built from it true.
```

Write the aggregate back to an entity scope (`…/data/episode/s2e04/leaderboard`) and let the component read it there. In tests, `InMemoryDocStore.asUser(uuid)` stands in for the host resolving `me`, so you can seed what a frontend would have written and then assert on `queryAcrossUsers`.

**The two ends see one store.** The doc a backend writes with `ctx.store().put(scope, key, value)` is exactly what the frontend reads at `GET /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}`.

**What the host enforces**, so a plugin doesn't have to:
- Data is **hard-scoped to the plugin id** — a plugin can only ever see its own data.
- **Access is what the manifest declares**, not what the slots imply: `"data": { "readableBy": "fan", "writableBy": "podcaster" }`. Values are `anonymous | fan | podcaster | admin`; `writableBy` may not be `anonymous`, and an absent block defaults `readableBy` to the **write** floor rather than to anonymous — saying nothing gets the safe answer. A slot's `visibleTo` governs **rendering only**; deriving the data floor from unrelated UI slots is what once let a plugin with one anonymous slot expose its whole store.
- **The floors say who, not which key** — see below.
- The host validates that the scope exists (and that the feed is enabled). `ctx.api` carries the user's auth (session or personal access token).

### Backend-owned keys (since 0.6.0) — a shared-scope document has no owner

Authorization on the data surface is **per plugin, not per document**. Every caller above your `writableBy` floor can overwrite or delete *any* key in a `site`/`feed`/`season`/`episode` scope — including a value your backend computed on a schedule, because the host cannot tell your write from a `curl`:

```bash
# podcaster, with a plugin declaring writableBy: "podcaster" — the floor did exactly what it says
curl -b cookie.podcaster -X PUT .../api/plugins/sample/data/site/main/stats \
     -H "X-XSRF-TOKEN: $XT" -d '{"totalEpisodes":9999}'      # 204, and every visitor now reads it
```

If your backend authors a key, **declare it**:

```json
"data": {
  "readableBy": "anonymous",
  "writableBy": "podcaster",
  "backendOwned": ["stats", "agg:*"]
}
```

- Each entry is an exact key, a prefix ending in `*`, or the bare `*` (the whole store is computed) — `DocStore.BACKEND_OWNED_PATTERN` = `^(\*|[A-Za-z0-9._:-]{1,200}\*?)$`. A `*` in the middle, or an empty entry, is rejected at load. A pattern can never be a key, since `*` is not in `KEY_PATTERN`.
- A client `PUT`/`DELETE` to a matching key is **403**, worded differently from the role-floor 403 so you can tell which rule refused you. **Reads are untouched** and still governed by `readableBy`. `ctx.store()` — the backend — is unaffected, which is the whole point.
- **Write those keys in `register()` too, not only on a schedule.** The declaration refuses *new* client writes; it does not remove a value forged before it existed, so otherwise a bogus document survives until the next tick.
- It is **ignored for `user` scopes**: the backend cannot write a partition at all, so even a bare `*` leaves those to their owner.
- `PluginDataDeclaration` in the TS SDK types this block (documentation only — the host validates it), and `InMemoryDocStore.withBackendOwned(...)` enforces it in tests, so you can prove the forged `PUT` fails without a running host.

**No request-time server logic.** A write is plain persistence — no plugin code runs on the request. Anything derived, validated or aggregated server-side is **precomputed** in `register`/`onSchedule` and read back from the store.

> Roadmap: custom plugin-defined server routes may arrive in a later `platformApi` version; plugins use the doc store.

## Relational storage — `ctx.schema` (since 0.4.0; frontend since 0.7.0)

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

### From the frontend — `ctx.schema` (since 0.7.0)

Provisioning without access was half a feature: the search box lives in the browser. `ctx.schema` is the
frontend counterpart, and it is **`null`** unless the manifest declares a schema — the same shape as the
Java call, so a doc-store plugin gets `null` rather than a client that 404s on everything.

```ts
if (!ctx.schema) return;                              // doc-store plugin: nothing to query

interface Page { id: number; slug: string; title: string; markdown: string; updatedAt: string }

const hits = await ctx.schema.search<Page>('page', 'markdown', term, {
  where: [{ field: 'published', op: 'eq', value: true }],
  orderBy: [{ field: 'updatedAt', direction: 'desc' }],
  size: 20,
});                                                   // → { items, page, size, totalElements, totalPages }

const one = await ctx.schema.find<Page>('page', 42);  // null when there is no such row
const n   = await ctx.schema.count('page');
```

| Operation | Backend (Java) | Frontend (TS) |
|---|---|---|
| rows by criteria | `schema.select(entity, criteria, T.class)` | `ctx.schema.select<T>(entity, query)` |
| full-text | `schema.search(entity, field, text, criteria, T.class)` | `ctx.schema.search<T>(entity, field, text, query)` |
| one by id | `schema.find(entity, id, T.class)` → `Optional<T>` | `ctx.schema.find<T>(entity, id)` → `T \| null` |
| how many | `schema.count(entity, criteria)` | `ctx.schema.count(entity, query)` |

- A `SchemaQuery` describes the same thing `Criteria` does — declared field names, an op from a closed
  vocabulary, values bound by the host — with `page`/`size` instead of `limit`/`offset` because it crosses
  HTTP (`page` from 0, `size` 50 by default, capped at 200).
- **Reads only.** There is no write half, deliberately: a v1 plugin authors no HTTP routes, so no plugin
  code runs at request time to enforce slug uniqueness or append a revision atomically. Your backend stays
  the only writer — a frontend that must write puts a document in the doc store and the backend ingests it
  on its schedule, which makes such a write eventually consistent.
- An undeclared entity is a 404, an undeclared field or a `search` on a field that is not `:fulltext` a
  400. Access is the same `data.readableBy` floor as the doc surface — one rule, both surfaces.

Test it with `makeMockSchema({ page: [...] })` from `/testing`, which records every query. Its `search` is
a substring match with the same caveat as `FakeSchemaStore`'s.

## Episode snapshots — `ctx.feeds` (since 0.9.0)

The Java contract could read an episode's display snapshot and the frontend could not, so a plugin that
wanted to draw an episode card copied the host's own data into its doc store and kept it fresh on a
schedule — a backend, a scheduled ingest, a `backendOwned` key and a copy that is stale between runs, for
fields the host already has.

```ts
const cards = await ctx.feeds.displayMany(ctx.episodes.slice(0, 20));   // one request, not N
for (const slug of ctx.episodes) {
  const snap = cards[slug];
  if (!snap) continue;                       // filtered out for this visitor — normal, not an error
  render(slug, snap.title, resolveArtwork(snap), i18n.duration(snap.duration ?? 0));
}

const one = await ctx.feeds.display('kraken');   // null when absent or not visible
```

- **The host filters, the plugin consumes.** A `WITHDRAWN` or tier-gated episode is **absent** rather than
  redacted, and this cannot enumerate episodes `ctx.episodes` did not already give you.
- **No `readableBy` gate of its own** — it returns host data the same visitor can read from
  `/api/episodes/*` anyway. It exists so a plugin need not know that URL shape, the same argument
  `ctx.links` makes.
- **Not authoritative.** The snapshot is overwritten on every feed refetch — that is the feature, and the
  reason to read it live. Cache per render, never per install.
- `displayMany` **clamps** at 200 slugs rather than erroring, so check what came back.

Test it with `makeMockFeeds().withDisplay(slug, snapshot)`, mirroring the Java `FakeFeedAccess.withDisplay`.

## Site-wide tags — `ctx.tags` / `ctx.tags()` (since 0.9.0)

Tags existed in core only as a feed-derived filter axis over episodes: no vocabulary, no plugin surface. So
every plugin that wanted tags grew a private free-text column, and a wiki's `lore` and an episode's `lore`
were unrelated strings that could not be suggested, linked or counted together.

Opt in from the manifest — reading and tagging your own subjects is one thing, tagging **episodes** is
another:

```json
"tags": { "readsVocabulary": true, "writesEpisodes": false }
```

`ctx.tags` (TS) and `ctx.tags()` (Java) are **`null`** without it, the same shape as `schema` and `blobs`.

```ts
const tags = ctx.tags;
if (!tags) return;

for (const t of await tags.all()) suggest(t.label, t.tag);      // the site's real vocabulary
await tags.tagSubject(`page:${slug}`, 'Maritime Lore');          // your own namespace
const also = await tags.episodesWith('maritime lore');           // what else is about this
a.href = ctx.links.feed('main', { tag: 'maritime lore' });       // and it links to the feed view
```

- **`tag` is the canonical key** (trim, collapse whitespace, casefold), applied on every path into the
  vocabulary including feed ingest; **`label` is presentation**, kept from first use. Send any spelling,
  store and compare on the key.
- **Tagging an episode is a capability**, not a convenience: it changes the shell's filter options *and*
  what core recommends beside that episode. Hence the second flag.
- **What no plugin may do:** delete a tag from the vocabulary (it is shared), rename one (a vocabulary-wide
  edit, and admin's), or remove another writer's assignment — the feed's included. Every plugin write
  carries `source = plugin:<id>`, which makes the last one enforceable rather than merely discouraged.
- A tag stops existing when nothing carries it.

`subjectKey` is opaque and yours to invent — the same namespacing `ctx.schema` has for tables. Use the same
key a `SearchProvider` hit resolves to, so a tag and a search result name one object.

Test with `makeMockTags({ writesEpisodes })` / `FakeTags`, both of which refuse what the host refuses.

## Deep links & navigation — `ctx.route` (navigate since 0.7.0)

A plugin declaring a `page` slot owns the URL subtree `/p/<pluginId>/*`. The host hands it the subpath
below that prefix as `ctx.route.path`, which is what makes plugin content linkable and shareable at all.

```ts
const slug = ctx.route.path || 'index';               // '' when not rendered as a page

link.addEventListener('click', (e) => {
  e.preventDefault();
  ctx.route.navigate(`glossary/${target}`);           // → /p/wiki/glossary/<target>
});

ctx.route.navigate('index', { replace: true });       // no back-button step (tab/filter state)
```

- `navigate` is **SPA navigation**: a history entry, a working back button, and no re-fetch of the shell or
  of any plugin bundle. An `<a href>` to your own page is a full document load, which on a densely
  cross-linked page set is the common path, not an edge case.
- `subpath` is relative to `/p/<pluginId>/`, the same coordinate `path` gives you. The host prefixes your
  namespace and drops any attempt to climb out of it, so another plugin's route or a core one is not
  blocked so much as **unnameable** — the same property `ctx.schema` has.
- Keep the real `href` on your anchors. Middle-click, "open in new tab" and crawlers need it; `navigate`
  only takes over the plain-click path.
- **Do not reach past this handle.** `history.pushState` plus a synthetic `popstate` happens to work
  against the host's current router and is not part of the contract.
- Pair it with `ShareMetadataProvider` (OpenGraph per subpath) and `SitemapProvider` on the backend so the
  URLs you navigate to also preview and index properly — and with `PageRouteProvider` (since 0.9.1) so the
  ones that do *not* exist answer 404 instead of a 200 with a not-found view in it.

### Reading the query, and matching a route (since 0.9.0)

`navigate` always accepted a `?query`, and until 0.9.0 there was no supported way to read one back —
`location.search` is exactly the "do not reach past this handle" the rule above forbids. Filters, sort
order and pagination are the obvious shareable-link state:

```ts
const sort = ctx.route.query.get('sort') ?? 'newest';   // host-parsed URLSearchParams
ctx.route.navigate(`moments?sort=${next}`, { replace: true });
```

`matchRoute` replaces the prefix matcher every page plugin hand-rolls — and the hand-rolled one is usually
a `startsWith`, which matches `moments-archive` too:

```ts
import { matchRoute } from '@mosaicast/plugin-sdk';

const match = matchRoute(ctx.route.path, ['', 'moments', 'highlight/:slug'] as const);
switch (match?.pattern) {
  case 'highlight/:slug': return renderDetail(match.params.slug);
  case 'moments':         return renderMoments();
  case '':                return renderIndex();
  default:                return renderNotFound();      // null — nothing matched
}
```

The whole path must be consumed, `:param` captures one non-empty segment (decoded), surrounding slashes and
any `?query`/`#hash` are ignored, and the **first** matching pattern wins.

### Linking to core pages — `ctx.links` (since 0.8.0)

`navigate` deliberately cannot name a core route. Producing a *link* to one is a different thing — the
visitor still clicks — and `ctx.links` gives you the host's URL shapes instead of a hardcoded string that
breaks when a route changes:

```ts
a.href = ctx.links.episode('kraken', { t: 724 });   // /episodes/kraken?t=724
a.href = ctx.links.feed('main', { season: '2' });   // /feeds/main?season=2
```

`?t=` is the host's timestamp deep link: it seeks the player to that second, and beats the listener's
stored position for that navigation without overwriting it. Frontend only — this is where links get
rendered. Strings, not navigation: put them in a real `href`.

## File storage — `ctx.blobs` (since 0.8.0)

A plugin that has to accept a file — a wiki's diagrams, a show-notes image — rather than link to somebody
else's host. **Declare it or you do not get it**, and what you declare is what an operator sees you asking
for:

```json
"blobs": { "maxFileBytes": 5242880, "quotaBytes": 268435456,
           "mimeTypes": ["image/png", "image/jpeg", "image/webp"] }
```

`ctx.blobs` is `null` without it, exactly like `ctx.schema`. From a Web Component:

```ts
const blobs = ctx.blobs;
if (!blobs) return;

const stored = await blobs.upload(file);              // a File from <input type="file">
img.src = blobs.urlFor(stored.ref);
await ctx.api.put('data/site/main/logo', { ref: stored.ref });
```

and from a backend, for what only a backend can do — fetching on a schedule, deleting what a document no
longer points at:

```java
try (InputStream in = Files.newInputStream(path)) {
    BlobInfo stored = ctx.blobs().put("architecture.png", "image/png", in);
}
```

- **Store the `ref`, never the URL.** The URL is derived and the host may change how; the ref is the
  identity.
- **Writes are the point here**, unlike `ctx.schema`. A file has no relational invariant for plugin code to
  enforce, so `data.writableBy` plus a quota is the whole authorization story and a client may upload
  directly. Reads follow `data.readableBy`.
- **Uploads are refused for reasons you can predict**: size against your effective ceiling, declared type
  against the effective allow-list, then the *actual* type read from the leading bytes. A file whose content
  contradicts its extension is refused, and SVG is never accepted — it is a script container wearing an
  image's file extension. Call `quota()` to warn someone *before* they pick a 200 MB file.
- **The operator's numbers win.** They cap both ceilings and intersect the type list with the install's own,
  so you may be granted less than you asked for; `quota()` is the only honest source.
- **Nothing collects orphans.** A file outlives the document that named it, and only your plugin knows
  which those are.
- Blobs are served same-origin under `/api/plugins/<id>/blob/<ref>`, so rendering one needs no CSP host and
  makes no consent decision — which an external image URL cannot say.

Test it with `makeMockBlobs()` from `/testing` and `InMemoryPluginBlobs` in the Java kit. Both **enforce the
ceilings and the allow-list**: a component that has only ever met an accepting double meets its first
refusal in front of a podcaster. Neither reads file formats — name the file that should be refused
(`rejectContent`) to exercise that path.

## Host icons — `iconCss` / `iconMask` (since 0.9.0)

The host publishes its icon set as `--mc-icon-*` custom properties, which inherit through the shadow
boundary — so a plugin built against **this** SDK picks up an icon the day core publishes it, with no SDK
release and no manifest bump. That is why the icon set is deliberately *not* on `ctx`, and why these
helpers take a plain `string` rather than a closed union: either would pin the set to an SDK version.

What they own is the mechanics, which are subtle enough to get wrong three ways:

```ts
import { iconCss } from '@mosaicast/plugin-sdk';

const style = document.createElement('style');
style.textContent = iconCss(['star', 'clock']);      // put it in YOUR shadow root
root.append(style);
root.insertAdjacentHTML('beforeend', '<i class="mc-icon mc-icon-star" aria-hidden="true"></i>');
```

- **Mask, never `background-image`.** `background: currentColor` behind a mask is what makes the icon
  re-theme with the text beside it.
- **Every reference needs a blank-image fallback.** An unresolved `var()` invalidates the declaration, so
  `mask-image` falls back to its initial `none` — leaving an unmasked element painting `currentColor`
  across its whole box. **A missing icon renders as a solid square, not as nothing**, and `mask-image: none`
  is the obvious fallback and the one that causes it. `iconMask` emits a real blank SVG instead.
- **A bundled `.css` file lands in the host document and cannot reach any shadow root.** It is the first
  thing everyone tries and it silently does nothing.

Don't declare into `--mc-*` yourself — that is the host's namespace.

## Formatting — `createPluginI18n` (plurals & units since 0.9.0)

`t(key, params)` interpolates; the rest formats against `ctx.locale`:

```ts
const i18n = createPluginI18n(catalogs, ctx.locale);

i18n.plural('moments', n);                 // catalog keys: moments.one / moments.other / moments.few …
i18n.n(1234.5);                            // 1.234,5 in de
i18n.date(snapshot.publishedAt!);          // takes the ISO instants the contract hands over
i18n.duration(snapshot.duration!);         // 'PT1H2M3S' → 1:02:03; also takes plain seconds
i18n.bytes(quota.usedBytes);               // decimal units, locale separator — 5,2 MB in de
```

- **`plural` uses `Intl.PluralRules`.** A catalog could not express "1 highlight" / "5 highlights" at all,
  so plugins shipped an English-shaped `if (n === 1)` — wrong in Polish, Russian and Arabic. Only the
  `.other` form is required; a locale needing one you did not write falls back to it. `count` is
  interpolated for you.
- **`duration` and `bytes` are contract-adjacent**: `DisplaySnapshot.duration` *is* an ISO-8601 string and
  `BlobQuota` *is* three raw byte counts. The SDK produces both, so it may as well render them — and the
  hand-rolled byte formatter hardcodes `.` as the decimal separator, which is simply wrong in `de`.

## Which languages the site has — `ctx.locale.available()` / `.content()` (since 0.10.0)

`ctx.locale` used to answer one question: what language the UI is in right now. It now also answers what
languages this instance *has* — because an operator can drop a catalog into `MOSAICAST_LOCALES_DIR` and
enable it, and a plugin that hardcoded a language list would be wrong the moment they did.

```ts
ctx.locale.current();      // 'de' — the active UI language, as before
ctx.locale.available();    // [{ code, nativeName, isDefault }] — what the shell can render in
ctx.locale.content();      // …what the admin permits content to be *authored* in
```

**They are different lists, and the difference is the whole point.** A language needs a catalog to be
offered in the shell and needs nothing at all to be one an imprint is written in — so a site can require a
Dutch imprint with an English-only UI. **An editor wants `content()`.** A plugin that built its authoring
tabs from `available()` would silently refuse the language its operator actually asked for.

Your own catalogs and `content()` will routinely disagree, and that is fine: your UI falls back to English
while the *content* is written in Dutch. Do not assume they line up.

On the backend the same thing is `ctx.locales()` — never `null`, because every site has at least one
language — and it is where a per-locale write should be checked:

```java
if (!ctx.locales().isContentLocale(locale)) {
    throw new IllegalArgumentException("not a content language: " + locale);
}
```

The browser's list is a hint. What arrives at your storage is input, and a row stored under a language
nobody offers is invisible to every reader and to your own editor's tab strip.

## Machine translation — `ctx.translation` (since 0.10.0)

```ts
if (ctx.translation?.available()) {
  const { text, providerId } = await ctx.translation.translate({ text: body, to: 'nl' });
}
```

```java
Translation translation = ctx.translation();
if (translation != null && translation.available()) {
    TranslationResult result = translation.translate(TranslationRequest.of(body, "nl"));
}
```

**`null` when the site admin configured no provider** — which is every site until somebody chooses one.
Same shape as `schema`/`blobs`/`tags`, different decision: those are gated by *your manifest*, this by the
*operator's* choice, and it can appear or disappear while your plugin is running. Do not cache the handle.

**The host owns the provider, the credentials and the cache.** You never call a translation service
directly. That spares operators having to trust every plugin with an API key, and it means two plugins
translating the same paragraph cost one call.

**Machine output is a draft. Store it marked as one.** Core's own legal-page prefill hands the admin an
unsaved draft for exactly this reason. A translation nobody has read is not made safer by being automatic.

Three things that will bite:

- **Markdown is not a format.** Send `'text'` or `'html'`; markdown sent as text comes back with mangled
  links and code fences, because a translator does not know they are markup. Splitting markdown into
  translatable blocks is your job, and a real one.
- **Calls are slow, metered and refusable.** Do it on `ctx.onSchedule(...)` or behind an explicit editor
  action, never on a path a visitor waits behind. `TranslationException` is checked, and carries a
  `reason()` plus `retryable()` so you can retry a `BUSY` and give up on a `NO_PROVIDER`.
- **Do not fall back to the untranslated string.** A reader who cannot tell a translation from an original
  is worse off than one who sees an error.

Test doubles: `makeMockTranslation()` (TS) and `FakeTranslation.marking()` / `.failing(reason)` plus
`FakeLocales` (Java). Both *mark* the text — `"[nl] Hello"` — rather than faking Dutch, so an assertion
pins that you asked for the right target language.

## The manifest, typed — `defineManifest` (since 0.9.0)

```ts
import { defineManifest, PLATFORM_API_VERSION } from '@mosaicast/plugin-sdk';

export default defineManifest({
  id: 'sample', version: '1.0.0', platformApi: PLATFORM_API_VERSION, name: 'Sample',
  frontend: { entry: 'sample.es.js', elements: ['sample-card'] },
  slots: [{ scope: 'episode', element: 'sample-card', placement: 'main', visibleTo: 'anonymous' }],
  nav: [{ path: '', label: 'Sample', icon: 'star' }],
  data: { writableBy: 'podcaster', readableBy: 'anonymous' },
});
```

**Documentation, not enforcement** — the same caveat `PluginDataDeclaration` and
`ConsentServiceDeclaration` have carried since 0.4.0. The manifest is owned and validated by the **host**;
the SDK never reads `plugin.json`, and if this type and core disagree, **core wins**.

What it buys you: emit `plugin.json` from a build step and the manifest stops being a second, unchecked
copy of what your code already knows. `nav[]` and a plugin's own in-page tab bar are the same list of
entrances declared twice, and a renamed path otherwise becomes a menu entry leading to an empty view with
nothing to catch it. Note where the two *legitimately* diverge: `path`, `icon` and `role` are pinned
between them, but the menu **label cannot be translated** — core has no plugin catalogs — while your
in-page tab can.

## Optional extension points — `SearchProvider`, `UserDataHandler`, `PageRouteProvider`

All three are `ExtensionPoint`s a plugin MAY implement alongside `PluginBackend`, like `SitemapProvider`. No
manifest declaration: not implementing one is how a plugin says it has nothing to contribute.

**`SearchProvider`** puts plugin content into the site's search, instead of each plugin growing a private
search box a visitor has no way to discover:

```java
public List<SearchHit> search(String query, Role role, int limit) {
    return pages.matching(query, role).stream()
            .map(p -> new SearchHit("glossary/" + p.slug(), p.title(), p.excerpt(), p.rank()))
            .toList();
}
```

`subpath` resolves to `/p/<pluginId>/<subpath>`, exactly as `ShareMetadataProvider.metaFor` works in
reverse, so a hit is a real deep-linkable URL and the host keeps the URL shape. Results are **grouped by
source**, not merged — your `score` and Postgres `ts_rank` are not on one scale — and a slow provider costs
its own section, not the visitor's query.

**Access is your job here, unusually.** Everywhere else the host resolves access; it cannot for objects it
has no model of. A provider returning a draft page to an anonymous visitor is a leak the host will not
catch, so `role` is `null` for anonymous and `SearchProviderHarness` calls you once per role:

```java
var results = new SearchProviderHarness(new WikiSearch(store)).search("kraken");
assertFalse(results.leakedToAnonymous("draft/lighthouse"));
```

**`UserDataHandler`** is what §12's promise needs to be keepable — core cannot pseudonymise a plugin's
contributions, cannot find them, and cannot know that pseudonymising is right rather than deleting:

```java
public void eraseUser(String userId) { revisions.anonymise(userId); }   // or a hard delete — your call
```

The `USER` scope is host-owned, so core drops `data/user/<id>/…` itself. Implement this for the hard half:
**schema columns and blobs**, where identity lives in a column a plugin chose and the host never learned
which one is a person. Handlers run *before* the account row goes, and **must be idempotent** — a failed
deletion is retried. `UserDataHandlerHarness.eraseTwice(userId)` is that test, and it is the one authors
skip: the second call is the one that throws, during a retry, when the alternative is a half-done deletion.

**`PageRouteProvider`** (since 0.9.1) is how a plugin's unknown subpaths become real 404s. Declare a `page`
slot and *every* subpath under `/p/<id>/` answers `200` — a page never written, a mistyped slug, the URL of
a page deleted last year — each rendering your not-found view inside a `200 OK`. §6.6 rules that soft-404
out for core's own routes, and the host cannot fix it alone: only the plugin knows whether a subpath is a
thing.

```java
public boolean hasRoute(String subpath) {
    return subpath.isEmpty() || subpath.startsWith("_search/") || pages.exists(slugOf(subpath));
}
```

**Not `ShareMetadataProvider`** — the tempting shortcut, and wrong: `_search/<term>` and `_admin` return an
empty `metaFor` *on purpose*, so reading "no share metadata" as "no page" would 404 working routes. Absent
means today's behaviour, the **root is a route** (`subpath` is empty there), it runs on a request so keep it
a lookup, and a provider that throws is logged and skipped and the route answers `200` — a broken plugin
must not turn a working page into a 404. It decides the status line only; the shell still renders the
not-found view. `PageRouteProviderHarness` asks about a handful of subpaths at once, root included:

```java
var routes = new PageRouteProviderHarness(new WikiRoutes(store)).check("glossary/kraken", "glossary/tpyo");
assertEquals(List.of("glossary/tpyo"), routes.notFound());
```

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

**Awaiting a component's fetches** (`flushMockApi`, since 0.9.0). The mock resolves its promise before the
component's `.then(setState)` runs, so `await Promise.resolve()` covers one hop and not two — which is why
the symptom is an assertion that fails *only sometimes*, depending on how many hops the component happens
to have:

```ts
mount(ctx);
await flushMockApi(ctx.api);            // waits for the calls, then drains the hops behind them
expect(root.querySelector('.title')?.textContent).toBe('The Kraken');
```

**Driving a failure.** `apiError(status, problem)` as a canned response makes the client reject with the
host's error shape, so a component's 404 branch and its 500 branch are separately testable:

```ts
const ctx = makeMockCtx({ apiResponses: { 'get data/site/main/stats': apiError(403, { detail: 'backendOwned' }) } });
```

**The doubles refuse what the host refuses.** `makeMockTags()` throws on `tagEpisode` unless you pass
`{ writesEpisodes: true }`, and its `untagEpisode` leaves a `withFeedTag` assignment alone. `makeMockBlobs`
enforces the ceilings and the allow-list. A component that has only ever met a permissive double meets its
first refusal in front of a podcaster.

**The extension-point harnesses** (Java): `SearchProviderHarness` calls a provider once per role including
anonymous; `UserDataHandlerHarness.eraseTwice(userId)` proves erasure survives the host's retry;
`PageRouteProviderHarness.check(...)` answers which subpaths 404, always probing the plugin root. All three
are shown under
[Optional extension points](#optional-extension-points--searchprovider-userdatahandler-pagerouteprovider).

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
