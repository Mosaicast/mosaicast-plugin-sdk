# Migrating a plugin to `platformApi` 0.6.0

For plugin authors coming from `0.5.x`. **Small: a version bump, and one manifest line if your backend
computes anything.** Nothing you wrote against 0.5.0 stops compiling.

**You have no choice about timing.** Core matches `major.minor` **exactly**, so the moment the host runs
`0.6.0` every `0.5.x` plugin is rejected at load, with the reason in the admin log viewer.

Coming from `0.4.x`? Do the [0.5.0 migration](https://github.com/Mosaicast/mosaicast-plugin-sdk/blob/v0.5.0/MIGRATION.md)
first — the `USER` scope and the `data` block that release introduced — then come back here.

---

## Why this release exists

A re-audit of core found the half that `USER` scope did not close. `USER` fixed one visitor reaching
another's data. **Shared scopes never had an ownership binding at all:** authorization is per *plugin*, not
per *document*, so any caller above your `writableBy` floor may overwrite or delete any key in any
`site`/`feed`/`season`/`episode` scope. The host cannot tell your backend's scheduled write from a `curl` —
same table, same key, no author recorded.

The demonstrated attack, against the reference plugin:

```bash
$ curl -b cookie.podcaster -X PUT .../api/plugins/sample/data/site/main/stats \
       -H "X-XSRF-TOKEN: $XT" -d '{"totalEpisodes":9999,"totalFavourites":1337}'
204
$ curl .../api/plugins/sample/data/site/main/stats     # anonymous
{"totalEpisodes":9999,"totalFavourites":1337}
```

`stats` is written by the sample's *scheduled backend task*. The plugin was not misconfigured — its floors
did exactly what they say. There was simply no way to express "this key is the backend's".

## 1. Bump the version you build against

`plugin.json`:

```diff
-  "platformApi": "0.5.0",
+  "platformApi": "0.6.0",
```

`build.gradle.kts`:

```diff
-  compileOnly("dev.mosaicast:plugin-api:0.5.0")
-  testImplementation("dev.mosaicast:plugin-testkit:0.5.0")
+  compileOnly("dev.mosaicast:plugin-api:0.6.0")
+  testImplementation("dev.mosaicast:plugin-testkit:0.6.0")
```

`package.json`: `"@mosaicast/plugin-sdk": "^0.6.0"`.

## 2. Declare the keys your backend authors

**Does your backend write to a shared scope?** Anything in `register()` or `onSchedule(...)` that calls
`ctx.store().put(Scope.site()/feed()/season()/episode(), …)`. If so, those keys are forgeable today.
Declare them:

```diff
 "data": {
   "readableBy": "anonymous",
-  "writableBy": "podcaster"
+  "writableBy": "podcaster",
+  "backendOwned": ["stats", "agg:*"]
 }
```

- An entry is an **exact key**, a **prefix ending in `*`**, or the **bare `*`** ("everything I store is
  computed; clients read only"). The grammar is `DocStore.BACKEND_OWNED_PATTERN` =
  `^(\*|[A-Za-z0-9._:-]{1,200}\*?)$` — a `*` in the middle, or an empty entry, is rejected at load.
- A client `PUT`/`DELETE` to a matching key becomes **403**, with its own message so you can tell it from a
  role-floor refusal. **Reads are unaffected**, still governed by `readableBy`.
- **`ctx.store()` is unaffected.** Your backend keeps writing those keys — that is the entire point.

### Three things that will otherwise surprise you

**It does not clean up.** Declaring a key `backendOwned` refuses *new* client writes; it does not remove a
value someone forged before the declaration existed. If your backend only writes on a schedule, the bogus
document is served until the next tick. Write your computed keys on startup too:

```diff
 public void register(PluginContext ctx) {
+    recomputeStats(ctx);                                   // repairs on every restart
     ctx.onSchedule(Duration.ofMinutes(15), () -> recomputeStats(ctx));
 }
```

**It does not apply to `user` partitions.** The backend cannot write one at all, so a declaration there
would make a key nobody can write. Even a bare `*` leaves `data/user/me/…` to its owner.

**A bare `*` makes `writableBy` vestigial** for shared scopes — and it never governed `user` scopes — so
with `backendOwned: ["*"]` nothing hits your write floor at all. Declare a sane one anyway; it is still
required and still may not be `anonymous`.

### Which keys are *not* candidates

Anything a client legitimately writes: a vote, a mark, a submitted answer. Those belong to whoever wrote
them — and if that is a person, they belong in the `USER` scope (0.5.0), not in a shared scope with a
declaration bolted on.

## 3. Prove it in your tests

`InMemoryDocStore.withBackendOwned(...)` enforces the declaration, using the `asUser(...)` view as the
stand-in for a client request — which covers every client write, since `writableBy` may not be `anonymous`:

```java
InMemoryDocStore store = new InMemoryDocStore().withBackendOwned("stats");
InMemoryDocStore client = store.asUser(UUID.randomUUID());

plugin.register(ctx);                                              // backend writes stats

assertThrows(IllegalStateException.class,
        () -> client.put(Scope.site(), "stats", forged));          // the host answers 403
assertEquals(computed, client.get(Scope.site(), "stats", Stats.class).orElseThrow());
```

A malformed pattern throws from `withBackendOwned` itself, so a typo in the declaration fails in your tests
instead of at load time in production.

## 4. Frontend

`PluginDataDeclaration` types the whole `data` block if you want the JSON checked while you write it:

```ts
import type { PluginDataDeclaration } from '@mosaicast/plugin-sdk';
```

Documentation only — the host owns and validates the manifest — but the block now carries a security
control, and a typo in `backendOwned` protects nothing while saying nothing.

Otherwise nothing changes on the frontend, except that a `PUT` to one of your own backend-owned keys now
rejects with a 403 problem. If a component was writing a key your backend also computes, that was a race
you were losing anyway: move the write to the backend, or the key out of the declaration.

## 5. Rebuild and re-test

```bash
./gradlew build            # against plugin-api/testkit 0.6.0
npm ci && npm run build
./build.sh                 # → dist/
```

Then copy `dist/` into `MOSAICAST_PLUGINS_DIR` and check the admin log viewer on startup.

---

## Quick checklist

- [ ] `platformApi` is `0.6.0`, and both Java artifacts and the npm package are on `0.6.0`
- [ ] Every shared-scope key your backend writes appears in `data.backendOwned`
- [ ] Those keys are (re)written in `register()`, not only on a schedule
- [ ] No client-written key is in the list — per-person data belongs in the `USER` scope instead
- [ ] A test asserts the forged client write is refused
- [ ] Tests pass against `plugin-testkit` / `makeMockCtx` `0.6.0`
