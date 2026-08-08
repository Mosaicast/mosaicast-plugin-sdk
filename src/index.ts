// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * `@mosaicast/plugin-sdk` — the versioned frontend plugin contract for Mosaicast.
 *
 * This is the entire interface a plugin author has to learn: the {@link PluginContext} the host sets on
 * the mounted Web Component, plus two small helpers ({@link defineMosaicastElement},
 * {@link createPluginI18n}). See ARCHITECTURE §7.5.
 *
 * @packageDocumentation
 */

/**
 * The plugin contract version (SemVer).
 *
 * Mirror of the Java `dev.mosaicast.plugin.api.PlatformApi.VERSION` constant and the npm package
 * version — **these move together**, and CI fails the build if they drift.
 *
 * The host compares a plugin manifest's `platformApi` against this value on **`major.minor` exactly** and
 * rejects a mismatch at startup (ARCHITECTURE §7.2). While the SDK is pre-1.0 a breaking change is
 * therefore a *minor* bump; from `1.0.0` on, breaking means major.
 */
export const PLATFORM_API_VERSION = '0.5.0' as const;

/** A user's role (ARCHITECTURE §8.5). Anonymous visitors have no role (`user` is `null`). */
export type Role = 'admin' | 'podcaster' | 'fan';

/**
 * The severity of a {@link PluginContext.log} call.
 *
 * Mirrors the SLF4J levels a plugin backend gets through the Java `ctx.logger()`, minus `trace` — a
 * browser has no use for it, and the host would drop it anyway.
 */
export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

/**
 * Unsubscribes a callback registered through one of the `onChange` handles.
 *
 * Call it when your component tears down — the cleanup callback returned from your
 * {@link MosaicastRender} is the natural place. A subscription that outlives its render keeps a
 * reference to a detached DOM tree and keeps firing into it.
 */
export type Unsubscribe = () => void;

/**
 * The level plus concrete entity a view is scoped to (ARCHITECTURE §6.1).
 *
 * This is the **slot** scope — the page your component was mounted on. It never carries the `user` level
 * of {@link DataScopeType}: a slot lives in a named region of a page, and there is no user page. A
 * component reading per-user data addresses `data/user/me/…` explicitly while `ctx.scope` stays whatever
 * page it is on.
 */
export interface Scope {
  /** The scope level. */
  type: 'site' | 'feed' | 'season' | 'episode';
  /** The id of the entity at that level (e.g. the `EpisodeRef` slug for `episode`). */
  id: string;
}

/**
 * The scope levels the host's **data** surface addresses — the four page levels of {@link Scope} plus
 * `user`, the caller's own private partition (`ScopeType` in the Java SDK).
 *
 * Deliberately a second type: `user` is a storage partition, never a slot scope, so it can appear in a
 * `data/{scopeType}/{scopeId}/…` path but never in `ctx.scope`.
 *
 * @since 0.5.0
 */
export type DataScopeType = Scope['type'] | 'user';

/**
 * The id every `user` data path carries: the literal `me`, mirroring the Java `Scope.SELF_ID`.
 *
 * The host resolves it from the session, so `data/user/me/{key}` reaches the calling user's partition and
 * no one else's. **Any other `user` id is a 400**, never a silent substitution, and an anonymous request
 * to a `user` path is a 401 — with no session there is no partition. Per-user data belongs here rather
 * than in a key like `mark:<userId>:cell`, which the host cannot enforce because the client supplies it.
 *
 * @since 0.5.0
 */
export const SELF_SCOPE_ID = 'me' as const;

/**
 * The host-owned filter axes for the current view (ARCHITECTURE §6.1).
 *
 * Filter state lives in the URL and is defined by the host; **plugins consume it read-only** and never
 * define new axes. Known axes are typed; the index signature allows the host to add more without a
 * breaking change.
 */
export interface FilterState {
  /** Selected season number, if the view is filtered by season. */
  season?: number;
  /** Selected tags/keywords, if the view is filtered by tag. */
  tags?: string[];
  /** Active sort key, if any. */
  sort?: string;
  /** Additional host-defined axes. */
  [axis: string]: unknown;
}

