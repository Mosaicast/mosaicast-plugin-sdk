# Changelog

All notable changes to `mosaicast-plugin-sdk` are documented here. The Java `plugin-api` /
`plugin-testkit` artifacts and the `@mosaicast/plugin-sdk` npm package share one SemVer anchor and are
released together (see the "Releasing" section in the README).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.14.0] — 2026-09-04

A plugin can put a message in a user's **inbox**. A minor bump, because `platformApi` matches on
`major.minor` — every plugin re-declares and rebuilds.

**Core must pin `mosaicastSdk = "0.14.0"`.** §17 landed in core as a proposal only (`22e046f`), with no
host implementation yet, so this release defines the contract core implements against rather than
following one.

A plugin that finished a long-running thing a user took part in — a bingo resolving, the case §17 was
written for — could only hope they came back and looked. This is the first plugin surface that **writes
into another user's view of the site**, so it is bounded twice over: the host delivers only to users the
plugin already holds `USER`-scope data for (the same partitions `queryAcrossUsers` reads), and the rate
limits are the host's rather than the plugin's.

### Added

- **`ctx.notify` / `ctx.notifier()`** — `NotifyClient.send(userIds, msg)` (TS) and
  `Notifier.send(Collection<UUID>, NotifyMessage)` (Java). **`null` unless the manifest declares
  `notifications`**, the same shape and reasoning as `blobs`, `tags` and `identity`.
  - **Partial sends are visible.** `send` answers **who was actually notified** rather than resolving
    `void`. An ineligible or erased recipient is left out instead of failing the call — one stale
    participant must not cost the other forty-nine their notification, given how ordinary an erased
    account is (§12.8) — and a write whose partial failure is invisible degrades in silence: a plugin
    working from a stale participant list notifies nobody and looks exactly like one working perfectly.
  - **There is no read side.** A plugin cannot list, count or mark an inbox, or learn whether anyone
    opened what it sent. And nothing here reaches email — in-app only, per §17.
- **`NotifyMessage`** — a translation `key`, optional `params`, optional `link`. **Never a rendered
  string**: a notification written on a timer is read days later in whatever language the shell is set to,
  so a rendered sentence fixes the language at send time and breaks §12.7 on the surface where it is most
  obviously wrong. Java gets convenience constructors (`NotifyMessage(key)`,
  `NotifyMessage(key, params)`) and `withLink`; `params` are defensively copied so a caller reusing its
  map cannot rewrite a message already sent. `link` is host-validated and internal-only.
- **`NotificationException`** (Java) — checked, for the reason `TranslationException` is: a send refused
  by the operator's cap is a routine outcome, not a bug, and a plugin that has not decided what to do
  about it has one. `Reason.RATE_LIMITED` is `retryable()`; `INVALID_LINK` and `INVALID_MESSAGE` are not.
  On the TS side the same failures arrive as `PluginApiError` 429 / 400.
- **`notifications` in the manifest type** — `{ sends: boolean; perUserPerDay?: number }`, the number being
  what the plugin *asks* for and the operator's cap what it gets. Typed for an author's editor only; the
  **host remains the sole validator**.
- **Test-kit doubles** (§13.5) — `FakeNotifier` (Java, wired via `FakePluginContext.withNotifier(…)`) and
  `makeMockNotify` (TS). Both model the partial send. `FakeNotifier` reads eligibility **from the
  `InMemoryDocStore`'s user partitions** rather than a hand-seeded list, so the rule cannot drift from the
  host's — the way to make a user notifiable is to give them a row, which is also how they became a
  participant. The cap is off until a test arms it (`withPerUserPerDay` / `perUserPerDay`), and an
  ineligible recipient never consumes one. `ctx.notify` defaults to `null` in `makeMockCtx`.

### Changed

- Nothing. This release is purely additive; the bump is `platformApi`'s exact `major.minor` match.

### Deviations from ARCHITECTURE §17 (flagged, not silently taken)

- **`send` returns the notified ids, not `void`.** §17.1 writes `Promise<void>`, which cannot express a
  partial send — and the host's eligibility rule guarantees partial sends. See above.
