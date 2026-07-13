# Changelog

All notable changes to `mosaicast-plugin-sdk` are documented here. The Java `plugin-api` /
`plugin-testkit` artifacts and the `@mosaicast/plugin-sdk` npm package share one SemVer anchor and are
released together (see the "Releasing" section in the README).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.3.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.3.0
[0.2.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.2.0
[0.1.1]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.1.1
[0.1.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.1.0