/**
 * The host theme exposed to plugins as semantic tokens (ARCHITECTURE §12.3).
 *
 * The shell and plugins read the **same** tokens, so a plugin re-themes automatically in light/dark.
 * {@link defineMosaicastElement} injects these into the component's shadow root as `--mc-*` CSS custom
 * properties (e.g. `textMuted` → `--mc-text-muted`).
 */
export interface ThemeTokens {
  /** Page background. */
  bg: string;
  /** Raised surface background. */
  surface: string;
  /** Primary text color. */
  text: string;
  /** Muted/secondary text color. */
  textMuted: string;
  /** Accent color. */
  accent: string;
  /** Readable text color on top of {@link accent}. */
  accentContrast: string;
  /** Optional secondary accent. */
  accent2?: string;
  /** Border/divider color. */
  border: string;
}

/**
 * One document in the plugin's doc store: its key plus its value.
 *
 * Mirror of the Java `dev.mosaicast.plugin.api.DocEntry` record, and the element type of the host's
 * paged list endpoint — the key is carried because you cannot address a document without it.
 */
export interface DocEntry<T = unknown> {
  /** The document's key within its scope; matches `^[A-Za-z0-9._:-]{1,200}$`. */
  key: string;
  /** The document's JSON value. */
  value: T;
}

/**
 * The host's standard paged envelope, as returned by the doc-store list endpoint
 * (`GET /api/plugins/{id}/data/{scopeType}/{scopeId}?prefix=&page=&size=`).
 */
export interface PagedDocs<T = unknown> {
  /** The documents on this page, keyed. */
  items: DocEntry<T>[];
  /** The zero-based page index. */
  page: number;
  /** The requested page size. */
  size: number;
  /** Total number of matching documents across all pages. */
  totalElements: number;
  /** Total number of pages. */
  totalPages: number;
}

