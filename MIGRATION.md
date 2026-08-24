# Migrating a plugin to `platformApi` 0.9.1

Two migrations in one file. **On `0.9.0`?** Read the next section and stop — the rest is the `0.8.x` guide.
**On `0.8.x`?** Do [0.8.x → 0.9.0](#08x--090-the-release-that-came-out-of-using-the-contract) first, then
come back to the top.

---

# 0.9.0 → 0.9.1: real 404s for your unknown subpaths

**Nothing to do.** This is a patch: core matches `platformApi` on `major.minor` and lets the patch float, so
a plugin declaring `0.9.0` keeps loading against a `0.9.1` host, unchanged. You may leave your manifest
alone. Bump the dependency when you want the new interface:

```diff
- implementation("dev.mosaicast:plugin-api:0.9.0")
+ implementation("dev.mosaicast:plugin-api:0.9.1")
```

## What the release adds, and why you might want it

Today every subpath under your plugin answers **200**, once you declare a `page` slot. A page that was never
written, a mistyped slug and the URL of a page you deleted last year all render your not-found view inside a
`200 OK`. That is a soft-404: a crawler indexes your typos and your deleted pages, and there is nothing in
the response to tell it otherwise. The host cannot fix it for you — only your plugin knows whether a subpath
is a thing.

```java
public final class WikiRoutes implements PageRouteProvider {
    @Override
    public boolean hasRoute(String subpath) {
        return subpath.isEmpty()                        // your own landing page — do not forget this one
                || subpath.startsWith("_search/")
                || subpath.equals("_admin")
                || pages.exists(slugOf(subpath));
    }
}
```

Add the class to `backend.extensions`; there is no declaration beyond that. **Implement it and your unknown
subpaths start answering a real 404** — which is the point, and worth saying out loud before you ship it:
anything you forget to claim here disappears from search results.

Four things that are easy to get wrong:

- **The root is a route.** `subpath` is empty at `/p/<id>/`, exactly as `ShareMetadataProvider.metaFor`
  receives it. A provider written as a lookup over your own slugs answers `false` there and 404s your own
  landing page. `PageRouteProviderHarness` probes the root whether you list it or not, for this reason.
- **Do not reuse `ShareMetadataProvider`.** It is the tempting shortcut and it is wrong: your subtree
  legitimately holds views with nothing to describe — `_search/<term>` and `_admin` return an empty
  `metaFor` *on purpose* — so reading "no share metadata" as "no page" 404s working routes. `metaFor` says
  how to describe a page; `hasRoute` says whether it exists.
- **It runs on a request**, like `SearchProvider`. A lookup, not a scan. A provider that throws is logged and
  skipped and the route answers `200` — a broken plugin must not turn a working page into a 404, so the
  failure is silent from a visitor's side and only your tests will tell you.
- **It decides the status line, not the body.** The shell renders its own not-found view; you do not need to
  produce one, and if you already render your own it keeps rendering.

The test is one line per subpath:

```java
var routes = new PageRouteProviderHarness(new WikiRoutes(store))
        .check("glossary/kraken", "glossary/tpyo", "_search/kraken", "_admin");

assertEquals(List.of("glossary/tpyo"), routes.notFound());
assertTrue(routes.servesRoot());
assertTrue(routes.failures().isEmpty());     // a throw means the host serves 200 anyway
```

**Core does not implement this yet** ([`mosaicast-core#89`](https://github.com/Mosaicast/mosaicast-core/issues/89)).
Write and test the provider now; the status line changes when core's half ships.

---

# 0.8.x → 0.9.0: the release that came out of using the contract

For plugin authors coming from `0.8.x`. **Small: a version bump, and one likely compile break in your
tests.** No plugin *code* written against 0.8.0 changes behaviour — everything in this release is new
surface.

**You have no choice about timing.** Core matches `major.minor` **exactly**, so the moment the host runs
`0.9.x` every `0.8.x` plugin is rejected at load, with the reason in the admin log viewer.

Coming from `0.7.x`? Do the [0.8.0 migration](https://github.com/Mosaicast/mosaicast-plugin-sdk/blob/v0.8.0/MIGRATION.md)
first — file storage and `ctx.links` — then come back here.

---

## Why this release exists

Everything here was found by *using* the contract: writing `mosaicast-plugin-sample` and
`mosaicast-plugin-wiki` against 0.8.0 and noticing what the plugin had to build for itself. It turned out
to be one gap repeated — **the host holds something a plugin can only re-implement badly on its own**, so
every plugin does, subtly differently.

The wiki grew a private `tags` column, because core's tags had no vocabulary and no plugin surface. It
projects episode snapshots into its own doc store on a schedule, because the frontend could not read one.
It is about to ship a private search box, because plugin content is invisible to the site's search. And
every plugin swallows a 404 with `catch(() => undefined)`, because the rejection was untyped — swallowing
the 500 and the 403 alongside it.

## Read this before you plan around it

**About half of the new surface is contract ahead of implementation.** `ctx.feeds`, `ctx.tags`, `ctx.docs`,
`ctx.route.query`, the typed errors and both extension points need core to build the other half; they are
tracked as issues on `mosaicast-core`. The SDK-side helpers — `iconCss`, `matchRoute`, the i18n formatters,
`defineManifest`, `declaredTypeFor`, `flushMockApi` and every test double — work the day this ships.

This is the deliberate order this time: the contract is settled first and core follows. It is worth knowing
which half you are writing against before you plan a release around it.

## 1. Bump the version you build against

`plugin.json`:

```diff
-  "platformApi": "0.8.0",
+  "platformApi": "0.9.0",
```

and your dependencies:

```diff
-  "@mosaicast/plugin-sdk": "^0.8.0"
+  "@mosaicast/plugin-sdk": "^0.9.0"
```

```diff
- implementation("dev.mosaicast:plugin-api:0.8.0")
+ implementation("dev.mosaicast:plugin-api:0.9.0")
```

`0.9.0` in the manifest is right even on a `0.9.1` host — the patch floats. Take the latest patch as your
*dependency*, then read [0.9.0 → 0.9.1](#090--091-real-404s-for-your-unknown-subpaths) once you are through
here.

## 2. The one compile break: a hand-built `ctx` in tests

`PluginContext` gained three members (`docs`, `feeds`, `tags`) and `PluginRoute` gained two (`query`,
`hash`). A test that constructs a context literal itself will stop compiling — and **`tsc --noEmit` is what
catches it, not your test runner**, which is the same trap 0.7.0 sprang.

The fix is not to add the members. It is to stop hand-building the context:

```diff
-const ctx = { scope: { type: 'site', id: 'main' }, episodes: [], user: null, /* … */ } as PluginContext;
+const ctx = makeMockCtx({ scope: { type: 'site', id: 'main' } });
```

`makeMockCtx` has covered every member since 0.1.0 and absorbs the next release too. A `route` override
still **merges** (since 0.7.1), so `route: { path: 'kraken' }` keeps working and keeps the `navigations`
recorder.

On the Java side there is **no** break: `FakePluginContext` gained `withTags(...)` as a chaining mutator
rather than a sixth constructor parameter, so every existing call still compiles and still means the same
thing.

## 3. Delete the code the SDK now owns

If your plugin has any of these, they can go — this is the point of the release.

**A `declaredType(file)` helper and an extension→MIME table.** `blobs.upload` normalises by default now:

```diff
-const stored = await blobs.upload(file, { declaredType: declaredType(file) });
+const stored = await blobs.upload(file);
```

Pass `{ declaredType: 'preserve' }` if you specifically want the browser's raw `file.type`.

**A `formatTime` / `formatBytes` pair.** Both were locale-blind, and the byte one almost certainly hardcodes
`.` as the decimal separator — which is wrong in `de`:

```diff
-<span>{formatTime(seconds)} · {formatBytes(quota.usedBytes)}</span>
+<span>{i18n.duration(seconds)} · {i18n.bytes(quota.usedBytes)}</span>
```

`duration` takes an ISO-8601 string too, so `i18n.duration(snapshot.duration)` works directly.

**An English-shaped plural.** `if (n === 1)` is wrong in Polish, Russian and Arabic:

```diff
-<span>{n === 1 ? t('moment_one') : t('moment_other', { count: n })}</span>
+<span>{i18n.plural('moments', n)}</span>
```

with catalog keys `moments.one` / `moments.other` (and `.few` / `.many` where a locale needs them; only
`.other` is required).

**A hand-rolled icon CSS file.** Check yours for the bug first — if the fallback is `mask-image: none`, your
plugin ships **solid squares** to anyone on a host that predates an icon you used:

```diff
-style.textContent = MY_ICON_CSS;   // 113 lines, three rules to remember
+style.textContent = iconCss(['star', 'clock']);
```

**A `flush()` helper doing microtask hops.** `flushMockApi(ctx.api)` waits for the calls and then drains
the hops behind them, instead of you guessing how many `await Promise.resolve()` a component needs.

**A test asserting `nav[]` matches your in-page tab bar.** Generate `plugin.json` from a
`defineManifest({ … })` module and the two stop being separate copies. Note what still legitimately
differs: `path`/`icon`/`role` are pinned between them, but the host's menu label **cannot be translated** —
core has no plugin catalogs — while your in-page tab can.

**A doc-store path built by hand.** `ctx.docs` validates the key and resolves the singletons:

```diff
-await ctx.api.put(`data/user/me/${encodeURIComponent(key)}`, marks);
+await ctx.docs.put('self', key, marks);
```

**And a projection of host data into your own doc store**, if you have one — see §5.

## 4. Optional: stop swallowing real failures

The 404-swallowing `catch` is worth revisiting wherever you have one, because it is also catching the 500
and the 403:

```diff
-ctx.api.get<Stats>(path).then(setStats).catch(() => setStats(undefined));
+ctx.api.getOrNull<Stats>(path).then(setStats);   // null when absent; a real failure still rejects
```

And where you do need to branch on a refusal:

```ts
import { isPluginApiError } from '@mosaicast/plugin-sdk';

catch (e) {
  if (isPluginApiError(e) && e.status === 403) { /* backendOwned, or the write floor */ }
  else throw e;
}
```

Use `isPluginApiError`, not `instanceof` — the error comes from the host across a bundle boundary.

## 5. Optional: read snapshots instead of copying them (`ctx.feeds`)

If your plugin keeps a projection of episode titles and artwork, this is what it was working around:

```ts
const cards = await ctx.feeds.displayMany(ctx.episodes.slice(0, 20));   // one request, not N
const snap = cards[slug];
if (!snap) return;                       // filtered out for this visitor — normal, not an error
render(snap.title, resolveArtwork(snap));
```

The snapshot is **not authoritative** — the host overwrites it on every feed refetch, which is exactly why
reading it live beats copying it. Cache per render, never per install. A `WITHDRAWN` or tier-gated episode
is **absent** from the answer rather than redacted, so a missing key must not be treated as a failure.

Deleting the projection usually deletes a scheduled ingest and a `backendOwned` key with it.

## 6. Optional: use the site's tags (`ctx.tags`)

Declare what you want — reading and tagging your own subjects is one thing, tagging **episodes** is another:

```json
"tags": { "readsVocabulary": true, "writesEpisodes": false }
```

`ctx.tags` (TS) and `ctx.tags()` (Java) are **`null`** without it. Check before use; TypeScript will make
you.

```ts
for (const t of await tags.all()) suggest(t.label, t.tag);   // instead of a free-text box
await tags.tagSubject(`page:${slug}`, 'Maritime Lore');       // your namespace, your key
a.href = ctx.links.feed('main', { tag: 'maritime lore' });    // a wiki tag now links to the feed view
```

Migrating a private tag column: send your existing strings through `tagSubject` and read back the canonical
keys. The host normalises (trim, collapse whitespace, casefold), so `Maritime` and `maritime ` converge on
one tag — expect your column's near-duplicates to merge, and store the key from then on.

**Tagging an episode is a capability.** It changes the shell's filter options *and* what core recommends
beside that episode, which is why it needs the second flag. You cannot delete a tag from the vocabulary,
rename one, or remove another writer's assignment — including the feed's.

## 7. Optional: the two new extension points (Java)

Both are optional `ExtensionPoint`s alongside `PluginBackend`, like `SitemapProvider`. Add the class to
`backend.extensions` in your manifest; there is no declaration beyond that.

**`SearchProvider`** — if you have a private search box, it should become one of these. **Read the access
note before you implement it**: this is the one place where the host cannot filter for you, because it has
no model of your objects. A provider that returns a draft page to an anonymous visitor is a leak nothing
else catches. `role` is `null` for anonymous, and `SearchProviderHarness` calls you once per role so the
test is one line.

**`UserDataHandler`** — implement it if you hold personal data in **schema columns or blobs**. Core drops
`data/user/<id>/…` itself (the `USER` scope is host-owned), but it has no idea which of your columns is a
person. `eraseUser` may erase or pseudonymise — your call, and the host cannot make it. It **must be
idempotent**, because a failed deletion is retried; `UserDataHandlerHarness.eraseTwice(userId)` is that test
and it is the one authors skip.

## What did not change

- `ctx.api`, `ctx.store()`, `ctx.schema`, `ctx.blobs`, `ctx.links`, `ctx.consent`, `ctx.route.navigate`,
  `ctx.player`, `ctx.progress`, `ctx.theme` — all unchanged.
- Both access floors, `backendOwned`, and the `USER`-scope exemptions.
- `FakePluginContext`'s existing constructors.
- The manifest is still **owned and validated by the host**. `PluginManifest` is a type for your editor;
  if it and core disagree, core wins.