- **The Java accessor is `notifier()`, not `notify()`.** §7.4 writes the latter and it **cannot compile**:
  `Object.notify()` is `final`, so no Java interface may declare that name. `notifier()` matches the type
  and this context's own `logger()` / `translation()` / `config()`, and avoids reading like a getter for a
  list of notifications. The TypeScript half is `ctx.notify`, exactly as §7.5 specifies — the two differ
  because one of them has to.
- **§17 specifies no failure vocabulary.** One is defined here (`NotificationException.Reason`, and 429 /
  400 on the browser side) because a cap that fails silently is one a scheduled sender can never back off
  from.

### Migration

Manifest bump and rebuild. No compile break in either language — `PluginContext` gained a method, but
plugins consume that interface rather than implement it, and `FakePluginContext` implements the new one
for you.

## [0.13.0] — 2026-09-04

A plugin can turn the user UUIDs it already holds into **people**. A minor bump, because `platformApi`
matches on `major.minor` — every plugin re-declares and rebuilds.

**Core must pin `mosaicastSdk = "0.13.0"`.** Its `gradle/libs.versions.toml` reads `0.10.0` on master; the
host side of §8.8 does not compile against anything older.

A backend calling `queryAcrossUsers` gets `OwnedDocEntry(userId, …)` — UUIDs and nothing else. A bingo
leaderboard therefore had ids and no way to render a person, and the only workarounds were to copy display
names into the plugin's own store (which outlives the rename meant to shed them and the erasure meant to
end them) or to show raw UUIDs. §8.8 fills the gap with a **lookup rather than a wider `ctx.user`**: §10
still holds, the host still resolves access, and what a plugin learns about somebody else stays exactly a
name, an avatar path and a role.

### Added

- **`ctx.users` / `ctx.users()`** — `UserDirectory.resolve(ids)` (TS) and `Users.resolve(Collection<UUID>)`
  (Java), returning `UserRef { id, displayName, avatarUrl, role }`. **`null` unless the manifest declares
  `identity`**, the same shape and reasoning as `blobs` and `tags`.
  - **Absent, not redacted.** An id that is unknown, erased or pseudonymised (§12.8) is *missing* from the
    result — no `null` element, no tombstone. The result is therefore **not index-aligned** with the input
    and may be shorter: match on `id`, never on position. That is what lets a leaderboard row outlive its
    author as §13 requires — the aggregate stays, the person becomes a placeholder the plugin renders.
  - **It resolves, it does not enumerate.** There is no list call, by design: a plugin may ask only about
    ids it already holds, and the only way it comes by them is its own scope.
  - **`avatarUrl` is always host-relative and always populated** — `/api/users/{id}/avatar` (§8.7). Every
    user has an avatar (generated from the UUID when there is no provider picture), so there is no null
    case and no fallback to implement. Never a provider URL: the host proxies the bytes rather than
    redirecting, so a Discord snowflake never reaches the page source.
  - **Store UUIDs, resolve at render — never persist a display name.** The host cannot enforce this: core
    provisioned a plugin's tables without ever learning which column holds a person, so §12.8 cannot reach
    inside them. The doc comment on `Users` / `UserDirectory` is the enforcement.
- **`identity` in the manifest type** — `{ resolvesUsers: boolean }`. Declared, never derived, even though
  the plugin already *has* the ids: the capability is turning them into people, and that is what an
  operator should read off a manifest before installing. Typed for an author's editor only; the **host
  remains the sole validator**.
- **Test-kit doubles that model absence** (§13.5) — `FakeUsers` (Java, wired via
  `FakePluginContext.withUsers(…)`) and `makeMockUsers` (TS). An id with no entry is missing from the
  result rather than `null` in it, and `withoutUser` / `forget` stage the erased-author case. Both derive
  `avatarUrl` the way the host does rather than accepting one, so an assertion pins the string production
  produces. `ctx.users` defaults to `null` in `makeMockCtx`, so an existing test keeps exercising the
  undeclared-manifest case.

### Changed

- **`ctx.user` gains `displayName` and `avatarUrl`** (TS). Same fields as `UserRef`, same rule: `id` is
  identity, these two are presentation — read them at render, do not keep a copy.