/**
 * A thin authenticated REST client for the **host-provided** endpoints of this plugin's namespace.
 *
 * All paths are relative to the plugin's base (`/api/plugins/<id>/`); the host attaches the base path and
 * the user's auth (session or personal access token). Methods reject on non-2xx responses (RFC 7807
 * `application/problem+json` body, ARCHITECTURE §13).
 *
 * **These are not plugin-authored routes.** A v1 plugin cannot declare HTTP endpoints — its server side
 * is `register(ctx)` and nothing else (see the Java `PluginBackend`). What this client talks to is a
 * fixed, generic surface the host exposes over the plugin's hard-scoped doc store, mirroring the Java
 * `DocStore` one-to-one — get / put / list / delete, nothing more:
 *
 * ```text
 * GET    /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}
 *          → one JSON doc; 404 if absent
 * GET    /api/plugins/{id}/data/{scopeType}/{scopeId}?prefix=&page=&size=
 *          → { items: [{ key, value }], page, size, totalElements, totalPages }
 * PUT    /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}   (JSON body)
 *          → upsert, last-write-wins
 * DELETE /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}
 *          → remove; idempotent
 * ```
 *
 * `scopeType` is a {@link DataScopeType} and `scopeId` the id of that entity — i.e. the `Scope` the
 * backend addresses with. Two of them are singletons whose id is fixed: `site` is always the literal
 * `main` (one site), and `user` always {@link SELF_SCOPE_ID} (`me`), which the host resolves to the
 * calling user. So the path always has four non-empty segments. `key` must match
 * `^[A-Za-z0-9._:-]{1,200}$` — the host answers 400 otherwise — and is the final path segment verbatim:
 * no `/`, so structure keys with `:` / `.` / `-` (e.g. `mark:s2e04:b3`; entity ids are slugs). The list
 * is paginated ({@link PagedDocs}) and carries each doc's key ({@link DocEntry}), since you cannot
 * address a doc without it.
 *
 * The doc a backend writes with `ctx.store().put(scope, key, value)` is the one read here at
 * `GET /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}`: one store, two ends. The `user` scope is the
 * exception — it exists only here. A backend has no calling user, so it cannot write a user partition at
 * all and reads them only in aggregate, through the Java `DocStore.queryAcrossUsers(prefix)`.
 *
 * ## Where per-user data goes
 *
 * **In the `user` scope, not in the key.** A key is client-supplied, so the older convention
 * `mark:<userId>:cell` under an episode scope was an access-control decision the host could not check:
 * any caller past the plugin's read floor could address someone else's key, and scope ids are public
 * slugs, so nothing had to be guessed. `data/user/me/…` is resolved from the session instead — the only
 * partition a caller can name is their own. Any other `user` id is a **400**; an anonymous `user` request
 * is a **401**, whatever the read floor says. The partition is flat, so the entity goes in the key:
 * `mark:<episodeSlug>:cell`.
 *
 * ## What the host enforces
 *
 * Data is hard-scoped to the plugin id — a plugin only ever sees its own — and the host validates that the
 * scope exists. Access is what your **manifest** declares:
 *
 * ```json
 * "data": { "readableBy": "fan", "writableBy": "podcaster" }
 * ```
 *
 * Values are `anonymous | fan | podcaster | admin` ({@link Role} plus `anonymous`, the absence of one);
 * `writableBy` may not be `anonymous`, and if the block is absent `readableBy` falls back to the *write*
 * floor rather than to anonymous — saying nothing gets the safe answer. A slot's `visibleTo` governs
 * **rendering only**; it never governed data access, and inferring the data floor from unrelated UI slots
 * is exactly what once let a plugin with one anonymous slot expose its whole store. The `user` scope
 * ignores `readableBy` altogether: no floor makes someone else's partition readable.
 *
 * A write is plain persistence: no plugin code runs at request time, so anything derived or validated
 * server-side must be precomputed in the backend's `register`/`onSchedule` and read back from the store.
 *
 * Custom plugin-defined server routes may arrive in a later `platformApi` version; v1 plugins use the
 * doc store.
 *
 * @example Read, list, write and remove docs from a Web Component
 * ```ts
 * const mine = `data/user/${SELF_SCOPE_ID}`;               // the caller's own partition
 * const shared = `data/${ctx.scope.type}/${ctx.scope.id}`; // scope.id is `main` on the site scope
 * const key = `mark:${ctx.scope.id}`;                      // no `/` in keys
 *
 * const marks = await ctx.api.get<Marks>(`${mine}/${key}`);   // rejects with a 404 problem if absent
 * await ctx.api.put(`${mine}/${key}`, { ...marks, b3: true }); // upsert, last-write-wins
 *
 * const page = await ctx.api.get<PagedDocs<Marks>>(`${mine}?prefix=mark:&page=0&size=50`);
 * page.items.forEach(({ key, value }) => render(key, value));
 *
 * await ctx.api.delete(`${mine}/${key}`);                     // idempotent
 *
 * // A leaderboard is not built here: the backend aggregates every user's marks with
 * // queryAcrossUsers(...) and writes the result to `${shared}/leaderboard` for this component to read.
 * const board = await ctx.api.get<Leaderboard>(`${shared}/leaderboard`);
 * ```
 */
export interface PluginApiClient {
  /** GET a path, resolving to the parsed JSON body. */
  get<T = unknown>(path: string): Promise<T>;
  /** POST a JSON body, resolving to the parsed JSON response. */
  post<T = unknown>(path: string, body?: unknown): Promise<T>;
  /** PUT a JSON body, resolving to the parsed JSON response. */
  put<T = unknown>(path: string, body?: unknown): Promise<T>;
  /** DELETE a path, resolving to the parsed JSON response. */
  delete<T = unknown>(path: string): Promise<T>;
}

/**
 * The presentation layer of an episode — the feed-derived snapshot the core UI shows (ARCHITECTURE §4.2),
 * mirrored from the Java `dev.mosaicast.plugin.api.DisplaySnapshot` record.
 *
 * **Not authoritative:** the host overwrites it on every fetch from the raw feed, so a feed change
 * propagates automatically. `publishedAt` and `duration` are serialized as ISO-8601 strings (an instant
 * and an ISO-8601 duration respectively). Optional fields are absent when the feed declares nothing.
 */
