# Changelog

All notable changes to `mosaicast-plugin-sdk` are documented here. The Java `plugin-api` /
`plugin-testkit` artifacts and the `@mosaicast/plugin-sdk` npm package share one SemVer anchor and are
released together (see the "Releasing" section in the README).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.8.0] — 2026-08-18

A plugin could declare relational tables, publish documents and serve a deep-linked page — and could not
store a **file**. The host's `BlobStore` had existed since branding shipped and had exactly one caller, so
the contract had no way to reach it. That produced an odd asymmetry: the host's CSP lets a plugin display
an image from *any* host on the web, and gave it no way to accept one from the site's own podcaster. The
honest answer to "I drew a diagram" was "find an image host first", which is not an answer.

The second gap is smaller and was found beside it. `ctx.route.navigate` is confined to a plugin's own
`/p/<pluginId>/` subtree by construction — a property worth keeping — so a plugin that wanted to write
"discussed in *The Kraken*, from 12:04" hardcoded `` `/episodes/${slug}` `` and became a thing that breaks
when the host changes a route.

**Nothing existing changes behaviour, and no test double breaks.** Both additions are new surface; the
`FakePluginContext` constructors every plugin test already calls still compile and still mean the same
thing. That is deliberate — 0.7.1 exists solely because 0.7.0 added a *required* member to a `ctx`
sub-object. New members here are top-level and nullable, and new constructor parameters arrive as
overloads. See [MIGRATION.md](MIGRATION.md); for most plugins it is a version bump and nothing else.

### Added

- **File storage: `ctx.blobs` (TypeScript) and `PluginContext.blobs()` (Java).** Opt-in through a new
  manifest `blobs` block declaring a per-file ceiling, a total quota and the accepted MIME types; both
  accessors are **`null`** without it, the same shape and the same reasoning as `ctx.schema`. What a plugin
  declares is what an operator sees it asking for, and the operator's numbers win — they cap both ceilings
  and intersect the type list with the install's own, so `quota()` is the only honest source for what was
  actually granted.
  - **Writes are the point here, unlike the schema surface.** The argument against schema writes over HTTP
    is that no plugin code runs at request time to enforce a relational invariant. A file has none, so the
    `data.writableBy` floor plus a quota is the whole authorization story and a plugin's own editing UI can
    upload directly. Reads follow `data.readableBy` — one floor pair, now three surfaces.
  - **A `ref` is the identity; a URL is derived from it.** Store the ref. The host is entitled to change how
    URLs are shaped and is not entitled to invalidate what a plugin wrote down.
  - Uploads are refused for reasons a plugin can predict and should surface: size, then the declared type
    against the allow-list, then the *actual* type read from the leading bytes. SVG is never accepted — it
    is a script container wearing an image's file extension, the same call branding uploads made.
  - **Nothing collects orphans.** A file outlives the document that named it and only the plugin knows
    which those are. The Java half exists largely for that: cleaning up on a schedule is a thing only a
    backend can do.
- **Test doubles that refuse what the host refuses**: `makeMockBlobs()` in `@mosaicast/plugin-sdk/testing`
  and `InMemoryPluginBlobs` in the Java kit, both enforcing the ceilings and the allow-list rather than
  accepting everything. A component that has only ever met an accepting double meets its first refusal in
  front of a podcaster. Neither reads file formats — that would be a second, diverging copy of a security
  rule — so `rejectContent(filename)` stands in for the host's content check.
- **`ctx.links`** — `episode(slug, { t })` and `feed(slug, { season, tag, order })`, returning root-relative
  URLs. **Strings, not navigation**: the visitor still clicks, and a real `href` is what middle-click, "open
  in new tab" and crawlers need. It grants no new capability — a plugin could always write any `href` — it
  moves knowledge of the host's URL shapes back to the host. `?t=` is core's timestamp deep link, which
  seeks the player and beats the listener's stored position without overwriting it.

### Changed

- `FakePluginContext` gained a five-argument constructor taking a `PluginBlobs`. The four-argument form is
  untouched and still means "no file storage".

## [0.7.1] — 2026-08-17

Test kit only. **No contract change** — `platformApi` still matches on `major.minor`, so a plugin declaring
`0.7.0` keeps loading and nothing needs re-declaring.

`0.7.0` added a required `navigate` to `PluginRoute`, which made `makeMockCtx`'s wholesale `route` override
worse than it looks: a test that only wanted to pin a subpath — the common case, and most of a `page`
plugin's tests — suddenly had to spell out `onChange` and `navigate` too, and silently lost the
`navigations` recorder by supplying its own. The reference plugin hit it on all four of its overrides, and
only `tsc --noEmit` caught it.

### Changed

