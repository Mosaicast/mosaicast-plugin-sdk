# Migrating a plugin to `platformApi` 0.7.0

For plugin authors coming from `0.6.x`. **Small: a version bump, plus one line in any test that builds its
own `route`.** Both additions in this release are new surface — no plugin *code* written against 0.6.0
changes behaviour.

The one thing that stops compiling is a test: `ctx.route` gained a required `navigate`, so an override like
`route: { path: 'x', onChange: () => () => {} }` no longer satisfies `PluginRoute`. See step 4. Your test
runner will not catch it — only `tsc --noEmit` will, which is the argument for having a `typecheck` script
at all.

**You have no choice about timing.** Core matches `major.minor` **exactly**, so the moment the host runs
`0.7.0` every `0.6.x` plugin is rejected at load, with the reason in the admin log viewer.

Coming from `0.5.x`? Do the [0.6.0 migration](https://github.com/Mosaicast/mosaicast-plugin-sdk/blob/v0.6.0/MIGRATION.md)
first — `data.backendOwned`, the keys only your backend may write — then come back here.

---

## Why this release exists

Two things a plugin could not do, both found while building the wiki.

**A frontend could not read the tables the platform provisioned for it.** The schema provider shipped its
provisioning half: your manifest declares entities, the host creates `plugin_<id>_<entity>` tables with the
indexes you asked for, and your Java backend reads them through `SchemaStore`. There was no HTTP surface,
so your Web Component could not read one row — including the full-text search the platform had built a GIN
index for. The workarounds were projecting the whole corpus into doc keys and re-implementing ranked search
in the browser, or using the doc store as a request channel. Neither is something an author should build.

**A page plugin could not navigate.** `ctx.route` told you where you are; it gave you no way to go
anywhere. Following a link inside your own `/p/<pluginId>/` subtree meant an `<a href>` and a full document
load — the shell, the plugin registry and every plugin bundle re-fetched per click.

## 1. Bump the version you build against

`plugin.json`:

```diff
-  "platformApi": "0.6.0",
+  "platformApi": "0.7.0",
```

`build.gradle.kts`:

```diff
-  compileOnly("dev.mosaicast:plugin-api:0.6.0")
-  testImplementation("dev.mosaicast:plugin-testkit:0.6.0")
+  compileOnly("dev.mosaicast:plugin-api:0.7.0")
+  testImplementation("dev.mosaicast:plugin-testkit:0.7.0")
```

`package.json`: `"@mosaicast/plugin-sdk": "^0.7.0"` (take `0.7.1` if you can — it makes step 4 a no-op).

That is the whole required migration. The rest of this document is what you can now do.

## 2. Read your schema tables from the frontend (`ctx.schema`)

Only if your manifest declares `storage.schema`. `ctx.schema` is **`null`** for a doc-store plugin —
the same shape as the Java `ctx.schema()`, so TypeScript makes you handle it:

```ts
if (!ctx.schema) return;   // doc-store plugin: nothing to query

interface Page { id: number; slug: string; title: string; markdown: string; updatedAt: string }

const hits = await ctx.schema.search<Page>('page', 'markdown', term, {
  where: [{ field: 'published', op: 'eq', value: true }],
  orderBy: [{ field: 'updatedAt', direction: 'desc' }],
  size: 20,
});
```

`select` / `search` / `find` / `count` mirror `SchemaStore`, and a query is described the same way
`Criteria` describes one — entity and field names come from **your manifest**, values are never
interpolated. Paging is `page`/`size` (from 0, default 50, capped at 200 by the host) in the same envelope
the doc surface uses.

Two differences from the doc client worth knowing: `find` resolves **`null`** for a missing row rather than
rejecting, and an entity or field you never declared is refused by the host (404 and 400 respectively)
rather than returning nothing.

**Reads only.** There are no schema writes over HTTP, and this is deliberate: a v1 plugin authors no HTTP
routes, so no plugin code runs at request time to enforce slug uniqueness, append a revision atomically or
reject malformed input. Your backend stays the only writer of relational truth — a frontend that must write
puts a document in the doc store and your backend ingests it in `onSchedule(...)`. Such a write is
therefore eventually consistent; design the UI for it (optimistic render, or a "saving…" state that
resolves on the next read).

## 3. Navigate inside your own page subtree (`ctx.route.navigate`)

Only if you declare a `page` slot.

```diff
-<a href="/p/wiki/glossary/kraken">The Kraken</a>
+<a href="/p/wiki/glossary/kraken" onclick="…">The Kraken</a>
```

```ts
link.addEventListener('click', (e) => {
  e.preventDefault();
  ctx.route.navigate('glossary/kraken');           // relative to /p/<pluginId>/
});

ctx.route.navigate('index', { replace: true });    // no back-button step
```

Keep the real `href` on the anchor: middle-click, "open in new tab" and crawlers all need it, and it is
what makes your pages shareable. `navigate` only takes over the plain-click path.

**If you were using `history.pushState` plus a synthetic `popstate`, replace it.** It happens to work
against the host's current router and is not part of the contract; `navigate` is the supported path and
cannot be aimed outside your own namespace.

## 4. Tests

**A `route` override needs `navigate`.** This is the only compile break in the release, and it hits any
test that pins a subpath — which for a `page` plugin is most of them:

```diff
-route: { path: 'kraken', onChange: () => () => {} },
+route: { path: 'kraken', onChange: () => () => {}, navigate: () => {} },
```

**On `0.7.1` and later, drop the stubs instead.** The override merges over the default there, so the whole
fix is nothing:

```ts
const ctx = makeMockCtx({ route: { path: 'kraken' } });   // navigate still records into ctx.navigations
```

Either way, replacing `navigate` yourself opts out of the recorder — the one case where `ctx.navigations`
stays empty.

`makeMockCtx` also now defaults `schema` to `null`, and records `navigate` calls:

```ts
const schema = makeMockSchema({
  page: [{ id: 1, slug: 'kraken', title: 'The Kraken', markdown: 'a big squid' }],
});
const ctx = makeMockCtx({ schema, route: { path: 'kraken' } });   // 0.7.1: the rest is inherited

await mount(ctx);
expect(schema.queries[0]).toMatchObject({ method: 'search', entity: 'page' });

const clicking = makeMockCtx({ schema });
await mount(clicking);
clickLink('The Kraken');
expect(clicking.navigations).toEqual([{ subpath: 'glossary/kraken', replace: false }]);
```

`makeMockSchema`'s `search` is a **case-insensitive substring match, not Postgres full-text search** — it
cannot reproduce stemming or `ts_rank` ordering. Use it to prove your component renders hits and handles
none; prove the searching itself against the host.

## 5. Rebuild and re-test

```bash
./gradlew build            # against plugin-api/testkit 0.7.0
npm ci && npm run build
./build.sh                 # → dist/
```

Then copy `dist/` into `MOSAICAST_PLUGINS_DIR` and check the admin log viewer on startup.

---

## Quick checklist

- [ ] `platformApi` is `0.7.0`, and both Java artifacts and the npm package are on `0.7.0`
- [ ] `tsc --noEmit` runs clean — every hand-built `route` override now needs `navigate`
- [ ] Every `ctx.schema` use is behind a `null` check
- [ ] No frontend code expects a schema **write** — the backend still writes, the frontend still reads
- [ ] Internal page links call `navigate` and keep their `href`
- [ ] No `history.pushState` / synthetic `popstate` left in the plugin
- [ ] Tests pass against `plugin-testkit` / `makeMockCtx` `0.7.0`