export interface DisplaySnapshot {
  /** The episode title from the feed. */
  title: string;
  /** The episode description/show notes; may be empty. */
  description: string;
  /** The enclosure audio URL; absent for a `PLANNED` episode with no audio yet. */
  audioUrl?: string;
  /** The publication timestamp (ISO-8601 instant); absent for a `PLANNED` episode. */
  publishedAt?: string;
  /** The declared runtime (ISO-8601 duration); absent when the feed declares none. */
  duration?: string;
  /** The episode's own artwork (`itunes:image` on the item); absent if the episode declares none. */
  imageUrl?: string;
  /** The feed/show cover (`itunes:image` on the channel); absent if the feed declares none. */
  feedImageUrl?: string;
  /** The episode author (`itunes:author`); absent if the feed declares none. */
  author?: string;
  /** A short episode subtitle (`itunes:subtitle`); absent if the feed declares none. */
  subtitle?: string;
}

/**
 * The artwork to display for an episode: its own {@link DisplaySnapshot.imageUrl} if present, otherwise
 * the {@link DisplaySnapshot.feedImageUrl feed cover}, otherwise `undefined`.
 *
 * Mirror of the Java `DisplaySnapshot.artwork()` accessor. Use the two fields directly when you
 * specifically need the episode- or feed-level value.
 *
 * @param snapshot the display snapshot to resolve artwork for
 * @returns the resolved artwork URL, or `undefined` when neither the episode nor the feed declares one
 */
export function resolveArtwork(snapshot: DisplaySnapshot): string | undefined {
  return snapshot.imageUrl ?? snapshot.feedImageUrl;
}

/**
 * The consent gate for anything that stores data on the visitor's device or talks to a third party
 * (ARCHITECTURE §12.5).
 *
 * Consent is **denied until granted**. The core itself runs banner-free — strictly necessary cookies need
 * no consent — so the only reason a visitor ever sees a consent prompt is a plugin that asked for one.
 * Treat that as the cost it is.
 *
 * The categories are declared per service in your manifest's `consent.services[]` (`necessary`,
 * `functional`, `analytics`, or one you declare yourself). The host builds the cookie notice from those
 * declarations, and the `hosts` you list there are also the CSP allow-list: **an origin you did not
 * declare stays blocked even after consent is given.**
 *
 * ## The decision is per category, not per service
 *
 * `consent.services[]` is richly per-service — `provider`, `privacyUrl`, `thirdCountryTransfer`, each
 * `storage[]` item — but every method here takes a **category**. That asymmetry is deliberate and worth
 * stating plainly, because the schema implies otherwise: **services describe, categories decide.** The
 * per-service detail exists so the notice can name who stores what for how long; the thing a visitor
 * actually toggles is the category.
 *
 * The consequence: if two plugins each declare a service under `analytics` with different providers, the
 * visitor sees **one** decision listing both plugins, and granting it grants both. There is no way to
 * consent to one provider and withhold the other. Do not build a UI that implies otherwise, and don't
 * assume `has('analytics')` says anything about *which* provider was accepted — it says the category was.
 *
 * If you need a visitor to be able to accept one of your services and refuse another, declare them under
 * **different categories** (a plugin-declared category is allowed, it just has no translated label in the
 * shell). That is the only lever the contract gives you.
 *
 * ## `necessary` is never asked about
 *
 * `has('necessary')` is **always `true`** and the host never prompts for it — it is the category the core
 * itself uses, and a banner-free site stays banner-free. Declaring a service as `necessary` therefore
 * means "this loads unconditionally"; use it only for what genuinely cannot be refused, and expect
 * `request('necessary')` to resolve `true` without showing the visitor anything.
 *
 * @example The click-to-load placeholder this exists for
 * ```ts
 * function render({ ctx, root }: { ctx: PluginContext; root: HTMLElement }) {
 *   if (ctx.consent.has('analytics')) {
 *     mountChart(root);
 *     return;
 *   }
 *
 *   const button = document.createElement('button');
 *   button.textContent = 'Load chart (sets a cookie)';
 *   button.onclick = async () => {
 *     if (await ctx.consent.request('analytics')) mountChart(root);
 *   };
 *   root.append(button);
 *
 *   // Someone may flip the purpose in the settings page while this is mounted.
 *   return ctx.consent.onChange(() => rerender());
 * }
 * ```
 */