- **`makeMockCtx` merges a `route` override over the default** instead of replacing it, so
  `route: { path: 'kraken' }` is enough and `navigate` keeps recording. Replacing `navigate` explicitly
  still works and still opts out of the recorder — the one case where `navigations` stays empty. Every
  other member is unchanged and remains all-or-nothing: each is a single behaviour, where `route` is a
  value plus the handles around it.

### Documentation

- `MIGRATION.md` no longer claims the 0.7.0 upgrade is the version string and nothing else. A hand-built
  `route` override is a compile break, and the guide now shows the diff — plus the note that a test runner
  will not catch it.

## [0.7.0] — 2026-08-17

The schema provider gets a frontend, and a page plugin gets a way to move.

Core 0.6.6 shipped the declarative schema provider: a manifest declares entities, the platform provisions
namespaced tables, and the plugin's Java backend reads them through `SchemaStore`. The **access half was
missing**. There was no HTTP surface, so a plugin's Web Component could not read a single row out of the
tables the platform had provisioned for it — and the search box, the one place a user types a query, lives
in the browser. The capability had no consumer that could ship.

The second gap is smaller and sits next to it. `ctx.route` was `{ path, onChange }`: a plugin was told
where it is and when that changes, and had no supported way to go anywhere. A `page`-placement plugin owns
`/p/<pluginId>/*` and is expected to navigate inside it, so its only option was an `<a href>` — a full
document load that re-fetches the shell, the registry and every plugin bundle per internal link. The
unsupported alternative (`history.pushState` plus a synthetic `popstate`) *works* against the host's current
router, which is precisely why it needed replacing before it became a convention.

**Every plugin declaring `0.6.x` is rejected** the moment core runs `0.7.0` — `platformApi` matches
`major.minor` exactly. Both additions are additive, so no plugin *code* changes; the one compile break is a
test that hand-builds a `route` override, since `PluginRoute` gained a required `navigate` (0.7.1 softened
that — see above). See `MIGRATION.md`.

### Added

- **`ctx.schema`** — `SchemaClient | null`, the frontend counterpart of the Java `ctx.schema()`, and `null`
  for the same reason: a plugin without `storage.schema` has no tables, and the manifest is the one place
  that is decided. `select` / `search` / `find` / `count`, mirroring `SchemaStore` and describing queries
  with the same vocabulary as `Criteria` (`SchemaQuery`, `SchemaPredicate`, `SchemaOp`). Paging is
  `page`/`size` in the doc surface's envelope (`SchemaPage`), because this crosses HTTP.
- **`ctx.route.navigate(subpath, { replace })`** — SPA navigation inside the plugin's own subtree
  (`PluginRoute`). `subpath` is the same coordinate `path` hands in; the host prefixes the namespace and
  drops any attempt to climb out of it, so a plugin still cannot name another plugin's route or a core one.
  Real history entry, working back button, no bundle reload.
- **Test kit: `makeMockSchema(rows)`** — a `SchemaClient` double answering from plain arrays, recording
  every query, and rejecting an entity the plugin never declared the way the host 404s one. Its `search` is
  a case-insensitive substring match, **not** Postgres full-text search: it cannot reproduce stemming or
  `ts_rank` ordering, and a test asserting a ranking it invented would prove nothing.
- **Test kit: `ctx.navigations`** — every `navigate` call, recorded like `logs`. `makeMockCtx` defaults
  `schema` to `null`, on the same argument as `episodeLabels` being absent: a component written against a
  schema that is always there never handles the doc-store case.

### Changed

- **Reads only, deliberately.** The schema surface exposes no writes. A v1 plugin authors no HTTP routes,
  so there is no request-time hook where plugin code could enforce slug uniqueness, append a revision
  atomically or reject malformed input — exposing writes would hand clients direct row access with no
  plugin code in the path, which is worse than the doc store's position, not better. The backend stays the
  only writer of relational truth; a frontend that must write puts a document in the doc store and the
  backend ingests it on its schedule. The cost is that such a write is eventually consistent, which is a
  consequence of the v1 contract rather than of this release.

### Documentation

- `PluginApiClient` no longer implies it is the only host surface — it is the doc store's, and `ctx.schema`
  is the other one, with its own paths and its own rules.

## [0.6.0] — 2026-08-09

The second half of the ownership story. `USER` scope (0.5.0) closed one visitor reaching another's data;
a re-audit of core showed the shared scopes still have **no ownership binding at all**. Authorization
there is per *plugin*, not per *document*, so any caller clearing a plugin's `writableBy` floor can
overwrite or delete any key — including a value the plugin's backend computed. The auditor demonstrated it:
a podcaster `PUT` a forged `site/main/stats` over HTTP and it was served to every visitor, then deleted it.

The plugin was not misconfigured — it declared `readableBy: anonymous`, `writableBy: podcaster`, and the
floors did exactly what they say. **There was simply no way to express "this key is the backend's".** Now
there is.