- `OwnedDocEntry`'s Javadoc no longer says a `userId` cannot be turned into a name; it points at
  `ctx.users()`.

### Migration

Manifest bump and rebuild. The one compile break is a **hand-built `ctx` in a TypeScript test**: a literal
`user: { id, role }` now needs `displayName` and `avatarUrl`. Java plugins have nothing to edit —
`PluginContext` gained a method, but plugins consume the interface rather than implement it, and
`FakePluginContext` implements the new one for you.

## [0.12.0] — 2026-09-02

Two records gain a component so a plugin can say **what language its pages are in**. A minor bump, because
`platformApi` matches on `major.minor` — every plugin re-declares and rebuilds.

Core just gained per-locale URLs: a page can be requested as `?lang=de`, and `sitemap.xml` emits reciprocal
`hreflang` alternates with `x-default` on the bare URL (§6.6, §12.7). **A plugin could not participate.**
`OgMeta` had no locale, so a plugin's German page was announced with the site's default `og:locale`;
`SitemapUrl` had no alternates, so a plugin's translation group was invisible. Core deliberately emits *no*
alternates for plugin entries rather than assuming the site's UI languages apply to content it cannot read
— the honest answer, and the reason this was blocking rather than cosmetic.

### Added

- **`OgMeta.locale`** — the language *this* title and description are written in (`de`, `pt-br`; trimmed
  and lower-cased), or `null` for "whatever the host resolved for this request". Nullable because most
  plugin pages are in the site's language and should not have to say so; a page written in one fixed
  language says which, and `og:locale` stops depending on who scraped the link.
- **`SitemapUrl.alternates`** — `Map<String, String>`, locale code → the path that page is written in that
  language. Empty means "no translation group", which is what the host already assumed.
  - **A map of paths, not a list of locale codes.** The cheaper shape — "this page exists in `de` and `en`",
    host appends `?lang=de` — cannot say what language `loc` *itself* is in, and the host will not guess it
    (§6.6). So the map **must** contain an entry pointing at `loc`; that entry is the plugin naming its own
    page's language, and the canonical constructor enforces it. The second reason is expressiveness: a wiki
    whose German article lives at `/p/wiki/artikel` and English one at `/p/wiki/article` has two paths in
    one group, and a list of codes has no way to link them. A plugin that renders one path per language maps
    every locale to the same `loc` and pays nothing for the choice.
  - **The host still owns URL shape.** Values are *paths*, the same shape as `loc` — never full URLs, never
    carrying `?lang=` yourself. The host adds the parameter, leaves the site default on the **bare** URL,
    points `x-default` there, makes the group reciprocal, and confines every alternate to the plugin's own
    `/p/<pluginId>/` namespace exactly as it does `loc`. Naming paths buys expressiveness, not reach.
- **`SitemapProviderHarness`** (test kit) — collects a provider's entries and reports what the host would
  drop or contradict: a `loc` or an alternate outside the namespace, a hand-written `?lang=`, a duplicated
  location, and the one no single entry can see — two entries in **one** translation group declaring
  **different** groups, which is what a half-updated slug map looks like.

### Changed