export interface ConsentApi {
  /**
   * Whether the visitor has granted a consent category.
   *
   * Check it before every load of a consent-requiring resource, not once at startup — consent can be
   * withdrawn mid-session from the host's settings page.
   *
   * @param category the consent category, as declared on a service in your manifest
   * @returns `true` if the category is currently granted
   */
  has(category: string): boolean;
  /**
   * Every category currently granted.
   *
   * For rendering a summary ("statistics: on, embeds: off"). Prefer {@link has} for a single gate.
   *
   * Includes `necessary`, which is always granted. These are categories, not service ids — a granted
   * category covers every service declared under it, across all plugins.
   *
   * @returns the granted category names, in no guaranteed order
   */
  granted(): string[];
  /**
   * Asks the visitor to grant a category, and resolves with their answer.
   *
   * The host opens its consent settings for this one purpose and resolves once the visitor decides;
   * dismissing it resolves `false`. **Call this from a user gesture** — the click on your placeholder —
   * and never on mount: an unprompted call turns a banner-free site into one with a banner, which is
   * exactly what §12.5 is arranged to avoid.
   *
   * Resolving `true` means the category is granted from that moment on; it does not load anything for
   * you. Load the resource yourself afterwards.
   *
   * ## What a caller may rely on
   *
   * A page full of plugin tiles will produce concurrent calls, so the contract is explicit about them:
   *
   * - **There is one consent surface, host-wide.** Calling this does not open a dialog of your own, and a
   *   second call while one is already open does not open a second — it joins the one in flight. You are
   *   asking the host to surface *its* settings, not opening a modal.
   * - **The visitor decides every category at once.** The host's settings cover all declared categories,
   *   so one interaction can change several. Your promise still resolves with the state of *the category
   *   you asked for* — but other categories may have moved too, which is why {@link onChange} fires for
   *   every change rather than only yours.
   * - **Every call resolves exactly once, and always.** Concurrent calls are never dropped or left
   *   pending: each resolves when the visitor completes the decision, including calls made while the
   *   surface was already open.
   * - **Grants are shared across plugins.** If another plugin's `request('analytics')` is what the
   *   visitor accepted, your pending `request('analytics')` resolves `true` too — the decision is per
   *   category and site-wide, not per plugin (see the note on granularity above).
   *
   * So: don't serialize your calls, don't build a queue, and don't assume the visitor only answered you.
   * Re-read {@link has} after any change rather than caching what a request resolved with.
   *
   * @param category the consent category to ask for
   * @returns whether the category is granted after the visitor decided
   */
  request(category: string): Promise<boolean>;
  /**
   * Subscribes to consent changes.
   *
   * Fires on **every** change to any category — including one made in the settings page while your
   * component is mounted, and including a withdrawal. It carries no payload: re-read {@link has} or
   * {@link granted} for the current state.
   *
   * @param cb called after each change
   * @returns an {@link Unsubscribe} — return it from your render callback so the subscription dies with
   *          the component
   */
  onChange(cb: () => void): Unsubscribe;
}

/**
 * One item a service stores on the visitor's device, as declared in `plugin.json`.
 *
 * Part of {@link ConsentServiceDeclaration} — see the caveats there before using either type.
 */
export interface ConsentStorageDeclaration {
  /** The cookie or storage key exactly as it appears on the device, e.g. `plausible_ignore`. */
  name: string;
  /** Where it is stored. */
  type: 'cookie' | 'localStorage' | 'sessionStorage';
  /** What it is for, in language a visitor reads — not an internal description. */
  purpose: string;
  /** How long it lasts: `session`, `persistent`, or a human duration such as `12 months`. */
  duration: string;
}