**Every plugin declaring `0.5.x` is rejected** the moment core runs `0.6.0`. That is the point of the minor
bump rather than a patch, small as the code delta is: `platformApi` matches `major.minor` exactly, so a
plugin declaring `backendOwned` against an older host would load happily and get **no enforcement** — a
security declaration silently ignored. A plugin that needs this protection has to be able to require a host
that provides it. (This reasoning is specific to a declaration the host must honour; it is not a standing
licence for the next small release to bump minor.)

Note the version numbers: this is SDK `0.6.0`, unrelated to core's own `0.6.0` — core's app version tracks
its milestones and the two schemes are independent by design.

### Added

- **`data.backendOwned` in the manifest** — the keys a plugin's backend authors. Clients may read them
  (subject to `readableBy`) and may not `PUT` or `DELETE` them; the host answers 403, worded differently
  from the role-floor 403 so an author can tell which rule refused them. `ctx.store()` is unaffected: the
  backend keeps writing, which is the entire point. Enforcement is host-side on the HTTP surface — **the
  `DocStore` interface does not change**.
- **`DocStore.BACKEND_OWNED_PATTERN`** = `^(\*|[A-Za-z0-9._:-]{1,200}\*?)$`. An exact key, a
  `KEY_PATTERN`-legal prefix with a single trailing `*`, or the bare `*` meaning "the whole store is
  computed". No `*` in the middle, no empty entry, case-sensitive. A pattern can never be mistaken for a
  key, because `*` is not in `KEY_PATTERN`, and capping the prefix at the same 200 characters keeps a
  pattern from out-ranging any key it could match. The constant exists so the SDK and core agree on the
  grammar by construction rather than by hand.
- **TypeScript `PluginDataDeclaration`** (and `DataAccessRole`) — the whole `data` block, typed.
  Documentation only, on the same terms as `ConsentServiceDeclaration`: the host owns and validates the
  manifest. It is typed because the block now carries a security control, and a typo in `backendOwned`
  protects nothing while saying nothing.
- **Test kit: `InMemoryDocStore.withBackendOwned(String...)`.** A write through an `asUser(...)` view — the
  stand-in for a client request — is refused with `IllegalStateException` where the host answers 403, while
  this store's own writes go through. So a plugin can prove in its own tests that the key its backend
  computes is not forgeable over HTTP. Malformed patterns throw on declaration rather than being discovered
  when core rejects the manifest at load.

### Documentation

- **`DocStore`'s javadoc now says the thing it never said:** a shared-scope document has no owner, anything
  above `writableBy` can overwrite or delete it, and if your backend authors a key you must declare it. The
  auditor's remark — that this belongs in the SDK where authors read it, not only in the host controller's
  javadoc — was fair.
- Two consequences documented everywhere the feature is: declaring a key `backendOwned` does **not** remove
  a value a client wrote before the declaration existed (so write computed keys in `register()` as well as
  on a schedule, or a forged document survives until the next tick), and the declaration is **ignored for
  `USER` scopes**, where even a bare `*` leaves a partition to its owner because the backend cannot write
  one at all.
- README gains a "Backend-owned keys" section carrying the audit's own `curl` as the motivating example.

### Not in this release

- **Per-feed ownership.** The audit also frames this as cross-tenant tampering — a podcaster editing
  another podcaster's feed. True, but `PODCASTER` is a global role and feeds have no owner, so that is a
  product decision about multi-tenancy, not a doc-store contract change.
- **Per-scope-type declarations** (site-scope `stats` owned, episode-scope `stats` not). More contract
  surface than the problem justifies: name the keys differently instead (`agg:stats` vs `stats`). If one
  key name means two things in two scopes, that is a modelling problem the contract should not absorb.

## [0.5.0] — 2026-08-08

A security cut. A white-box audit of core found that the plugin doc store had no notion of *whose* a
document is: `scopeType`, `scopeId` and `key` are all client input, the only gate was a per-plugin role
floor, and scope ids are public slugs — so any caller above that floor could read, overwrite or delete
any key in any scope, including another user's. Nothing had to be guessed.

**The advice in the contract was part of the bug.** ARCHITECTURE §7.6 and this SDK's `DocStore` javadoc
told authors to model per-user data as per-user *keys* (`mark:<userId>:cell`). A key is client-supplied,
so that is an access-control decision placed exactly where the host cannot check it. The fix is a scope
the host owns, not better advice.

Every plugin declaring `0.4.x` is rejected the moment core runs `0.5.0` — it compares `major.minor`
exactly. See [`MIGRATION.md`](MIGRATION.md); the code change is small, but **moving existing per-user data
is not automatic** and is the part to read.

### Added

