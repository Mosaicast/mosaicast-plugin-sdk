# Migrating a plugin to `platformApi` 0.5.0

For plugin authors coming from `0.4.x`. **The code change is small — an afternoon at most. Moving your
existing per-user data is the part that needs thought, and it is step 4.**

**You have no choice about timing.** Core matches `major.minor` **exactly**, so the moment the host runs
`0.5.0` every `0.4.x` plugin is rejected at load, with the reason in the admin log viewer.

Coming from `0.3.x`? Do the [0.4.0 migration](https://github.com/Mosaicast/mosaicast-plugin-sdk/blob/v0.4.0/MIGRATION.md)
first — Jackson 3 and the manifest `consent` schema — then come back here.

---

## Why this release exists

A white-box audit of core found that the doc store had no notion of *whose* a document is. `scopeType`,
`scopeId` and `key` all come from the client, the only gate was a per-plugin role floor, and scope ids are
public slugs — so any caller above that floor could read, overwrite or delete any key in any scope,
**including another user's**. Nothing had to be guessed.

The advice in the contract was part of the bug: the SDK told you to model per-user data as per-user *keys*
(`mark:<userId>:cell`). A key is client input, so that put an access-control decision exactly where the
host could not check it. 0.5.0 replaces the advice with a scope the host owns.

## 1. Bump the version you build against

`plugin.json`:

```diff
-  "platformApi": "0.4.0",
+  "platformApi": "0.5.0",
```

`build.gradle.kts`:

```diff
-  compileOnly("dev.mosaicast:plugin-api:0.4.0")
-  testImplementation("dev.mosaicast:plugin-testkit:0.4.0")
+  compileOnly("dev.mosaicast:plugin-api:0.5.0")
+  testImplementation("dev.mosaicast:plugin-testkit:0.5.0")
```

`package.json`: `"@mosaicast/plugin-sdk": "^0.5.0"`.

## 2. Declare your data floor in the manifest — **this one breaks public plugins**

New block, validated at load:

```json
"data": { "readableBy": "fan", "writableBy": "podcaster" }
```

Values are `anonymous | fan | podcaster | admin`; `writableBy` may not be `anonymous`.

**Read this even if you skim the rest.** Core used to derive the read floor from the *minimum* `visibleTo`
across all your slots — so a plugin with one anonymous display slot and one admin-only slot served its
**entire** doc store to anonymous callers. That inference is gone, and nothing replaces it silently:

> **If you omit the block, `readableBy` falls back to your *write* floor — not to anonymous.**

So a plugin that has any `visibleTo: "anonymous"` slot and no `data` block **loses its anonymous reads**:
requests that returned 200 yesterday return **403** today. That is the fix working as intended, not a
regression — but it is a behaviour change, and it will look like "my public component stopped loading" if
you meet it in production instead of here.

**If your data really is public, say so:**

```diff
   "slots": [ { "scope": "episode", "element": "my-card", "placement": "main", "visibleTo": "anonymous" } ],
+  "data":  { "readableBy": "anonymous", "writableBy": "fan" },
```

There is no `data` block that restores the old *derived* behaviour, and that is deliberate: the old
behaviour is the vulnerability. Pick the floor your data actually needs.

And the distinction the old behaviour blurred: **`visibleTo` on a slot governs rendering only.** It never
governed data access.

**The `USER` scope ignores `readableBy` in both directions.** No floor makes another user's partition
readable, and none stands between a caller and their own — an authenticated user of any role reads
`data/user/me/…`, including one below your declared read floor. An anonymous caller has no partition at
all, so that request is a 401 regardless.

## 3. Fix what no longer compiles

Three small ones:

```diff
- switch (scope.type()) { case SITE -> …; case FEED -> …; case SEASON -> …; case EPISODE -> …; }
+ switch (scope.type()) { case SITE -> …; case FEED -> …; case SEASON -> …; case EPISODE -> …;
+                         case USER -> …; }
```

```diff
- ctx.store().put(Scope.site("main"), key, value);   // removed; deprecated since 0.3.0
+ ctx.store().put(Scope.site(), key, value);
```

A hand-rolled `DocStore` (a test double written by hand rather than `InMemoryDocStore`) must now implement
`queryAcrossUsers(String)`. Switching to `InMemoryDocStore` is the cheaper fix.

## 4. Move your per-user data

**This is the real work, and nothing does it for you.** Existing per-user data lives *inside keys* under
entity scopes, and the host cannot know the convention that put it there — so there is no automatic
migration, and that data stays exactly as exposed as it is today until you move it.

### The new shape

```diff
- ctx.api.put(`data/episode/${ctx.scope.id}/mark:${ctx.user.id}:b3`, true);
+ ctx.api.put(`data/user/me/mark:${ctx.scope.id}:b3`, true);
```

- The scope id is the literal `me` (`SELF_SCOPE_ID` in TS, `Scope.SELF_ID` in Java), resolved server-side
  from the session. **Any other `user` id is a 400**, never a silent substitution; an anonymous `user`
  request is a **401** whatever your `readableBy` says.
- In Java the canonical `Scope` constructor normalizes any `USER` id to the sentinel, and there is no
  `Scope.user(String)` overload — you cannot write an expression naming somebody else's partition.
- The partition is **flat**: one per user, not one per user *and* entity. So the entity moves into the key
  (`mark:<episodeSlug>:cell`). Entity ids are slugs, not UUIDs.

### Both halves of the move

A backend **can** read your legacy per-user keys — they sit under entity scopes, which it addresses
normally — so you can inventory them eagerly on the server. It **cannot** write into anyone's partition:
`ctx.store()` throws `UnsupportedOperationException` for a `USER` scope on *every* method, reads included,
because a backend thread has no calling user to resolve.

So the write half is necessarily client-side and lazy — each user's data moves the next time they open
your plugin:

```ts
const legacy = `data/episode/${ctx.scope.id}/mark:${ctx.user!.id}:b3`;
const mine   = `data/user/me/mark:${ctx.scope.id}:b3`;

const existing = await ctx.api.get<Mark>(mine).catch(() => null);
if (!existing) {
  const old = await ctx.api.get<Mark>(legacy).catch(() => null);
  if (old) {
    await ctx.api.put(mine, old);
    await ctx.api.delete(legacy);   // idempotent
  }
}
```

Keep that path until you are satisfied everyone has come back, then delete it — and delete whatever legacy
keys remain from the backend, which *can* remove them (entity scope, ordinary `delete`).

### Aggregates: `queryAcrossUsers`

Per-user data is no longer addressable by key, so a leaderboard cannot be assembled from the client any
more. It should never have been: a summary each browser reports about itself is a summary of whatever
users typed. Do it on the backend instead:

```java
// Backend-only, read-only, no HTTP surface — no visitor's request can reach another visitor's data.
List<OwnedDocEntry> marks = ctx.store().queryAcrossUsers("mark:");   // record(UUID userId, String key, JsonNode value)
```

The `userId` is host-resolved from the partition the document lives in, never a value a browser supplied.
It is an identifier, not a display name, and the contract offers no way to turn it into one.

Then write the aggregate somewhere the frontend can read it — an entity scope — as you would any other
precomputed value:

```java
ctx.store().put(Scope.episode(episodeSlug), "leaderboard", board);
```

### In tests

`InMemoryDocStore.asUser(uuid)` stands in for the host resolving `me` from a session, so you can seed what
a frontend would have written and then assert on the aggregate:

```java
InMemoryDocStore store = ctx.store();          // FakePluginContext narrows the return type for you
store.asUser(alice).put(Scope.user(), "mark:s2e04:b3", true);
store.asUser(bob).put(Scope.user(), "mark:s2e04:b7", true);

plugin.register(ctx);

assertEquals(2, store.queryAcrossUsers("mark:").size());
```

There is no production counterpart to `asUser` — no real `DocStore` can write into another user's
partition.

## 5. Frontend types

`ctx.scope` is unchanged: `site | feed | season | episode`. `user` is a **storage** scope, not a slot
scope — there is no user page to mount a slot on — so it lives in a separate type:

```ts
import { SELF_SCOPE_ID, type DataScopeType } from '@mosaicast/plugin-sdk';
```

A component reading per-user data addresses `data/user/${SELF_SCOPE_ID}/…` explicitly while its
`ctx.scope` stays whatever page it is on. `makeMockCtx` needs no change.

`queryAcrossUsers` has no TypeScript counterpart, deliberately — not as a method that 403s, not as a
method at all.

## 6. Rebuild and re-test

```bash
./gradlew build            # against plugin-api/testkit 0.5.0
npm ci && npm run build
./build.sh                 # → dist/
```

Then copy `dist/` into `MOSAICAST_PLUGINS_DIR` and check the admin log viewer on startup.

---

## Quick checklist

- [ ] `platformApi` is `0.5.0`, and both Java artifacts and the npm package are on `0.5.0`
- [ ] Manifest declares `"data": { "readableBy": …, "writableBy": … }` — **and if any slot is `visibleTo: "anonymous"`, `readableBy` says `"anonymous"` too, or those reads now 403**
- [ ] Any `switch` over `ScopeType` handles `USER` (or has a `default`)
- [ ] No `Scope.site(String)` calls left
- [ ] Per-user writes go to `data/user/me/…`, with the entity in the key — **not** `…:<userId>:…`
- [ ] A lazy client-side migration path moves each user's legacy data on their next visit
- [ ] Leaderboards/rollups come from `queryAcrossUsers(...)` on the backend, not from the client
- [ ] No `USER` scope reaches `ctx.store()` on the backend (it throws `UnsupportedOperationException`)
- [ ] Tests pass against `plugin-testkit` / `makeMockCtx` `0.5.0`