/**
 * The shape of one entry in your manifest's `consent.services[]`.
 *
 * **This type is documentation, not enforcement.** The manifest is owned and validated by the host — the
 * SDK does not read `plugin.json`, and nothing here runs at build or load time. It exists so an author
 * writing the declaration gets IDE completion and catches a typo before core rejects the plugin at load,
 * which is otherwise the first feedback you get. Two consequences worth knowing:
 *
 * - **The host is authoritative.** If this type and core disagree, core wins. It is kept in step by hand,
 *   so treat a mismatch as a bug in the SDK rather than permission to ignore the host.
 * - **It is not a manifest type.** The manifest as a whole stays core-owned and the SDK will not grow one;
 *   this covers a single nested shape that got deep enough in 0.4.0 to be worth typing.
 *
 * Use it by typing a literal you keep next to your manifest, or as a reference while writing the JSON:
 *
 * ```ts
 * const services: ConsentServiceDeclaration[] = [{
 *   id: 'plausible',
 *   name: 'Plausible Analytics',
 *   provider: 'Plausible Insights OÜ',
 *   category: 'analytics',
 *   privacyUrl: 'https://plausible.io/privacy',
 *   hosts: ['https://plausible.example'],
 *   thirdCountryTransfer: false,
 *   storage: [{
 *     name: 'plausible_ignore', type: 'localStorage',
 *     purpose: 'Remembers that you opted out of statistics', duration: 'persistent',
 *   }],
 * }];
 * ```
 */
export interface ConsentServiceDeclaration {
  /** Stable identifier for this service within your plugin. */
  id: string;
  /** The service as a visitor would recognise it, e.g. `Plausible Analytics`. */
  name: string;
  /** The **legal entity** operating the service — the company, not your plugin. */
  provider: string;
  /**
   * The consent category this service falls under, and therefore what {@link ConsentApi.has} gates on.
   *
   * `necessary` is never prompted for and always granted. Remember that the category — not the service —
   * is what the visitor decides: two services sharing a category are accepted or refused together.
   */
  category: 'necessary' | 'functional' | 'analytics' | (string & {});
  /** The provider's own privacy policy. */
  privacyUrl: string;
  /**
   * Every origin the service is contacted on, scheme included (`https://plausible.example`).
   *
   * **Also the CSP allow-list**: core widens `script-src`/`frame-src`/`connect-src` by exactly these, so
   * an undeclared or bare-hostname origin stays blocked even after consent is given.
   */
  hosts: string[];
  /** Whether personal data leaves the EU/EEA. */
  thirdCountryTransfer: boolean;
  /** Each item the service stores on the visitor's device. */
  storage: ConsentStorageDeclaration[];
}

/**
 * Everything a frontend plugin is given, set by the host on the mounted custom element
 * (ARCHITECTURE §7.5). This is the **entire** interface a plugin author must learn.
 */