- **`ScopeType.USER` and `Scope.user()` — the calling user's own private partition.** Its id is always the
  sentinel `Scope.SELF_ID` (`"me"`), and the canonical `Scope` constructor normalizes any `USER` id to it,
  exactly as it already did for `SITE_ID`. There is deliberately **no `Scope.user(String)` overload**: a
  plugin has no business naming a user, and a compile error is a better answer than an argument that would
  be ignored. Over HTTP the scope is `…/data/user/me/{key}`, resolved server-side from the session; any
  other `user` id is a **400** (never a silent substitution) and an anonymous `user` request is a **401**.
  The partition is flat, so the entity goes in the key: `mark:<episodeSlug>:cell`.
- **`DocStore.queryAcrossUsers(String keyPrefix)` → `List<OwnedDocEntry>`**, and the `OwnedDocEntry(UUID
  userId, String key, JsonNode value)` record. Backend-only and read-only, with no HTTP surface: it is how
  a plugin builds a leaderboard, a moderation view or a nightly rollup now that per-user data is not
  addressable by key. The owner id is host-resolved from the partition the document lives in, never a
  value the browser supplied — which is what makes an aggregate built from it true. The alternative,
  having each client report its own summary into a shared scope, would be a leaderboard of whatever users
  typed.
- **TypeScript `DataScopeType` and `SELF_SCOPE_ID`.** `DataScopeType` is `Scope['type'] | 'user'` — a
  second type on purpose: `user` addresses storage and is **not** a slot scope, so it appears in a
  `data/{scopeType}/{scopeId}/…` path and never in `ctx.scope`. There is no user page for a slot to mount
  on, and `makeMockCtx`'s `scope` keeps its four values. `queryAcrossUsers` has no TS counterpart at all —
  not as a method that 403s, not as a method.
- **Test kit: `InMemoryDocStore.asUser(UUID)` and `docsOf(UUID)`.** `asUser` stands in for the host
  resolving `me` from a session, so a test can seed what a frontend would have written and then assert on
  `queryAcrossUsers`. It has no counterpart in the contract — no production `DocStore` can write into a
  user's partition. `FakePluginContext.store()` narrows its return type to `InMemoryDocStore` (as
  `logger()` already did) so this needs no cast.

### Changed — **BREAKING**

- **`ScopeType` gained a constant.** An exhaustive `switch` over `ScopeType` compiled against `0.4.0` no
  longer covers every case. **Migration:** add a `USER` branch, or a `default`.
- **`DocStore` gained `queryAcrossUsers`.** Breaks only implementors of the interface — a hand-rolled
  `DocStore` in a test. **Migration:** use `InMemoryDocStore` instead, or implement the method.
- **A `USER` scope throws `UnsupportedOperationException` on every `DocStore` method**, reads included: a
  backend thread has no calling user, and resolving "me" without a caller would have to pick someone. Not
  `IllegalArgumentException` — the argument is fine, the operation has no meaning there.
- **`Scope.site(String)` is gone**, deprecated `forRemoval` since 0.3.0. **Migration:** `Scope.site()`.
- **`FeedAccess.episodesIn(Scope.user())` returns no episodes** (and `FakeFeedAccess` mirrors it): no
  episode belongs to a person. Resolve the page's own scope instead.
- **Manifest: the data surface declares its own access floor.** A new `"data": { "readableBy": …,
  "writableBy": … }` block, validated at load, replaces the old behaviour of deriving the read floor from
  the *minimum* `visibleTo` across all slots — under which a plugin with one anonymous display slot and one
  admin-only slot exposed its entire doc store anonymously. Values are `anonymous | fan | podcaster |
  admin`; `writableBy` may not be `anonymous`; an **absent block defaults `readableBy` to the write floor**,
  not to anonymous, so a plugin that says nothing gets the safe answer rather than inheriting the old
  behaviour as a default. **Migration:** a plugin with a `visibleTo: "anonymous"` slot and no `data` block
  loses its anonymous reads — they answer 403 until it declares `"readableBy": "anonymous"`. Intended, but
  it is a behaviour change and it is the one most likely to be met in production; `MIGRATION.md` step 2
  covers it. Slot `visibleTo` now governs **rendering only** — it never governed data, which
  is precisely the confusion that produced the finding. **Neither floor applies to the `USER` scope** —
  `readableBy` in neither direction, and `writableBy` not at all, since a write floor protects the shared
  surface and a user partition is unshared; gating it would force any plugin with a per-user feature to
  declare `writableBy: "fan"` and open its shared scopes with it.

### Documentation

- The `DocStore`, `Scope`, `ScopeType`, `PluginContext.store()`, `FeedAccess` javadoc and the
  `PluginApiClient` TSDoc now say where per-user data goes and why, instead of recommending per-user keys.
  Every `mark:userId:cell` example is replaced with `mark:<episodeSlug>:cell` — entity scope ids are slugs,
  not UUIDs, so a UUID example taught the wrong shape too.
