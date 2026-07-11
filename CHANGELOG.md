# Changelog

All notable changes to `mosaicast-plugin-sdk` are documented here. The Java `plugin-api` /
`plugin-testkit` artifacts and the `@mosaicast/plugin-sdk` npm package share one SemVer anchor and are
released together (see the "Releasing" section in the README).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
