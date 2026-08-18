# Migrating a plugin to `platformApi` 0.8.0

For plugin authors coming from `0.7.x`. **Small: a version bump, and nothing else unless you implement
`PluginContext` yourself.** Everything in this release is new surface — no plugin *code* written against
0.7.1 changes behaviour, and no existing test double breaks.

**You have no choice about timing.** Core matches `major.minor` **exactly**, so the moment the host runs
`0.8.0` every `0.7.x` plugin is rejected at load, with the reason in the admin log viewer.

Coming from `0.6.x`? Do the [0.7.0 migration](https://github.com/Mosaicast/mosaicast-plugin-sdk/blob/v0.7.0/MIGRATION.md)
first — the frontend schema client and `route.navigate` — then come back here.

---

## Why this release exists

**A plugin could not store a file.** It could declare relational tables, publish documents and serve a
deep-linked page — but not accept an upload. The host's `BlobStore` had existed since branding shipped and
had exactly one caller. The result was an odd asymmetry: the host's CSP lets a plugin display an image from
*any* host on the web, and gave it no way to accept one from the site's own podcaster. A wiki wants
diagrams; the honest answer was "find an image host first", which is not an answer.

**A plugin could not link to an episode without hardcoding a URL.** `ctx.route.navigate` is confined to
your own `/p/<pluginId>/` subtree by construction, and that is a property worth keeping — so every plugin
that wanted to write "discussed in *The Kraken*, from 12:04" wrote `` `/episodes/${slug}` `` itself and
became a thing that breaks when the host changes a route.

## 1. Bump the version you build against

`plugin.json`:

```diff
-  "platformApi": "0.7.1",
+  "platformApi": "0.8.0",
```

and your dependencies:

```diff
-  "@mosaicast/plugin-sdk": "^0.7.0"
+  "@mosaicast/plugin-sdk": "^0.8.0"
```

```diff
- implementation("dev.mosaicast:plugin-api:0.7.1")
+ implementation("dev.mosaicast:plugin-api:0.8.0")
```

That is the whole required migration. The rest of this page is opt-in.

## 2. Optional: store files (`ctx.blobs`)

Declare what you want to store. **Nothing is granted without this**, and what you declare is what an
operator sees you asking for — they cap both numbers and intersect the type list with the install's own
allow-list, so you may get less:

```json
"blobs": { "maxFileBytes": 5242880, "quotaBytes": 268435456,
           "mimeTypes": ["image/png", "image/jpeg", "image/webp"] }
```

`ctx.blobs` (TypeScript) and `ctx.blobs()` (Java) are **`null`** without it — the same shape `ctx.schema`
has, and for the same reason. Check before use; TypeScript will make you.

From a Web Component:

```ts
const blobs = ctx.blobs;
if (!blobs) return; // this plugin declared no `blobs` block

const stored = await blobs.upload(file);          // a File from <input type="file">
img.src = blobs.urlFor(stored.ref);
await ctx.api.put('data/site/main/logo', { ref: stored.ref });
```

**Store the `ref`, never the URL.** The URL is derived from the ref and the host may change how; the ref is
the identity.

From a backend, for what only a backend can do — fetching on a schedule, and deleting files a document no
longer points at:

```java
PluginBlobs blobs = ctx.blobs();
try (InputStream in = Files.newInputStream(path)) {
    BlobInfo stored = blobs.put("architecture.png", "image/png", in);
}
```

Three things to know before you build on it:

- **Uploads are refused for reasons you can predict.** Size against your effective ceiling, declared type
  against the effective allow-list, then the *actual* type read from the leading bytes — a file whose
  content contradicts its extension is refused, and SVG is never accepted at all. Show the refusal; the
  person who picked the file is the only one who can pick a different one. `quota()` reports the effective
  numbers so you can say so before they pick.
- **Nothing collects orphans.** A file outlives the document that referred to it, and only your plugin
  knows which those are. Delete what you stop pointing at.
- **Writes go through `data.writableBy`**, reads through `data.readableBy` — the same floors as the doc
  surface. Unlike the schema surface, writes over HTTP are the point here: a file has no relational
  invariant for plugin code to enforce, so the floors plus a quota are the whole authorization story.

The test kit mirrors all of it — `makeMockBlobs()` in `@mosaicast/plugin-sdk/testing`, and
`InMemoryPluginBlobs` in the Java kit. Both **enforce the ceilings and the allow-list**, because a component
that has only ever met an accepting double meets its first refusal in front of a podcaster. Neither reads
file formats; name the file that should be refused (`rejectContent`) to exercise that path.

## 3. Optional: link to core pages (`ctx.links`)

Strings, not navigation — put the result in a real `href` and let the visitor click (middle-click, "open in
new tab" and crawlers all need one):

```ts
const href = ctx.links.episode('kraken', { t: 724 });   // /episodes/kraken?t=724
const feed = ctx.links.feed('main', { season: '2' });   // /feeds/main?season=2
```

`?t=` is the host's timestamp deep link: it seeks the player to that second and beats the listener's stored
position for that navigation. Frontend only — this is where links get rendered.

## 4. If you implement `PluginContext` yourself

`PluginContext` gained `blobs()`. If you have your own implementation rather than using
`FakePluginContext`, add it — returning `null` is correct for a plugin that declares no `blobs` block.

**`FakePluginContext`'s existing constructors are unchanged.** The four-argument form every plugin test
already calls still compiles and still means "no file storage"; a fifth argument is a *new* overload:

```java
var blobs = new InMemoryPluginBlobs().withMimeTypes(Set.of("image/png"));
var ctx = new FakePluginContext(new InMemoryDocStore(), new MapPluginConfig(),
        new FakeFeedAccess(Map.of()), null, blobs);
```

That is deliberate. 0.7.1 exists solely because 0.7.0 added a *required* member to a `ctx` sub-object and
broke every hand-built test double downstream. New members here are top-level and nullable, and new
constructor parameters come as overloads.