export interface PluginContext {
  /**
   * The scope this plugin instance is mounted in. For the `episode` scope, `scope.id` is the episode's
   * **public slug** (e.g. `the-sample-cast-s01e06`) — the same id used in its URL and as the doc-store
   * partition (`data/episode/{slug}/…`), not the internal UUID.
   */
  scope: Scope;
  /**
   * The episode ids in scope, resolved (and access-filtered) by the host — the public **slugs** (the same
   * values used in URLs and doc-store paths). Pair with {@link episodeLabels} for display.
   */
  episodes: string[];
  /**
   * Human-readable labels for {@link episodes}, keyed by slug (e.g. `"S01E06 · The Lighthouse…"`). The host
   * builds them from the feed's season/episode + title; use them in pickers so users see titles, not slugs.
   * Optional: absent (or partial) when the host does not provide a label for a given episode.
   */
  episodeLabels?: Record<string, string>;
  /** Present on the `episode` scope: lifecycle status of the current episode. */
  episode?: { status: 'PLANNED' | 'PUBLISHED' | 'WITHDRAWN' };
  /** The signed-in user, or `null` for anonymous visitors. */
  user: { id: string; role: Role } | null;
  /**
   * Authenticated client for this plugin's host-provided data endpoints — **not** for plugin-authored
   * routes, which do not exist in v1. It reads and writes the same hard-scoped doc store the backend
   * uses via `ctx.store()`. See {@link PluginApiClient} for the endpoint shape and access rules.
   */
  api: PluginApiClient;
  /**
   * Writes one line to the host's log, attributed to this plugin.
   *
   * The counterpart of the backend's `ctx.logger()`, and the **only** supported way for a frontend
   * component to log to the host — do not POST to `/api/plugins/{id}/log` through {@link api}, which is a
   * data endpoint. The host attributes the entry to your plugin, stores `info` and above, surfaces `warn`
   * and above in the admin log viewer, and rate-limits this path: a render loop logging per frame will be
   * throttled, not stored.
   *
   * Messages are read by site operators, not by you — they land next to core's own output. Keep them
   * short, and keep personal data out of them.
   *
   * @param level   the severity
   * @param message the message; already-formatted, since there is no placeholder syntax here
   *
   * @example
   * ```ts
   * ctx.log('warn', `no board for episode ${ctx.scope.id}`);
   * ```
   */
  log(level: LogLevel, message: string): void;
  /** Cookie/consent gate for third-party resources — see {@link ConsentApi} (ARCHITECTURE §12.5). */
  consent: ConsentApi;
  /**
   * Read-only access to the host's URL filter state (ARCHITECTURE §6.1).
   *
   * Plugins *consume* filters, they never define them: the axes (season, tags, sorting) belong to the
   * host and live in the URL. `onChange` returns an {@link Unsubscribe}.
   */
  filter: { current(): FilterState; onChange(cb: (f: FilterState) => void): Unsubscribe };
  /**
   * Player position + control, for sync plugins (ARCHITECTURE §6.5).
   *
   * `on` returns an {@link Unsubscribe} — player events outlive a single render, so detaching matters
   * here more than anywhere else on this context.
   */
  player: {
    currentTime(): number;
    seekTo(s: number): void;
    on(ev: string, cb: (...args: unknown[]) => void): Unsubscribe;
  };
  /**
   * The subpath under `/p/<pluginId>/`, for deep-linkable plugin content (ARCHITECTURE §6.4).
   *
   * `onChange` returns an {@link Unsubscribe}.
   */
  route: { path: string; onChange(cb: (p: string) => void): Unsubscribe };
  /**
   * The active UI locale (ARCHITECTURE §12.7).
   *
   * `onChange` returns an {@link Unsubscribe}. {@link createPluginI18n} subscribes to this for you and
   * hands back a `dispose` to undo it.
   */
  locale: { current(): string; onChange(cb: (l: string) => void): Unsubscribe };
  /** Core listening progress in seconds, or `null` if unknown (ARCHITECTURE §6.5). */
  progress: { get(episodeId: string): Promise<number | null> };
  /** Host theme tokens, also injected as `--mc-*` CSS custom properties. */
  theme: ThemeTokens;
}

/**
 * What a plugin author implements: render logic given the mount point and context.
 *
 * @param args.ctx  the host-provided context
 * @param args.root a dedicated container inside the component's shadow root to render into; it is
 *                  cleared by the SDK before each call
 * @returns an optional cleanup callback, run before the next render and on disconnect
 */
export type MosaicastRender = (args: { ctx: PluginContext; root: HTMLElement }) => void | (() => void);

/** Options for {@link defineMosaicastElement}. */
export interface DefineElementOptions {
  /** The custom-element tag name (must contain a hyphen), e.g. `bingo-episode-card`. */
  tag: string;
  /** The render callback invoked whenever `ctx` is (re)assigned. */
  render: MosaicastRender;
}

const THEME_TOKEN_VARS: ReadonlyArray<[keyof ThemeTokens, string]> = [
  ['bg', '--mc-bg'],
  ['surface', '--mc-surface'],
  ['text', '--mc-text'],
  ['textMuted', '--mc-text-muted'],
  ['accent', '--mc-accent'],
  ['accentContrast', '--mc-accent-contrast'],
  ['accent2', '--mc-accent-2'],
  ['border', '--mc-border'],
];

/** Builds the `:host { --mc-*: … }` rule from theme tokens. */
function themeCss(theme: ThemeTokens): string {
  const decls = THEME_TOKEN_VARS.filter(([key]) => theme[key] != null)
    .map(([key, cssVar]) => `${cssVar}: ${theme[key]};`)
    .join(' ');
  return `:host { display: block; ${decls} }`;
}

/**
 * Registers a Mosaicast plugin Web Component.
 *
 * The created custom element attaches an open shadow root, accepts the host's {@link PluginContext} via
 * a `ctx` property, injects the theme tokens as `--mc-*` CSS custom properties into the shadow root, and
 * calls your {@link DefineElementOptions.render} into a dedicated container — so you write only render
 * logic. Re-assigning `ctx` re-renders (after running any cleanup the previous render returned).
 *
 * Calling twice with the same tag is a no-op (the browser forbids redefining a custom element).
 *
 * @param options the tag name and render callback
 */
