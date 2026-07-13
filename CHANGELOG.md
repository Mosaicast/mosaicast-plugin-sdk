# Changelog

All notable changes to `mosaicast-plugin-sdk` are documented here. The Java `plugin-api` /
`plugin-testkit` artifacts and the `@mosaicast/plugin-sdk` npm package share one SemVer anchor and are
released together (see the "Releasing" section in the README).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Documentation
- Documented the **plugin data-access contract** — docs only, no code, no new types, no version bump
  (`platformApi` stays **0.2.0**). A plugin's server side is `register(ctx)`: it persists exclusively
  through `ctx.store()` (the `DocStore`) and aggregates in `ctx.onSchedule(...)`. **v1 plugins do not
  define HTTP routes** — the absence of a route-registration API is by design, not a gap.
- Spelled out how the frontend reaches plugin data: `ctx.api` (`PluginApiClient`) targets the host's
  fixed, per-plugin-namespaced generic endpoints over that same doc store, which mirror `DocStore` —
  get / put / list, no more:
  - `GET  …/data/{scopeType}/{scopeId}/{key}` → one doc, 404 if absent
  - `GET  …/data/{scopeType}/{scopeId}?prefix=&page=&size=` → `{ items: [{ key, value }], page, size,
    totalElements, totalPages }` — paginated, and keyed because the frontend cannot address a doc
    without its key
  - `PUT  …/data/{scopeType}/{scopeId}/{key}` → upsert, last-write-wins
  - **no `DELETE` in v1**, since `DocStore` has no `delete` and the two ends stay symmetric
  Data is hard-scoped to the plugin id; reads are gated by the slot's `visibleTo`, writes by the mapped
  role; the host validates the scope. `SITE` normalizes to the singleton id `main`, and keys must match
  `^[A-Za-z0-9._:-]{1,200}$` (final path segment, verbatim — no `/`). Writes are plain persistence: no
  plugin code runs at request time. Covered in the README, the TSDoc on `PluginApiClient` /
  `PluginContext.api`, and the Javadoc on `PluginBackend`, `PluginContext.store()`, `DocStore` and
  `Scope`.
- Noted the deliberate v1 asymmetries and their 0.3.0 follow-ups on the types themselves:
  `DocStore.delete(scope, key)` (with the `DELETE` endpoint alongside), `DocStore.query` returning keyed
  entries like the HTTP list does, and a `Scope.SITE_ID` constant.

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

[0.2.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.2.0
[0.1.1]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.1.1
[0.1.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.1.0
