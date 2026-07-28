# Migrating a plugin to `platformApi` 0.4.0

For plugin authors coming from `0.3.x`. Everything here is mechanical; budget an afternoon for a
typical plugin, most of it in step 2.

**You have no choice about timing.** Core matches `major.minor` **exactly**, so the moment the host runs
`0.4.0` every `0.3.x` plugin is rejected at load. Rejections are visible in the admin log viewer with
their reason, so a plugin that has not been migrated fails loudly rather than silently.

Work in this order — each step compiles on its own, and doing 2 before 1 means fighting two error
messages at once.

---

## 1. Bump the version you build against

`plugin.json`:

```diff
-  "platformApi": "0.3.0",
+  "platformApi": "0.4.0",
```

`build.gradle.kts`:

```diff
-  compileOnly("dev.mosaicast:plugin-api:0.3.0")
-  testImplementation("dev.mosaicast:plugin-testkit:0.3.0")
+  compileOnly("dev.mosaicast:plugin-api:0.4.0")
+  testImplementation("dev.mosaicast:plugin-testkit:0.4.0")
```

`package.json`: `"@mosaicast/plugin-sdk": "^0.4.0"`.

## 2. Jackson 2 → Jackson 3

The contract now names Jackson 3, because the host does. Change the package:

```diff
-import com.fasterxml.jackson.databind.JsonNode;
-import com.fasterxml.jackson.databind.ObjectMapper;
+import tools.jackson.databind.JsonNode;
+import tools.jackson.databind.ObjectMapper;
```

Then the two things that are not a pure rename:

- **`new ObjectMapper()` → `JsonMapper.builder().build()`.** Mappers are immutable in Jackson 3;
  configure them through the builder.
- **`JacksonException` is unchecked.** `throws JsonProcessingException` clauses and the `catch` blocks
  that existed only to satisfy them can go. If you catch it deliberately, keep the catch — just note that
  nothing forces you to any more.
- **`JsonNode.asText()` is deprecated.** Use `stringValue()` on a string node, or `asString()` if you
  want the old coercing behaviour.

If your plugin only reads through `store().get(scope, key, YourType.class)` and `config().get(...)`,
**you have nothing to change here** — neither exposes a Jackson type. Only `store().query(...)` hands you
a `JsonNode` via `DocEntry.value()`.

> Why this and not a shim: plugins load parent-first under PF4J, so at runtime you get whatever `JsonNode`
> the host loaded. Compiling against Jackson 2 while the host runs Jackson 3 would replace a compile error
> with a `NoClassDefFoundError` in production. The full reasoning is in the [CHANGELOG](CHANGELOG.md).

## 3. Manifest `consent` — the legacy form is rejected

If your `plugin.json` has this, **the plugin will not load**:

```json
"consent": { "categories": ["analytics"], "externalSources": ["plausible.example"] }
```

Replace it with a per-service declaration:

```json
"consent": {
  "services": [{
    "id": "plausible",
    "name": "Plausible Analytics",
    "provider": "Plausible Insights OÜ",
    "category": "analytics",
    "privacyUrl": "https://plausible.io/privacy",
    "hosts": ["https://plausible.example"],
    "thirdCountryTransfer": false,
    "storage": [
      { "name": "plausible_ignore", "type": "localStorage",
        "purpose": "Remembers that you opted out of statistics", "duration": "persistent" }
    ]
  }]
}
```

Two things bite here:

- **`hosts` needs the scheme** (`https://plausible.example`, not `plausible.example`) — it is also the
  CSP allow-list, and core widens `script-src`/`frame-src`/`connect-src` by exactly these origins. An
  undeclared or bare-hostname origin **stays blocked even with consent granted**, which looks like "the
  embed just doesn't load".
- **`provider` is the operating company**, not your plugin. It ends up in the visitor-facing notice.

Declaring no third-party services at all? Then omit `consent` entirely, and the site stays banner-free.

See the [README](README.md#consent--the-manifest-core-owned-schema) for the field-by-field table.

## 4. Frontend `ctx` changes

**`onChange` now returns an unsubscribe** — on `consent`, `filter`, `route`, `locale`, and `player.on`.
Ignoring the return value still compiles, so nothing breaks; take advantage of it instead:

```diff
 render({ ctx, root }) {
-  ctx.filter.onChange(() => rerender());
+  return ctx.filter.onChange(() => rerender());   // the SDK runs this on cleanup
 }
```

**This breaks you only if you hand-roll a context** — a test fake written by hand rather than with
`makeMockCtx`, or anything else implementing `PluginContext`. Those must now return a function from every
`onChange`. Switching to `makeMockCtx` is the cheaper fix.

**`createPluginI18n` gained `dispose()`.** It subscribes to locale changes for its whole life; call
`dispose()` when the component owning it goes away. Previously that subscription leaked, so this is a
bug fix you may want to adopt even though nothing forces you to.

**Stop POSTing to `/api/plugins/{id}/log`:**

```diff
-await ctx.api.post('log', { level: 'warn', message: 'no board' });
+ctx.log('warn', 'no board');
```

**Optional, worth doing:** `ctx.consent.request(category)` finally lets you build the click-to-load
placeholder — call it from the click, never on mount. `ctx.consent.granted()` renders a summary.
`ctx.episodeLabels` gives you human-readable names for `ctx.episodes`, so pickers can show titles instead
of slugs (it is optional and may be partial — fall back to the slug).

## 5. Backend niceties (optional)

`ctx.logger()` is an `org.slf4j.Logger` the host has already named `plugin.<pluginId>`. Add
`slf4j-api` as a *provided* dependency and use it; do not create your own logger, or the host cannot
attribute the output to you.

If you declared `"storage": "schema"` and gave up because it was rejected at load: it works now. See the
[README](README.md#relational-storage--ctxschema-since-040).

## 6. Rebuild and re-test

```bash
./gradlew build            # against plugin-api/testkit 0.4.0
npm ci && npm run build
./build.sh                 # → dist/
```

Then copy `dist/` into `MOSAICAST_PLUGINS_DIR` and check the admin log viewer on startup: a plugin that
still trips one of the above is listed there with the reason.

---

## Quick checklist

- [ ] `platformApi` is `0.4.0`, and both Java artifacts and the npm package are on `0.4.0`
- [ ] No `com.fasterxml.jackson` imports left
- [ ] `new ObjectMapper()` replaced with `JsonMapper.builder().build()`
- [ ] `consent` uses `services[]`, with schemes in `hosts` and a real `provider`
- [ ] Any hand-rolled `PluginContext` returns an unsubscribe from every `onChange`
- [ ] No POSTs to `/api/plugins/{id}/log`
- [ ] Tests pass against `plugin-testkit` / `makeMockCtx` `0.4.0`