export function defineMosaicastElement(options: DefineElementOptions): void {
  const { tag, render } = options;
  if (customElements.get(tag)) {
    return;
  }

  class MosaicastElement extends HTMLElement {
    private readonly styleEl: HTMLStyleElement;
    private readonly root: HTMLElement;
    private ctxValue: PluginContext | null = null;
    private cleanup: (() => void) | void = undefined;

    constructor() {
      super();
      const shadow = this.attachShadow({ mode: 'open' });
      this.styleEl = document.createElement('style');
      this.root = document.createElement('div');
      shadow.append(this.styleEl, this.root);
    }

    /** The host sets this to (re)render the component. */
    set ctx(value: PluginContext) {
      this.ctxValue = value;
      this.rerender();
    }

    get ctx(): PluginContext | null {
      return this.ctxValue;
    }

    private rerender(): void {
      const ctx = this.ctxValue;
      if (!ctx) {
        return;
      }
      if (typeof this.cleanup === 'function') {
        this.cleanup();
        this.cleanup = undefined;
      }
      this.styleEl.textContent = themeCss(ctx.theme);
      this.root.replaceChildren();
      this.cleanup = render({ ctx, root: this.root });
    }

    disconnectedCallback(): void {
      if (typeof this.cleanup === 'function') {
        this.cleanup();
        this.cleanup = undefined;
      }
    }
  }

  customElements.define(tag, MosaicastElement);
}

/** A translation catalog: message key → template string (with `{{param}}` placeholders). */
export type I18nCatalog = Record<string, string>;

/** Catalogs keyed by locale code, e.g. `{ en: {...}, de: {...} }`. */
export type I18nCatalogs = Record<string, I18nCatalog>;

/** The translator returned by {@link createPluginI18n}. */
export interface PluginI18n {
  /**
   * Translates a key against the active locale, interpolating `{{param}}` placeholders.
   *
   * Resolution: active locale → source locale (`en`) → the key itself as a last-resort fallback.
   *
   * @param key    the message key
   * @param params values for `{{placeholder}}` interpolation
   */
  t(key: string, params?: Record<string, string | number>): string;
  /** The currently active locale code. */
  readonly locale: string;
  /**
   * Detaches the translator from `ctx.locale`.
   *
   * A translator subscribes to locale changes for its whole life. Call this when the component that owns
   * it goes away — from the cleanup callback your render returns — or the subscription keeps a dead
   * translator alive.
   */
  dispose(): void;
}

const SOURCE_LOCALE = 'en';

function interpolate(template: string, params?: Record<string, string | number>): string {
  if (!params) {
    return template;
  }
  return template.replace(/\{\{\s*(\w+)\s*\}\}/g, (whole, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : whole,
  );
}

/**
 * Creates a plugin-local translator bound to the host's active locale (ARCHITECTURE §12.7).
 *
 * A plugin ships its own `locales/*.json` catalogs and translates against `ctx.locale`, re-rendering on
 * change — the same convention as the shell, no extra i18n library required. English (`en`) is the
 * source language and the fallback.
 *
 * The translator subscribes to `locale.onChange` for as long as it lives — call {@link PluginI18n.dispose}
 * from your render's cleanup callback when the component goes away.
 *
 * @param catalogs catalogs keyed by locale code
 * @param locale   the host locale handle, i.e. `ctx.locale`; its `onChange` drives re-selection
 * @returns a translator whose `t` and `locale` reflect the currently active locale
 */
export function createPluginI18n(
  catalogs: I18nCatalogs,
  locale: PluginContext['locale'],
): PluginI18n {
  let active = locale.current();
  const unsubscribe = locale.onChange((l) => {
    active = l;
  });

  return {
    get locale() {
      return active;
    },
    dispose() {
      // Optional call: a host built against 0.3.x returns nothing here, and a translator that cannot be
      // disposed is better than one that throws while a component is tearing down.
      unsubscribe?.();
    },
    t(key, params) {
      const template =
        catalogs[active]?.[key] ?? catalogs[SOURCE_LOCALE]?.[key] ?? key;
      return interpolate(template, params);
    },
  };
}