- `MIGRATION.md` is rewritten for 0.5.0, including **both halves of the data move**: a backend can read
  legacy per-user keys under entity scopes, but it cannot write into anyone's partition, so the write half
  is necessarily client-side and lazy. Existing per-user data stays exactly as exposed as it is today until
  a plugin moves it — the host cannot know the key convention that put it there, so there is no automatic
  migration.

## [0.4.0] — 2026-07-28

Five needs had queued up behind one unavoidable break, so they ship as a single cut rather than three
contract bumps: the host's move to Jackson 3, a `SchemaStore` that can actually be used, plugin logging,
consent a plugin can *ask for* instead of only read, and the manifest's consent schema.

**Two of the five actually force the bump** — the Jackson 3 change to `DocEntry.value()`, and the manifest
consent schema replacement. `SchemaStore`'s query surface, `ctx.logger()`/`ctx.log()`, `consent.request()`
and `episodeLabels` are **additions that rode along**; only the `onChange` return types make the consent
work breaking at all, and only for callers who implement `PluginContext` themselves. Bundling them is
cheap here because breaking is a *minor* bump pre-1.0 and plugin authors act once instead of three times —
but "we're breaking anyway" is not a standing licence, and this note exists so the next release has to
make the case again rather than inherit it.

**Every plugin declaring `0.3.x` is rejected** the moment core runs `0.4.0` — it compares `major.minor`
exactly. That is expected, and rejections are now visible in core's admin log viewer with their reason.
Plugin authors: see [`MIGRATION.md`](MIGRATION.md), which is ordered so each step compiles on its own.

### Changed — **BREAKING** (the contract now uses Jackson 3)