- **`OgMeta` and `SitemapUrl` are 4- and 3-component records.** Source compatibility is preserved by real
  constructors for the old shapes — `OgMeta(title, description, imageUrl)` and `SitemapUrl(loc,
  lastModified)` — because "the host decides" and "nothing translated" are the honest answers for most
  plugin pages. Binary compatibility still breaks (the canonical constructor's signature changed), so the
  upgrade is a manifest bump and a **rebuild**, with no code edits for anyone with nothing to declare.
- `SitemapUrl` rejects a translation group that never names `loc`'s own language, a blank locale code or
  path, and a locale listed twice. An alternate is a claim about content: list a language only if the page
  at that path is really written in it. A default-language fallback served to a reader who asked for German
  is a kindness to a visitor and a lie to a crawler.

### Migration

Manifest bump and rebuild. No compile break unless you match on `OgMeta`/`SitemapUrl` as record patterns or
call their canonical constructors reflectively. See `MIGRATION.md`.

### Specs

`docs/ARCHITECTURE.md` needs no amendment — §6.6 already says the host emits no alternates for a
`SitemapProvider` entry "unless the plugin declared them", and that is now a thing a plugin can do.

`docs/BRIEF.md` §"Content – Java" is updated to the new signatures (normally read-only here; changed on the
maintainer's instruction, since it described this repo's own contract and nothing else could correct it).

## [0.11.0] — 2026-08-30

One manifest block, and a handle that now has **two** ways of being `null`. A minor bump, because
`platformApi` matches on `major.minor` — every plugin re-declares and rebuilds.

0.10.0 shipped `ctx.translation` ahead of core's implementation. Implementing it exposed a hole the
contract could not express: the handle was gated **only** by the operator's provider choice, with no
manifest declaration and no role floor. Harmless on a backend, which has no HTTP routes and calls at
startup or on a timer. Not harmless in a browser, where `translate()` means anyone who can load the page
can spend the operator's money on a metered API. Every other plugin write surface has a floor —
`data.writableBy`, `tags.writesEpisodes`, `blobs` — and this one had none, while the host's rate limiter
keys on kind and provider, so one plugin could exhaust a site's budget with nothing recording which.

### Added

- **`external` in the manifest** — `PluginExternalDeclaration` (TS): `kinds: ExternalServiceKind[]` and an
  optional `usedBy` role floor. Typed on the same terms as `blobs` and `tags`: documentation the host
  validates, not enforcement the SDK performs.
  - **`kinds` is a list with one member today.** A plugin that later wants transcription adds an entry
    rather than a second block; `ExternalServiceKind` is a closed union because a kind the host has no bean
    for is not an unconfigured service, it is a name nothing answers to.
  - **`usedBy` defaults to `podcaster`**, matching `data.writableBy`'s floor, and is **browser-side only** —
    `register()` and `onSchedule` have no visitor and no role. `anonymous` is legal and almost always
    wrong: a metered provider plus an anonymous floor is an open spending endpoint.
  - One floor per plugin, not per kind. With one kind the two are the same thing spelled differently, and a
    later per-kind value can only **narrow** this one, so a manifest written today keeps meaning what it
    says.

### Changed

- **`ctx.translation` / `ctx.translation()` are now manifest-gated as well as operator-gated.** `null` when
  `external.kinds` does not contain `'translation'`, **or** when the admin configured no provider. The two
  reasons are deliberately **indistinguishable** — whether you declared is a static fact about a file you
  wrote, so a runtime discriminator would be API surface for a question `plugin.json` already answers, and
  a second nullability shape across four capability handles is the real cost. Rule of thumb for an
  unexpected `null`: check the manifest before the admin panel.
- **`translate()` can now reject with 403** when the visitor is below `usedBy`. A non-null handle is not
  permission; the host enforces the floor at the call.

### Migration

**A silent behaviour change**, and the only one here: a plugin that used 0.10.0's ungated handle keeps
compiling and gets `null` at runtime until it declares `external`. `translation` was already nullable, so
no type error catches it — it surfaces as a button that stopped working. See `MIGRATION.md`.

### Deviation flagged (not changed here)

`docs/ARCHITECTURE.md` is read-only in this repo, synced from `mosaicast-core`. **§16 describes the
operator half and never says a plugin must declare its use.** Proposed for core to amend and re-sync:

> A plugin declares the kinds it uses in its manifest (`external.kinds`) and the lowest role that may
> trigger a call from its UI (`external.usedBy`, default `podcaster`); an undeclared kind is `null` on the
> plugin's context and 404 on its endpoint, independently of whether a provider is configured.

## [0.10.0] — 2026-08-26

Two additions, both about something plugins could not previously see: **what languages this site has**, and
**whether it can translate**. A minor bump, because `platformApi` matches on `major.minor` — every plugin
re-declares and rebuilds.

### Added

- **`ctx.locale.available()` / `ctx.locale.content()`** (TS) and **`ctx.locales()`** (Java). Core made
  languages a runtime registry — an operator drops a catalog into `MOSAICAST_LOCALES_DIR` and enables it —
  so a plugin that hardcoded a language list is now simply wrong. A wiki whose articles are written per
  language cannot ask "which ones?" without this.
  - **Two lists, deliberately.** `available()` is what the shell can *render* in; `content()` is what the
    admin permits text to be *authored* in. A language needs a catalog for the first and nothing at all
    for the second, so a Dutch imprint on an English-only site is expressible — and an editor built from
    `available()` would offer the wrong list.
  - `Locales.isContentLocale(String)` is the backend check for a per-locale write. The browser's list is a
    hint; what reaches your storage is input.
- **`ctx.translation`** (TS) / **`ctx.translation()`** (Java) — host-mediated machine translation, `null`
  when the site admin configured no provider. The host owns the provider, the credentials and the cache,
  so an operator does not have to trust every plugin with an API key, and two plugins translating the same
  paragraph cost one call. `TranslationException` is **checked** and carries a `reason()`: somebody else's
  service refusing is a routine outcome rather than an exceptional one, and the compiler is the cheapest
  place to discover a plugin has not decided what to do about it.
- Test doubles: `makeMockTranslation()` (TS), `FakeTranslation` and `FakeLocales` (Java), plus
  `FakePluginContext.withTranslation(...)` / `.withLocales(...)` — chaining mutators, never new
  constructor parameters, for the reason `withTags` records in that file.

### Changed

- **`createPluginI18n(catalogs, locale)` now asks for only the two members it uses**
  (`Pick<PluginContext['locale'], 'current' | 'onChange'>`). Widening `ctx.locale` would otherwise have
  broken every plugin test that hands in a two-method double, over members this function never touches.
- **`translation` defaults to `null` in `makeMockCtx`**, like `schema`/`blobs`/`tags` — a component
  written against a translator that is always there breaks on every site that has none, which is every
  site by default.

### Migration

`ctx.locale` and `PluginContext` gained members, so a plugin that hand-builds a context literal in its
tests stops compiling until it supplies them — caught by `tsc --noEmit`, not by the test runner. See
`MIGRATION.md`. **Core does not implement `ctx.translation` yet** (`mosaicast-core`, external-services
milestone); the locale lists land with it.

## [0.9.1] — 2026-08-24

One optional extension point, and the reason it is a patch. **No contract change** — `platformApi` matches
on `major.minor`, so every plugin declaring `0.9.0` keeps loading, unchanged, and nothing needs
re-declaring. That is the whole argument for shipping it here rather than in `0.10.0`: the interface can
exist without rejecting a single installed plugin.

`/p/<pluginId>/<anything>` answers `200` for every subpath once a plugin declares a `page` slot. A wiki page
that was never written, a mistyped slug and the URL of a page deleted last year all render the plugin's
not-found view inside a `200 OK`. ARCHITECTURE §6.6 rules that out for core's own routes — "real HTTP 404s
for unknown episodes/routes, no soft-404" — and the consequence is that a crawler indexes a plugin's typos
and its deleted pages. The host cannot fix this alone: only the plugin knows whether a subpath is a thing.

### Added

- **`PageRouteProvider`** (Java, optional) — `boolean hasRoute(String subpath)`, alongside
  `SitemapProvider` and `ShareMetadataProvider`. The plugin answers whether it renders something at a
  subpath; the host turns a `false` into a real `404`.
  - **Absent means today's behaviour.** A plugin that does not implement it keeps answering `200` for
    everything under its subtree. Core only asks plugins that declare a `page` slot.
  - **Not `ShareMetadataProvider`, and the docs say why.** A plugin's subtree legitimately contains views
    with nothing to describe — the wiki's `_search/<term>` and `_admin` return an empty `metaFor` *on
    purpose* — so treating "no share metadata" as "no page" would 404 working routes. That mistake is why
    this is a separate interface rather than a second reading of an existing one.
  - **It runs on a request**, like `SearchProvider`: keep it cheap and bounded. A provider that throws is
    logged and skipped and the route answers `200` — the same failure posture the other extension points
    have, because a broken plugin must not turn a working page into a 404.
  - **It decides the status line, not the body.** The shell still renders its own not-found view, so a
    plugin does not need to produce one.
- **`PageRouteProviderHarness`** (test kit) — asks a provider about a handful of subpaths in one call and
  reports what the host would answer. It always probes the **root** whether the test lists it or not (a
  lookup over the plugin's own slugs answers `false` for `""` and 404s the plugin's landing page), and it
  **records a throw** as the `200` the host would serve instead of ending the run.

**Core does not implement this yet** — `mosaicast-core#89` is the branch in `PluginPageController.page`,
and it follows this release. Until then a plugin can implement and test the provider; the status line does
not change.

## [0.9.0] — 2026-08-22

The release that came out of *using* the contract. Every item here was found the same way — building
`mosaicast-plugin-sample` and `mosaicast-plugin-wiki` against 0.8.0 and noticing what the plugin had to
build for itself — and they turn out to be one shape of gap repeated: **the host holds something a plugin
can only re-implement badly on its own**, so every plugin re-implements it, subtly differently, with the
interesting failures in the ones that get it wrong quietly.

Four examples of the same sentence. Tags exist in core as a feed-derived filter axis with no vocabulary and
no plugin surface, so the wiki grew a private `tags` column and its `lore` and an episode's `lore` became
unrelated strings. The Java contract can read an episode's `DisplaySnapshot` and the frontend cannot, so the
wiki projects host data into its own doc store and keeps it stale on a schedule. Core searches episodes and
nothing else, so each plugin builds a second search box on the same site. And `PluginApiClient` rejected
with an untyped value, so every call site wrote `catch(() => undefined)` to survive a 404 — and swallowed
the 500, the 403 and the network failure with it.

The fifth is different in kind: ARCHITECTURE §12 promises that plugin contributions are **pseudonymised**
on account deletion. Bingo is a plugin. Core cannot find its contributions, cannot pseudonymise them, and
cannot know that pseudonymising is right rather than deleting — that judgement belongs to whoever designed
the data, and there was no hook to ask. `UserDataHandler` is that hook, filed before core builds account
deletion rather than after.

**About half of this surface is contract ahead of implementation.** `ctx.feeds`, `ctx.tags`, `ctx.docs`,
`route.query`, the typed errors and both extension points need core to build the other half; the SDK-side
helpers (`iconCss`, `matchRoute`, the i18n formatters, `defineManifest`, `declaredTypeFor`, `flushMockApi`)
work the day this ships. That is the deliberate order this time — core follows the contract — and it is the
same position `SchemaStore` was in before 0.7.0, which is worth saying plainly rather than letting a plugin
author discover it. Tracked as issues on `mosaicast-core`.

**No plugin *code* changes.** Everything is additive. What breaks is a hand-built `ctx` literal in a test —
`PluginContext` gained three members and `PluginRoute` two — and `makeMockCtx` absorbs it. See
[MIGRATION.md](MIGRATION.md).

### Added

- **Site-wide tags: `ctx.tags` (TypeScript) and `PluginContext.tags()` (Java)** — the shared vocabulary
  plugins can read and contribute to, instead of N private columns that look identical to a reader and
  share nothing. Opt-in through a manifest `tags` block; both accessors are **`null`** without it, the same
  shape as `schema` and `blobs`. Reads: `all`, `episodesWith`, `tagsOn`, `similarTo`, `subjectsWith`,
  `tagsOnSubject`. Writes: `tagSubject` / `untagSubject`, and `tagEpisode` / `untagEpisode`.
  - **Two writes that look alike and are not.** Tagging your own subjects touches keys you invented in your
    own namespace, and `data.writableBy` is the whole story. Tagging an *episode* changes the shell's filter
    options **and** what core recommends beside it — a real capability, so it needs a second flag
    (`tags.writesEpisodes`) and what a plugin may do stays readable off its manifest.
  - **The host owns a canonical key**; `label` is presentation, kept from first use. `Maritime`, `maritime`
    and `maritime ` are one tag, and the rule applies to feed ingest too — a vocabulary normalised on one
    path only is not normalised.
  - **What no plugin may do:** delete a tag from the vocabulary (it is shared), rename one (a
    vocabulary-wide edit, and admin's), or remove another writer's assignment, the feed's included. Every
    plugin write is recorded with `source = plugin:<id>`, which is what makes the last one enforceable
    rather than merely discouraged — and gives an operator something to revoke.
- **`ctx.feeds`** — `display(slug)` and `displayMany(slugs)`, the frontend half of the Java `FeedAccess`.
  This retires a piece of dead surface: `DisplaySnapshot` and `resolveArtwork` have shipped since 0.1 with
  nothing in the contract that hands a frontend one. The host filters, so a `WITHDRAWN` or tier-gated
  episode is **absent** rather than redacted, and this cannot enumerate episodes `ctx.episodes` withheld.
  No `readableBy` gate of its own — it returns host data the visitor can already read; it exists so a
  plugin need not know that URL shape, the argument `ctx.links` made. `displayMany` clamps at 200.
- **`ctx.docs`** — a typed doc-store client over the endpoints `ctx.api` already reaches. `'self'` resolves
  to `data/user/me` and `'site'` to `data/site/main`, which makes the most security-relevant convention in
  the contract — per-user data goes in the `USER` scope, never in a key — the *shortest* thing to write.
  That is the only reliable way to make a convention stick. Keys are validated client-side, turning a 400
  round-trip into a thrown error carrying the pattern. `ctx.api` stays as the escape hatch.
- **Typed API failures** — `PluginApiError` (with `status` and the RFC 7807 `problem` body),
  `isPluginApiError`, and `PluginApiClient.getOrNull`. `getOrNull` is the one that changes behaviour in the
  wild: absence stops being an exception, so the `catch` that remains is a real error path again. The guard
  is **structural**, because the error crosses a bundle boundary from the host and `instanceof` against a
  plugin's own copy of a class would answer `false` for a genuine one.
- **`blobs.upload` normalises the declared type** by default, via the exported `declaredTypeFor(file)`.
  A real cross-browser failure, not a hypothetical: Chromium fills `File.type` from its own table, Firefox
  asks the OS MIME database, and where that lookup fails it hands over `''` — `FormData` then sends
  `application/octet-stream` and the host refuses on the *declared* type before it sniffs anything. A valid
  PNG, rejected in one browser only, on a machine the author cannot reproduce. Guessing is safe here
  **specifically because the host still reads the bytes**: a wrong guess becomes the same 415 it would have
  been. `{ declaredType: 'preserve' }` opts out; an unmapped extension is left alone so the refusal keeps
  the host's wording.
- **`iconCss(names)` / `iconMask(name)`** — the host icon mechanics, as CSS strings. The icon set stays off
  `ctx` on purpose (§12.3): custom properties inherit through the shadow boundary, so a plugin picks up an
  icon the day core publishes it with no SDK release. These keep that property (the name parameter is a
  plain `string`; `KnownIconName` is an open hint) and own the part that is easy to get wrong — chiefly
  that **a missing icon renders as a solid square, not as nothing**. An unresolved `var()` invalidates the
  declaration, `mask-image` falls back to its initial `none`, and an unmasked element paints `currentColor`
  across its whole box. `mask-image: none` is the obvious fallback and the cause; these emit a blank SVG.
- **`ctx.route.query` and `ctx.route.hash`** — `navigate` always accepted a `?query` and there was no
  supported way to read one back. The only route was `location.search`, which is exactly the "do not reach
  past this handle" that `navigate` forbids, for the reason it gives. Filters, sort order and pagination are
  the obvious shareable-link state.
- **`matchRoute(path, patterns)`** — the prefix matcher every page plugin hand-rolls, and the hand-rolled
  one is usually a `startsWith` that also matches `moments-archive`. Whole-path match, `:param` capture,
  `null` for the not-found branch.
- **Locale-bound formatting on `createPluginI18n`** — `plural`, `n`, `date`, `duration`, `bytes`. A catalog
  could not express "1 highlight" / "5 highlights" at all, so plugins shipped an English-shaped
  `if (n === 1)`, which is wrong in Polish, Russian and Arabic. `duration` and `bytes` earn their place by
  being contract-adjacent: `DisplaySnapshot.duration` *is* an ISO-8601 string and `BlobQuota` *is* three raw
  byte counts, so the SDK produces both and may as well render them — locale-correctly, unlike the
  hand-rolled byte formatter that hardcodes `.` as the decimal separator.
- **`PluginManifest` + `defineManifest(m)`** — the whole manifest typed, not two blocks of it.
  **Documentation, not enforcement**, on exactly the terms `PluginDataDeclaration` already carried: the host
  owns and validates the manifest, and if this type and core disagree, core wins. The argument for typing
  the rest is drift — `nav[]` and a plugin's own in-page tab bar are one list of entrances declared twice,
  and a renamed path becomes a menu entry leading to an empty view with nothing to catch it. Documented
  where the two legitimately diverge: `path`/`icon`/`role` are pinned, the menu label deliberately is not,
  because core has no plugin catalogs to translate it against.
- **`SearchProvider`** (Java, optional `ExtensionPoint`) — plugin content contributed to the site's search,
  keyed by a subpath under `/p/<id>/` exactly as `ShareMetadataProvider` works in reverse. Results are
  **grouped by source rather than merged**: a plugin's `score` and Postgres `ts_rank` are not on one scale,
  and pretending otherwise produces a ranking nobody can explain. **Access is the plugin's job here**,
  unusually — the host has no model of a plugin's objects, so a provider returning a draft to an anonymous
  visitor is a leak nothing else catches. `role` is `null` for anonymous.
- **`UserDataHandler`** (Java, optional `ExtensionPoint`) — `eraseUser(userId)` plus a defaulted
  `exportUser(userId)`. Erase or pseudonymise is **the plugin's call**; the host cannot make it. Both on one
  interface deliberately: they need the same "find this user's rows" query, and shipping erasure alone means
  every plugin writes it twice. Handlers run before the account row is dropped, and must be idempotent — a
  failed deletion is retried.
- **Test doubles for all of it**: `makeMockFeeds`, `makeMockTags`, `makeMockDocs`, `apiError(status)` and
  `flushMockApi` in `@mosaicast/plugin-sdk/testing`; `FakeTags`, `SearchProviderHarness` and
  `UserDataHandlerHarness` in the Java kit.
  - **They refuse what the host refuses**, continuing the line 0.8.0 drew: `makeMockTags` / `FakeTags` throw
    on an episode write without the declaration, and their `untagEpisode` leaves the feed's assignment
    alone. A component that has only met a permissive double meets its first refusal in front of a user.
  - **`flushMockApi` removes a real footgun.** The mock resolves before a component's `.then(setState)`
    runs, so `await Promise.resolve()` covers one microtask hop and not two — the symptom is an assertion
    that fails *only sometimes*, depending on how many hops the component happens to have.
  - **`SearchProviderHarness` calls a provider once per role, anonymous included**, because that is the one
    test the interface's unusual access rule demands and the one an author writes by hand incorrectly
    (anonymous is `null`, not a fourth enum constant). `UserDataHandlerHarness.eraseTwice` is the
    idempotency test that otherwise only fails during a production retry.

### Changed

- `FakePluginContext` gained **`withTags(Tags)`**, a chaining mutator rather than a sixth constructor
  parameter. The constructor list was already at five, and each positional addition breaks every existing
  plugin test to supply something almost none of them want — the mistake 0.7.1 was spent undoing on the
  TypeScript side. The four- and five-argument constructors are untouched and still mean the same thing.
- `makeMockCtx` wires `docs` and `feeds` as real (empty) doubles, `tags` as **`null`** like `schema` and
  `blobs`, and adds an empty `query`/`hash` to the merged `route` default. The 0.7.1 merge already protects
  a `route: { path }` override from the two new required members.
- `makeMockBlobs` applies the same declared-type normalisation the real client does, so the double and the
  host agree about a `File` whose `type` the browser left empty.

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

[0.14.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.14.0
[0.13.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.13.0
[0.12.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.12.0
[0.11.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.11.0
[0.10.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.10.0
[0.9.1]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.9.1
[0.9.0]: https://github.com/Mosaicast/mosaicast-plugin-sdk/releases/tag/v0.9.0
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
