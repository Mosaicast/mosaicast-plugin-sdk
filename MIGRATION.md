# Migrating a plugin to `platformApi` 0.13.0

Six migrations in one file. **On `0.12.x`?** Read the next section and stop. **On `0.11.x`?** Do
[0.11.x → 0.12.0](#011x--0120-saying-what-language-your-pages-are-in) first, then work upward.

---

# 0.12.x → 0.13.0: rendering the people behind the UUIDs

**This one you must do.** `platformApi` matches on `major.minor`, so a plugin declaring `0.12.x` is
rejected by a `0.13.0` host. Re-declare, rebuild, reinstall.

```diff
  // plugin.json
- "platformApi": "0.12.0",
+ "platformApi": "0.13.0",
```

```diff
- implementation("dev.mosaicast:plugin-api:0.12.0")
+ implementation("dev.mosaicast:plugin-api:0.13.0")
- "@mosaicast/plugin-sdk": "^0.12.0"
+ "@mosaicast/plugin-sdk": "^0.13.0"
```

**Java plugins have nothing else to do.** `PluginContext` gained a method, but plugins consume that
interface rather than implement it, and `FakePluginContext` implements the new one for you.

## The one compile break: a hand-built `ctx` in a TypeScript test

`ctx.user` gained `displayName` and `avatarUrl`. A test that spells the user out fails to type-check:

```diff
  const ctx = makeMockCtx({
-   user: { id: 'u1', role: 'podcaster' },
+   user: { id: 'u1', role: 'podcaster', displayName: 'Ana', avatarUrl: '/api/users/u1/avatar' },
  });
```

Anonymous is still `user: null`, and that is still the default — nothing to change there.

## What the release adds, and why you might want it

A backend that calls `queryAcrossUsers` gets `OwnedDocEntry(userId, …)`: UUIDs and a document. Until
0.13.0 a leaderboard built from that had ids and no way to draw a person, and the two workarounds were
both bad — show raw UUIDs, or copy display names into the plugin's own store.

`ctx.users` is the fix, and it is a **lookup rather than a wider `ctx.user`**:

```ts
const dir = ctx.users;
if (!dir) return;                      // no `identity` block in this plugin's manifest

const board = await ctx.docs.get<{ userId: string; score: number }[]>('site', 'agg:leaderboard');
const people = await dir.resolve((board ?? []).map((row) => row.userId));
const byId = new Map(people.map((u) => [u.id, u]));
```

```java
List<UserRef> people = ctx.users().resolve(ids);   // null unless the manifest declares `identity`
```

Declare it, or the handle is `null` and the endpoint 404s:

```diff
  // plugin.json
+ "identity": { "resolvesUsers": true },
```

Declared and never derived even though your plugin already *has* the ids: what is being granted is not
access to the UUIDs but the turning of them into people, and that is what an operator reads off your
manifest before installing.

### The three rules that will catch you

**1. Absent, not redacted — and therefore not index-aligned.** An id that is unknown, erased or
pseudonymised is simply missing from the result. There is no `null` element to skip and no tombstone to
recognise, so the array may be shorter than what you asked for:

```ts
const found = await dir.resolve(['u-1', 'u-gone']);
found[1];                              // undefined — do NOT read positionally
byId.get(row.userId)?.displayName ?? 'Former listener';   // do this
```

That is what lets a leaderboard row outlive its author: the aggregate stays, the person becomes whatever
placeholder you render.

**2. Store UUIDs, resolve at render. Never persist a display name.** A copied name survives the rename
meant to shed it and the erasure meant to end it, and core cannot reach inside your storage to fix either
— it provisioned your tables without ever learning which column holds a person. **The host cannot enforce
this one.** Keep the id in your documents and resolve at the point you draw the row.

**3. `avatarUrl` is finished.** Always `/api/users/{id}/avatar`, always host-relative, always populated —
every user has an avatar, generated from their UUID when there is no provider picture. There is no null
case, no empty string and no fallback to write. Put it in an `src`.

It also **resolves rather than enumerates**: there is no list call and there will not be one. You may ask
only about ids you already hold, which you only come by through your own scope.

### Test it against the absent case

Both test kits model absence rather than hiding it, which is the point:

```ts
const users = makeMockUsers({ 'u-1': 'Ana' });
const ctx = makeMockCtx({ users });
users.forget('u-1');                   // the erased-author case
```

```java
FakeUsers users = new FakeUsers().withUser(ana, "Ana", Role.FAN);
FakePluginContext ctx = new FakePluginContext().withUsers(users);
users.withoutUser(ana);
```

`ctx.users` defaults to `null` in both, so a test that never passes one keeps checking that your component
survives a manifest with no `identity` block.

---

# 0.11.x → 0.12.0: saying what language your pages are in

**This one you must do.** `platformApi` matches on `major.minor`, so a plugin declaring `0.11.x` is
rejected by a `0.12.0` host. Re-declare, rebuild, reinstall.

```diff
  // plugin.json
- "platformApi": "0.11.0",
+ "platformApi": "0.12.0",
```

```diff
- implementation("dev.mosaicast:plugin-api:0.11.0")
+ implementation("dev.mosaicast:plugin-api:0.12.0")
- "@mosaicast/plugin-sdk": "^0.11.0"
+ "@mosaicast/plugin-sdk": "^0.12.0"
```

**That is the whole migration for most plugins.** `OgMeta` and `SitemapUrl` each gained a component, which
breaks binary compatibility — so you must rebuild — but the old shapes survive as real constructors, so
there is nothing to edit:

```java
new OgMeta(title, description, imageUrl);       // still compiles: "whatever language the host resolved"
new SitemapUrl(loc, lastModified);              // still compiles: no translation group, as before
```

You only have a code change if you deconstruct these records in a pattern (`case OgMeta(var t, var d, var
i)`) or call a canonical constructor reflectively.

## What the release adds, and why you might want it

A page can now be requested as `?lang=de`, and `sitemap.xml` emits `hreflang` alternates with `x-default`
on the bare URL. Until 0.12.0 a plugin could not join in: the host **deliberately emits no alternates** for
plugin entries rather than assuming the site's UI languages apply to content it cannot read.

### `OgMeta.locale` — if your page is in one fixed language

Leave it `null` if your pages are written in whatever language the shell is showing; the host's
request-resolved locale is already right, and that is most plugin pages. Say it if your page's text is in
one language whoever asks for it:

```java
public Optional<OgMeta> metaFor(String subpath) {
    var page = pages.find(slugOf(subpath));
    return page.map(p -> new OgMeta(p.title(), p.excerpt(), p.imageUrl(), p.locale()));
}
```

**It is a claim about the text in that record.** If you fell back to your default language because you had
no translation for the requested locale, the honest value is your default's code — not the code that was
asked for.

### `SitemapUrl.alternates` — locale code → path

```java
var group = Map.of("en", "/p/wiki/article", "de", "/p/wiki/artikel");
return List.of(
        new SitemapUrl("/p/wiki/article", updatedAt, group),
        new SitemapUrl("/p/wiki/artikel", updatedAt, group),
        new SitemapUrl("/p/wiki/changelog", updatedAt));            // nothing translated
```

Three rules, and the first is enforced at construction:

1. **The map must contain an entry pointing at `loc` itself.** Not redundancy — it is you naming the
   language your own page is written in, which the host has no way to know and will not guess. Leave it out
   and the constructor throws.
2. **Values are paths, not URLs, and never carry `?lang=` yourself.** The host adds the parameter, leaves
   the site default on the *bare* URL, points `x-default` there, and makes the group reciprocal. It also
   confines every alternate to your own `/p/<pluginId>/` namespace, exactly as it does `loc`.
3. **List a language only if that page is really written in it.** Serving your default language to a reader
   who asked for German is a kindness to a visitor; telling a crawler a translation exists and handing it
   the original is not.

If you render **one path per language**, map every locale to the same `loc` — a map with one distinct
value:

```java
new SitemapUrl(loc, updatedAt, Map.of("en", loc, "de", loc));
```

### Check it with `SitemapProviderHarness`

The failures here are silent: the host *drops* what it will not accept, so the pages simply never appear.

```java
var sitemap = new SitemapProviderHarness("wiki", new WikiSitemap(store)).collect();

assertTrue(sitemap.problems().isEmpty());                       // nothing dropped or contradicted
assertEquals(List.of("de", "en"), sitemap.locales("/p/wiki/article"));
```

It catches the one thing no single entry can see: two entries in **one** group declaring **different**
groups, which is what a half-updated slug map looks like.

## What did *not* change

`ShareMetadataProvider` and `SitemapProvider` keep their signatures — same methods, same return types.
Nothing on the TypeScript side changed at all; `@mosaicast/plugin-sdk` moves to `0.12.0` because the two
packages share one version anchor.

---

# 0.10.x → 0.11.0: declaring what external services you use

**This one you must do.** `platformApi` matches on `major.minor`, so a plugin declaring `0.10.x` is
rejected by a `0.11.0` host. Re-declare, rebuild, reinstall.

```diff
  // plugin.json
- "platformApi": "0.10.0",
+ "platformApi": "0.11.0",
```

```diff
- implementation("dev.mosaicast:plugin-api:0.10.0")
+ implementation("dev.mosaicast:plugin-api:0.11.0")
- "@mosaicast/plugin-sdk": "^0.10.0"
+ "@mosaicast/plugin-sdk": "^0.11.0"
```

## The one that will catch you: `ctx.translation` goes `null` until you declare

**There is no compile error for this.** `translation` was already `TranslationClient | null`, so nothing
in the type system changes — the handle simply becomes `null` at runtime, and you find out because a
translate button stopped working. That is the worst shape a change can have, which is why it leads this
section.

If your plugin used `ctx.translation` or `ctx.translation()` on 0.10.0, add the block:

```diff
  // plugin.json
+ "external": { "kinds": ["translation"], "usedBy": "podcaster" }
```

Nothing else changes. Your existing null-check is already the right code — it now covers two reasons
instead of one:

1. **Your manifest did not ask** (the new gate).
2. **The operator configured no provider** (the 0.10.0 gate), which is still every site by default.

They are indistinguishable on purpose: whether you declared is a static fact about a file you wrote, so
the contract does not spend a discriminator on it. **Unexpected `null`? Check the manifest before the
admin panel.**

## Choosing `usedBy`

It is the lowest role that may trigger a call **from your UI**, and it defaults to `podcaster` — the same
floor `data.writableBy` uses. Leave it alone unless you have a reason.

```jsonc
"external": { "kinds": ["translation"], "usedBy": "podcaster" }  // the default, spelled out
"external": { "kinds": ["translation"], "usedBy": "fan" }        // fans may translate; you are paying for it
"external": { "kinds": ["translation"], "usedBy": "anonymous" }  // legal, and almost always wrong
```

An external call spends the operator's money on a metered API. `anonymous` makes that an open spending
endpoint reachable by anyone who can load the page — ask for it only if you can say why an operator would
want to pay for a stranger's translation.

Two things to know about how it behaves:

- **A non-null handle is not permission.** The host enforces the floor at the call, so a visitor below it
  holds a handle whose `translate()` rejects with **403**. Handle it like the 429 and 503 you already do.
- **It does not reach your backend.** `register()` runs at startup and `onSchedule` on a timer — no
  visitor, no role. On the Java side, declaring the kind is the whole gate, and `usedBy` is ignored.

## What did *not* change

`TranslationRequest`, `TranslationResult`, `TranslationClient`, `TranslationException`, `ctx.locale` and
every test double keep their 0.10.0 shape. `FakePluginContext.withTranslation(...)` and
`makeMockTranslation()` are unchanged — a `null` translation in a fake now stands in for either gate,
which is exactly what a plugin observes in production. There is no new SDK type on the Java side: the
manifest has never had one.

---

# 0.9.x → 0.10.0: the site's languages, and translation

**This one you must do.** `platformApi` matches on `major.minor`, so a plugin declaring `0.9.x` is rejected
by a `0.10.0` host. Re-declare, rebuild, reinstall.

```diff
  // plugin.json
- "platformApi": "0.9.1",
+ "platformApi": "0.10.0",
```

```diff
- implementation("dev.mosaicast:plugin-api:0.9.1")
+ implementation("dev.mosaicast:plugin-api:0.10.0")
- "@mosaicast/plugin-sdk": "^0.9.1"
+ "@mosaicast/plugin-sdk": "^0.10.0"
```

## The one compile break: a hand-built `ctx` in tests

`PluginContext` gained `translation`, and `ctx.locale` gained `available()` and `content()`. If your tests
build a context literal by hand, they stop compiling — **and `tsc --noEmit` is what tells you, not the test
runner**, which is the same trap 0.9.0 had. Use `makeMockCtx()`, which fills both:

```diff
- const ctx = { scope: …, locale: { current: () => 'en', onChange: () => () => {} }, … } as PluginContext;
+ const ctx = makeMockCtx({ scope: … });
```

`createPluginI18n(catalogs, ctx.locale)` is **unchanged** — it now asks for only `current` and `onChange`,
so a two-method double you pass it directly keeps working.

On the Java side there is no break: `FakePluginContext` grew `withLocales(...)` / `withTranslation(...)` as
chaining mutators rather than constructor parameters.

## What you may now want to use

**`ctx.locale.content()`** if your plugin authors anything per language. Core made languages a runtime
registry, so the list is the operator's and it changes: an admin drops a catalog in and enables it. Build
your editor's tabs from `content()`, not `available()` — a site can require a Dutch imprint with an
English-only UI, and `available()` would leave that language out. On the backend, check writes with
`ctx.locales().isContentLocale(locale)`; the browser's list is a hint, what reaches storage is input.

**`ctx.translation`** if you have text worth translating. `null` when the admin configured no provider,
which is every site until somebody chooses one — so `if (ctx.translation?.available())`, and handle the
failure rather than falling back to the untranslated string. Java's `TranslationException` is checked and
carries `reason()` / `retryable()`.

Two things to get right, because neither is obvious:

- **Markdown is not a format.** `'text'` and `'html'` are. Markdown sent as text comes back with mangled
  links and code fences.
- **Machine output is a draft.** Store it flagged and let a human confirm it, the way core's legal-page
  prefill hands the admin something unsaved.

**Core does not implement `ctx.translation` yet** — it is `null` on every current host. The contract ships
first, as `0.9.0`'s did; the locale lists are live.

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