- `DocEntry.value()` is `tools.jackson.databind.JsonNode`, and `plugin-api` depends on
  `tools.jackson.core:jackson-databind:3.1.4` instead of `com.fasterxml.jackson.core:jackson-databind`.
  `InMemoryDocStore(ObjectMapper)` moves with it. **Migration:** change the import; replace
  `new ObjectMapper()` with `JsonMapper.builder().build()` (mappers are immutable in 3.x); drop
  `throws JsonProcessingException` clauses, since `JacksonException` is now unchecked; replace the
  deprecated `JsonNode.asText()` with `stringValue()`. A plugin that only uses
  `store().get(scope, key, T.class)` and `config().get(...)` changes nothing — neither exposes a Jackson
  type.

  **Why Jackson 3 rather than a compatibility shim.** Core must move to Spring Boot 4.1 (3.4 and 3.5 are
  both past OSS support), and Boot 4 defaults to Jackson 3. Three options were on the table:

  1. *Move the contract to Jackson 3* — chosen.
  2. *Keep Jackson 2 in the contract, convert at the PF4J boundary.* Rejected. Plugins compile
     `compileOnly` against `plugin-api` and load **parent-first in-process**, so at runtime a plugin gets
     whatever `JsonNode` the host loaded. Compiling against Jackson 2 while the host runs Jackson 3 does
     not shrink the blast radius — it turns a compile error into a runtime `NoClassDefFoundError` in
     someone else's plugin. Making it real means core keeps `spring-boot-jackson2` and databind 2.21.4 on
     the plugin classloader; Boot 4.1 does ship that compat module, so it is possible, but it is a
     migration aid rather than a destination, and ARCHITECTURE §7.1 already names PF4J classloading
     inside a Boot fat JAR as *the* known rough edge of this system. Two databind stacks and two
     `ObjectMapper` configurations behind one contract is the wrong place to spend that budget.
  3. *Drop Jackson from the contract entirely* (a string, or an SDK-owned tree type). Right in principle,
     **deliberately deferred**: a string is re-parsed with the host's Jackson anyway, so the dependency
     would be hidden rather than removed, and an SDK-owned JSON tree is a maintenance burden forever.
     Meanwhile the escape hatch is documented — `DocStore.get`, `PluginConfig.get` and the new
     `SchemaStore` all deserialize into your own types and never expose a node, so only `DocStore.query`
     hands you one. **This is a compromise with an end date, not a settled design:** naming the host's
     JSON library in the contract means a future change of that library forces another break on plugins
     for a reason unrelated to plugin code. Removing it is tracked in
     [#28](https://github.com/Mosaicast/mosaicast-plugin-sdk/issues/28), milestone **1.0.0** — it has to be
     resolved before the contract stabilises, since afterwards the same fix costs a major bump.

  `0.4.0` is a hard break regardless, so the cost of bundling this here is one import line per file that
  touches `DocEntry.value()`. Deferring it means paying the same cost again at `0.5.0`. The decision was
  communicated to core before release in Mosaicast/mosaicast-core#45.

### Added — `SchemaStore` has a usable surface

`SchemaStore` exposed `namespace()` and nothing else, so a plugin granted namespaced tables had no way to
read or write them — which is why core still rejected `storage: "schema"` outright. ARCHITECTURE §7.6 has
declared the feature since v1 with nothing behind it. It now has:

- `entities()`, `find(entity, id, type)`, `select(entity, criteria, type)`,
  `search(entity, field, text, criteria, type)`, `count(entity, criteria)`, `insert(entity, values)`,
  `update(entity, id, values)`, `delete(entity, criteria)`.
- `Criteria` — an immutable builder: `where`/`and` over `EQ NE LT LTE GT GTE IN LIKE IS_NULL IS_NOT_NULL`,
  plus `orderBy`, `limit`, `offset`.
- `FakeSchemaStore` in the test kit, enforcing the same declaration the host does.

Everything is addressed by **declared entity and field name — never SQL, never a table name**. That is the
scoping guarantee: reaching another plugin's tables or core's is not blocked, it is inexpressible. The
host resolves the entity to its provisioned table, validates field names against the manifest, and binds
every value as a JDBC parameter — implementable over plain JDBC, no `DataSource` handed out. `search`
is on the interface deliberately: full-text is *the* reason a plugin declares a schema, and omitting it
would have shipped another unusable feature.

**Predicates combine with AND only.** A flat list mixing AND and OR is ambiguous, nothing the platform
provisions needs disjunction today, and it can be added compatibly later. `FakeSchemaStore.search` is a
substring match, not Postgres FTS — no stemming, no ranking; assert on which rows come back, not their
order.

### Added — plugin logging

- Java: `PluginContext.logger()` returns an `org.slf4j.Logger` the host has already named
  `plugin.<pluginId>`; `slf4j-api:2.0.18` becomes an `api` dependency of `plugin-api` (precedent:
  jackson-databind). Plugins keep it *provided* and resolve to the host's copy.
- TypeScript: `ctx.log(level, message)` with `LogLevel = 'debug' | 'info' | 'warn' | 'error'`, so frontend
  components stop POSTing to `/api/plugins/{id}/log` through raw `ctx.api`.
- Test kit: `RecordingLogger` (formatted messages, throwable captured separately) behind a covariant
  `FakePluginContext.logger()`; `makeMockCtx` collects `ctx.logs`.

An SLF4J `Logger` rather than a bespoke `log(level, message)` because authors already know the API and
parameterised messages and throwables come free — but the deciding reason is that **attribution rides in
the logger name**. The name travels with the event, so output is still attributed to the plugin when it
logs from its own thread or an `onSchedule` task, where a thread-local MDC arrives empty. Core `0.5.7`
already captures this name pattern and rate-limits the path.

### Changed — **BREAKING** (frontend `ctx`)

- `ctx.consent` is now a documented `ConsentApi`: **`request(category): Promise<boolean>`** (the host
  opens its settings for that one purpose and resolves with the answer — call it from a user gesture, the
  click-to-load placeholder of §12.5, never on mount), **`granted(): string[]`**, and an `onChange` that
  is specified as real: it fires on **every** consent change, including a withdrawal made in the settings
  page mid-session, and returns an unsubscribe.
- `filter.onChange`, `route.onChange`, `locale.onChange` and `player.on` return an unsubscribe too. They
  had the identical leak with no way to detach, and this is the release where fixing it costs nothing —
  leaving `consent` as the only detachable handle would be an asymmetry authors trip on.
  **Migration:** callers who ignored the old `void` return are unaffected. Anyone hand-rolling a
  `PluginContext` (rather than using `makeMockCtx`) must now return a function from every `onChange`.
- `createPluginI18n(...)` gains **`dispose()`**. It subscribed to `locale.onChange` for its whole life and
  never let go; that leak is now fixable by the caller. **Migration:** call `dispose()` from your render's
  cleanup callback.
- `makeMockCtx` returns `MockPluginContext` (adds `logs`), and its consent double is a new
  `makeMockConsent()` with `grant`/`revoke`/`requests`/`autoGrantOnRequest`. **The default stays
  deny-everything** — core denies until granted, and a component written against a permissive mock is one
  whose placeholder path was never exercised.

### Added — `ctx.episodeLabels`

Optional `Record<string, string>` mapping each id in `ctx.episodes` to a human-readable label
(`"S01E06 · The Lighthouse…"`), built by the host from the feed's season/episode plus title. Use it in
pickers so users see titles rather than slugs; it may be absent or partial, so fall back to the slug.
Core has supplied it since `0.5.2` via its own `HostPluginContext` — this formalises it in the contract.
TypeScript-only by design: the Java side already reaches titles through `FeedAccess`/`DisplaySnapshot`,
and labels exist for the frontend picker.

### Documentation

- **The manifest `consent.services[]` schema** is documented in the README, with a field-by-field table.
  The manifest as a whole stays core-owned — the SDK does not read `plugin.json` and will not grow a
  manifest type — but this is where plugin authors look. **The legacy `{ categories, externalSources }`
  form is rejected from `0.4.0`.** Rationale: a notice satisfying §25 TDDDG / Art. 5(3) ePD needs per-item
  name, purpose, duration, provider and third-country flag; category slugs and bare hostnames cannot
  produce one, and they force the UI to talk about "plugins" to visitors who only care about cookies and
  named companies. `hosts` doubles as the **CSP allow-list** — core widens
  `script-src`/`frame-src`/`connect-src` by exactly these origins, so an undeclared host stays blocked
  even with consent given.
- **`ConsentServiceDeclaration` / `ConsentStorageDeclaration`** (TypeScript, documentation-only). The
  declaration went from two flat string arrays to eight fields with a nested `storage[]` in one release,
  and prose was the only spec — a typo surfaced as a load-time rejection rather than a squiggle. These
  types give an author completion while writing the manifest. They validate nothing and the SDK still
  never reads `plugin.json`; **core remains authoritative**, and a disagreement is an SDK bug.
- **Consent granularity is stated explicitly: services describe, categories decide.** The manifest is rich
  per service, but every `ctx.consent` method takes a category, so two services sharing a category are
  granted or refused together — across plugins. The schema strongly implies otherwise, so the README and
  the `ConsentApi` TSDoc now say it outright, along with the only lever a plugin has (declare services
  under different categories) and the fact that **`necessary` is always granted and never prompted for** —
  behaviour that previously lived only in core's `ConsentService`/`ConsentContext` sources.
- **`request()`'s behaviour under concurrency is specified**, since a page of plugin tiles produces it:
  one consent surface host-wide (a second call joins the one in flight), the visitor decides all
  categories in one interaction, every call resolves exactly once and is never dropped, and grants are
  shared across plugins.
- [`MIGRATION.md`](MIGRATION.md) — what breaks, what to change, in what order.
- README gained sections on relational storage and logging, and now states the versioning rule the host
  actually applies: core matches `major.minor` **exactly**, so pre-1.0 a breaking change is a *minor*
  bump. The previous "a breaking change is a major bump" wording contradicted both the host and the
  0.2 → 0.3 precedent.

### Deviations from ARCHITECTURE.md

The architecture doc is the source of truth and is not edited unilaterally, so the gaps this release
opens are recorded here instead:

- §7.2's manifest example still shows `"consent": { "categories": [], "externalSources": [] }` — rejected
  from `0.4.0`.
- §12.5 still describes consent as "categories + external sources", and says a plugin loads
  consent-requiring resources only after `ctx.consent.has(cat)` without a way to *ask*.
- §7.4's `PluginContext` listing has no `logger()`; its `DocStore` listing still shows
  `List<JsonNode> query(...)` and no `delete` (stale since 0.3.0).
- §7.5's `ctx` listing lacks `episodeLabels`, `log`, `consent.request`/`granted` and the unsubscribe
  returns.
- §7.6 declares schema storage but no query surface; this release supplies one.

### Testing

`plugin-api` gains its first tests (a `PlatformApi.VERSION` tripwire — the Java side had none, only
TypeScript and the CI job — and the `Criteria` builder). New test-kit coverage for `RecordingLogger` and
`FakeSchemaStore`; new TypeScript coverage for consent defaults, unsubscribe delivery, log recording,
`episodeLabels` passthrough and `PluginI18n.dispose`.

## [0.3.0] — 2026-07-13

The **data-access cut**: the plugin doc store and the host's HTTP surface over it are now symmetric, and
both are documented. Landing this before the sample plugin starts, so plugin authors write against the
final shape.

### Added
- `DocStore.delete(scope, key)` — removes a document; idempotent, returns whether one existed. Its
  frontend counterpart is `DELETE /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}`.
- `DocEntry(key, value)` — one document, keyed. Returned by `DocStore.query`, mirroring what the host's
  list endpoint returns to the frontend.
- `DocStore.KEY_PATTERN` = `^[A-Za-z0-9._:-]{1,200}$` — the key charset, shared by the host and the test
  kit. Keys are the final path segment of the data URL, verbatim, so `/` is excluded (percent-encoding
  does not help: servlet containers reject `%2F` in a path segment). Use `:` / `.` / `-` as separators,
  e.g. `mark:userId:cell`.
- `Scope.SITE_ID` (= `main`) and `Scope.site()` — the site scope is a singleton.
- TypeScript: `DocEntry<T>` and `PagedDocs<T>`, the shapes the host's list endpoint returns, so plugins
  stop hand-declaring the envelope.
- `InMemoryDocStore` rejects a key outside `KEY_PATTERN` — a document the frontend could never address
  now fails in your tests instead of in production.

### Changed — **BREAKING** (plugin backends)
- `DocStore.query(scope, prefix)` returns `List<DocEntry>` instead of `List<JsonNode>`. A caller that
  receives values alone cannot tell documents apart, nor address one afterwards. **Migration:** `.value()`
  gives the old `JsonNode` back, `.key()` is the new information — `store.query(s, p)` →
  `store.query(s, p).stream().map(DocEntry::value)` reproduces the 0.2.x result exactly.
- `Scope`'s canonical constructor normalizes the `SITE` scope to `Scope.SITE_ID`, so
  `new Scope(SITE, anything).id()` is `main` and all site scopes are `equals` — matching how the host
  addresses them. **Migration:** none required; `Scope.site("main")` still works.
- `Scope.site(String)` is **deprecated for removal**: the site scope takes no id. **Migration:**
  `Scope.site("main")` → `Scope.site()`.
- `DocStore.put` now rejects a key outside `KEY_PATTERN` with `IllegalArgumentException`. **Migration:**
  replace `/` in keys with `:`.

Implementors of `DocStore` (the host; anyone with a custom double) must add `delete` and adapt `query`.
Plugins that only *consume* the store are affected solely by the `query` return type.

### Documentation
- Documented the **plugin data-access contract**, which was the origin of this release. A plugin's server
  side is `register(ctx)`: it persists exclusively through `ctx.store()` and aggregates in
  `ctx.onSchedule(...)`. **Plugins do not define HTTP routes** — the absence of a route-registration API
  is by design, not a gap, and reading "backend endpoint" as "a route my plugin declares" is the mistake
  this text exists to prevent.
- Spelled out how the frontend reaches plugin data: `ctx.api` (`PluginApiClient`) targets the host's
  fixed, per-plugin-namespaced generic endpoints over that same doc store — get / put / list / delete,
  mirroring `DocStore` one-to-one. The document a backend writes with `ctx.store().put(scope, key, value)`
  is the one the frontend reads at `GET …/data/{scopeType}/{scopeId}/{key}`.
- Data is hard-scoped to the plugin id; reads are gated by the slot's `visibleTo`, writes by the mapped
  role; the host validates the scope (and that its feed is enabled). Writes are plain persistence — no
  plugin code runs at request time, so derived or validated data is precomputed in `register`/`onSchedule`
  and read back from the store.
- Covered in the README ("Plugin data access"), the TSDoc on `PluginApiClient` / `PluginContext.api`, and
  the Javadoc on `PluginBackend`, `PluginContext.store()`, `DocStore`, `DocEntry` and `Scope`.

## [0.2.0] — 2026-07-11

### Added
- `DisplaySnapshot` gains four nullable fields for richer episode display: `imageUrl` (episode artwork,
  `itunes:image` on the item), `feedImageUrl` (feed/show cover, `itunes:image` on the channel), `author`
  (`itunes:author`) and `subtitle` (`itunes:subtitle`). The host fills each, leaving `null`/absent
  whatever the feed does not declare.
- `DisplaySnapshot.artwork()` (Java) / `resolveArtwork(snapshot)` (TypeScript) — a derived accessor
  returning the episode `imageUrl` if present, else `feedImageUrl`, else `null`/`undefined`.
- TypeScript: the SDK now ships a `DisplaySnapshot` interface mirroring the Java record (previously the
  type was Java-only).

### Compatibility
- Additive, minor bump. Adding record components extends `DisplaySnapshot`'s canonical constructor, so
  Java callers that construct one directly must pass the new arguments (the four new values, in order
  after `duration`).
- Existing `episode_display` JSONB rows in core deserialize with the new fields `null` until the episode
  is re-polled — no migration required.

## [0.1.1] — 2026-07-11

### Fixed
- Added `repository`/`bugs`/`homepage` metadata to `package.json` so npm provenance (`--provenance`,
  OIDC trusted publishing) succeeds on release.

## [0.1.0] — 2026-07-11

### Added
- Initial release: the Java `plugin-api` contract (`dev.mosaicast.plugin.api.*`) + `plugin-testkit` test
  doubles, and the `@mosaicast/plugin-sdk` TypeScript package with the `/testing` subpath.

[0.8.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.8.0
[0.7.1]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.7.1
[0.7.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.7.0
[0.6.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.6.0
[0.5.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.5.0
[0.4.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.4.0
[0.3.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.3.0
[0.2.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.2.0
[0.1.1]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.1.1
[0.1.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.1.0
