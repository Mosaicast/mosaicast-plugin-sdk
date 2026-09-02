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
export const PLATFORM_API_VERSION = '0.12.0' as const;

/** A user's role (ARCHITECTURE §8.5). Anonymous visitors have no role (`user` is `null`). */
export type Role = 'admin' | 'podcaster' | 'fan';

/**
 * A role as the manifest names it: the {@link Role} values plus `anonymous`, the absence of one.
 *
 * Used by the access floors in {@link PluginDataDeclaration}. `ctx.user` is `null` rather than
 * `'anonymous'` — this spelling exists only where a manifest has to name "no role at all".
 *
 * @since 0.6.0
 */
export type DataAccessRole = Role | 'anonymous';

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
 * `DocStore` one-to-one — get / put / list / delete, nothing more.
 *
 * A plugin that declares `storage.schema` reads its provisioned tables through {@link SchemaClient}
 * (`ctx.schema`), a second host surface with its own paths and its own rules — read-only, since writing
 * those tables stays with the plugin's backend. This client is the doc store's:
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
 * "data": {
 *   "readableBy": "fan",
 *   "writableBy": "podcaster",
 *   "backendOwned": ["stats", "agg:*"]
 * }
 * ```
 *
 * Values are `anonymous | fan | podcaster | admin` ({@link Role} plus `anonymous`, the absence of one);
 * `writableBy` may not be `anonymous`, and if the block is absent `readableBy` falls back to the *write*
 * floor rather than to anonymous — saying nothing gets the safe answer. A slot's `visibleTo` governs
 * **rendering only**; it never governed data access, and inferring the data floor from unrelated UI slots
 * is exactly what once let a plugin with one anonymous slot expose its whole store. Watch the default: a
 * plugin with an anonymous slot and no `data` block loses its anonymous reads (403) until it declares
 * `"readableBy": "anonymous"`. **Neither floor applies to the `user` scope:** no floor makes someone
 * else's partition readable, none stands between a caller and their own, and `writableBy` does not gate it
 * either — a write floor protects the *shared* surface, and a user partition is unshared. Any
 * authenticated caller reads and writes their own `data/user/me/…` whatever the manifest declares.
 *
 * **The floors say who, not which key.** Authorization here is per plugin, not per document, so every
 * caller above `writableBy` can overwrite or delete *any* shared-scope key — including one your backend
 * computed, since the host cannot tell a scheduled write from a `curl`. `backendOwned` is the exception:
 * an exact key or a `*`-terminated prefix that the backend still writes freely while a client `PUT` or
 * `DELETE` answers **403**, worded differently from the role-floor 403 so you can tell which rule refused
 * you. Reads are unaffected. It does not apply to `user` partitions, and it does not remove a value forged
 * before it was declared — see {@link PluginDataDeclaration}.
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
 * // If the manifest declares that key backendOwned, a PUT to it from here is a 403 — by design.
 * const board = await ctx.api.get<Leaderboard>(`${shared}/leaderboard`);
 * ```
 */
export interface PluginApiClient {
  /**
   * GET a path, resolving to the parsed JSON body.
   *
   * Rejects with a {@link PluginApiError} on any non-2xx response — **including 404**, which is the
   * normal answer for a document that does not exist yet. Prefer {@link getOrNull} when absence is an
   * expected outcome rather than a failure.
   */
  get<T = unknown>(path: string): Promise<T>;
  /**
   * Like {@link get}, but resolves **`null`** on a 404 instead of rejecting.
   *
   * "Nothing saved yet" is the ordinary state of a doc-store key, so every plugin ends up writing
   * `get(path).catch(() => undefined)` — which also swallows the 500, the 403 from the read floor and
   * the network failure, and reports all four to the visitor as an empty widget. This makes absence an
   * answer, so the `catch` that remains is a real error path again.
   *
   * Every other non-2xx still rejects with a {@link PluginApiError}.
   *
   * @param path the path relative to the plugin's base
   * @returns the parsed body, or `null` when the host answered 404
   * @since 0.9.0
   */
  getOrNull<T = unknown>(path: string): Promise<T | null>;
  /** POST a JSON body, resolving to the parsed JSON response. Rejects with a {@link PluginApiError}. */
  post<T = unknown>(path: string, body?: unknown): Promise<T>;
  /** PUT a JSON body, resolving to the parsed JSON response. Rejects with a {@link PluginApiError}. */
  put<T = unknown>(path: string, body?: unknown): Promise<T>;
  /** DELETE a path, resolving to the parsed JSON response. Rejects with a {@link PluginApiError}. */
  delete<T = unknown>(path: string): Promise<T>;
}

/**
 * The RFC 7807 `application/problem+json` body the host sends with a refusal.
 *
 * Every field is optional: the host is entitled to answer with a bare status, and a plugin that
 * destructures blindly breaks on the day it does.
 *
 * @since 0.9.0
 */
export interface ProblemDetail {
  /** A URI identifying the problem type. */
  type?: string;
  /** A short, human-readable summary of the problem type. */
  title?: string;
  /** An explanation specific to this occurrence — the field worth showing an operator. */
  detail?: string;
  /** A URI identifying this specific occurrence. */
  instance?: string;
}

/**
 * What every {@link PluginApiClient} method rejects with on a non-2xx response.
 *
 * The status is the point. Without it a plugin cannot tell the 403 that means *the manifest's read floor
 * refused you* from the 403 that means *this key is `backendOwned`* — the contract words those two
 * differently on purpose, and an untyped rejection throws that distinction away. Nor can it tell either
 * of them from a 500, which is the failure a plugin should surface rather than swallow.
 *
 * Test it with {@link isPluginApiError} rather than `instanceof`: the error crosses a bundle boundary
 * from the host, so it is not guaranteed to share a constructor with anything in your plugin.
 *
 * ```ts
 * try {
 *   await ctx.api.put(`data/site/main/stats`, computed);
 * } catch (e) {
 *   if (isPluginApiError(e) && e.status === 403) {
 *     ctx.log('warn', e.problem?.detail ?? 'refused');   // backendOwned, or the write floor
 *     return;
 *   }
 *   throw e;                                            // a real failure — do not swallow it
 * }
 * ```
 *
 * @since 0.9.0
 */
export interface PluginApiError extends Error {
  /** The HTTP status the host answered with — `404`, `403`, `415`, `500`, … */
  readonly status: number;
  /** The RFC 7807 body, when the host sent one. */
  readonly problem?: ProblemDetail;
}

/**
 * Whether a caught value is a {@link PluginApiError}.
 *
 * A **structural** check, deliberately: the error is constructed by the host and reaches your plugin
 * across a bundle boundary, so `instanceof` against your own copy of a class would answer `false` for a
 * genuine one. This tests what the contract actually promises — an `Error` carrying a numeric `status`.
 *
 * @param e the caught value
 * @returns whether it carries the API error shape
 * @since 0.9.0
 */
export function isPluginApiError(e: unknown): e is PluginApiError {
  return e instanceof Error && typeof (e as PluginApiError).status === 'number';
}

/**
 * The pattern every doc-store key must match — mirror of the Java `DocStore.KEY_PATTERN`.
 *
 * Note what is **not** in it: `/`. A key travels as the final path segment verbatim, so structure one
 * with `:` / `.` / `-` (`mark:s2e04:b3`), never with a slash.
 *
 * @since 0.9.0
 */
export const DOC_KEY_PATTERN = /^[A-Za-z0-9._:-]{1,200}$/;

/**
 * Which partition a {@link DocClient} call addresses.
 *
 * A {@link Scope} for a page-level partition — `ctx.scope` is usually the one you want — or one of two
 * shorthands for the singletons whose id is fixed:
 *
 * - **`'self'`** → `data/user/me`, the calling user's own partition.
 * - **`'site'`** → `data/site/main`, the one site.
 *
 * @since 0.9.0
 */
export type DocTarget = Scope | 'self' | 'site';

/**
 * A typed client for this plugin's doc store — the same endpoints {@link PluginApiClient} reaches, with
 * the path building, the key validation and the 404 handling done for you.
 *
 * Every doc access before this was string concatenation against a four-segment path, with the plugin
 * responsible for `encodeURIComponent`, for {@link DOC_KEY_PATTERN}, for knowing that `site` is always
 * `main` and `user` always `me`, and for remembering that a key cannot contain `/`:
 *
 * ```ts
 * `data/episode/${encodeURIComponent(episodeSlug)}/favourites`   // every call site, every plugin
 * ```
 *
 * ## Why `'self'` is the shortest thing to write
 *
 * Per-user data belongs in the `user` scope, never in a key — a key is client-supplied, so
 * `mark:<userId>:cell` is an access-control decision the host cannot enforce, and scope ids are public
 * slugs so nothing has to be guessed. That is the most security-relevant convention in the whole
 * contract, and making it the *shortest* call is the only reliable way to make a convention stick:
 *
 * ```ts
 * await ctx.docs.put('self', `mark:${ctx.scope.id}`, marks);   // the caller's own partition
 * ```
 *
 * ## What it validates, and what it does not
 *
 * A key that fails {@link DOC_KEY_PATTERN} **throws at the call site**, with the pattern in the message,
 * instead of costing a 400 round-trip you then have to read the body of. Everything else is the host's
 * to enforce and is unchanged — the `readableBy`/`writableBy` floors, `backendOwned`, the 400 on an
 * unknown scope, the 401 on an anonymous `user` request. See {@link PluginApiClient} for all of it;
 * that client stays available as the escape hatch for anything this does not cover.
 *
 * @since 0.9.0
 */
export interface DocClient {
  /**
   * One document, or **`null`** when there is none.
   *
   * Null rather than a rejection: absence is the ordinary state of a key nothing has written yet, and
   * making it an exception is what produced the `catch` that also swallowed every real failure. Other
   * refusals still reject with a {@link PluginApiError}.
   *
   * @param target which partition — a {@link Scope}, `'self'` or `'site'`
   * @param key    the document key; must match {@link DOC_KEY_PATTERN}
   * @throws Error synchronously-thrown-as-rejection if the key is malformed
   */
  get<T = unknown>(target: DocTarget, key: string): Promise<T | null>;
  /**
   * Upserts a document. Last-write-wins, as everywhere on this store.
   *
   * @param target which partition
   * @param key    the document key; must match {@link DOC_KEY_PATTERN}
   * @param value  the JSON value to store
   */
  put<T = unknown>(target: DocTarget, key: string, value: T): Promise<void>;
  /**
   * One page of documents in a partition, each carrying its key.
   *
   * @param target which partition
   * @param opts   `prefix` filters by key prefix; `page` is zero-based and `size` defaults to 50 (the
   *               host caps it at 200)
   */
  list<T = unknown>(
    target: DocTarget,
    opts?: { prefix?: string; page?: number; size?: number },
  ): Promise<PagedDocs<T>>;
  /**
   * Removes a document. Idempotent — removing what is already gone resolves.
   *
   * @param target which partition
   * @param key    the document key; must match {@link DOC_KEY_PATTERN}
   */
  remove(target: DocTarget, key: string): Promise<void>;
}

/**
 * How a declared field is compared to a value in a {@link SchemaQuery}.
 *
 * The same closed vocabulary as the Java `Criteria.Op`, lower-cased — the two halves of one plugin
 * describe a query the same way. `isNull` / `isNotNull` take no value; `in` takes an array.
 *
 * `like` matches a pattern with `%` as the wildcard and is **case-sensitive**. For finding text inside a
 * `text:fulltext` field use {@link SchemaClient.search}, which reads the index the platform provisioned —
 * a leading-wildcard `like` cannot.
 *
 * @since 0.7.0
 */
export type SchemaOp = 'eq' | 'ne' | 'lt' | 'lte' | 'gt' | 'gte' | 'like' | 'in' | 'isNull' | 'isNotNull';

/**
 * One `field op value` condition over a declared field.
 *
 * The field name is the one your **manifest** declares; the host resolves it against that declaration and
 * answers 400 if it is not there. Values are bound as parameters, never interpolated — a query is
 * described, never written.
 *
 * @since 0.7.0
 */
export interface SchemaPredicate {
  /** The declared field name. */
  field: string;
  /** The comparison. */
  op: SchemaOp;
  /**
   * The value to compare against: an array for `in`, omitted for `isNull` / `isNotNull`.
   *
   * Give it in the field's declared type — a `boolean` for `boolean`, a number for `integer`/`number`,
   * an ISO-8601 instant string (or a `Date`) for `timestamp`. The host coerces against the declaration
   * and answers 400 on a value it cannot read as that type.
   */
  value?: unknown;
}

/**
 * Which rows, in what order, on which page — the query half of {@link SchemaClient}.
 *
 * Mirrors the Java `Criteria`, with one difference: paging here is `page`/`size` rather than
 * `limit`/`offset`, because it crosses HTTP and shares the doc surface's conventions (`page` from 0,
 * `size` 50 by default, capped at 200 by the host).
 *
 * **Predicates combine with AND.** There is no `or` in this version, exactly as in `Criteria`: model it
 * as two queries and merge.
 *
 * @since 0.7.0
 */
export interface SchemaQuery {
  /** Conditions, combined with AND. Omit to match every row. */
  where?: SchemaPredicate[];
  /** Sort terms, applied in order. Omit for the store's unspecified order. */
  orderBy?: { field: string; direction: 'asc' | 'desc' }[];
  /** Zero-based page index; defaults to 0. */
  page?: number;
  /** Page size; defaults to 50, and the host caps it at 200. */
  size?: number;
}

/**
 * One page of rows, in the same envelope the doc surface uses ({@link PagedDocs}).
 *
 * @typeParam T the row shape — name its properties for the fields your manifest declares
 * @since 0.7.0
 */
export interface SchemaPage<T = Record<string, unknown>> {
  /** The rows on this page. */
  items: T[];
  /** The zero-based page index. */
  page: number;
  /** The effective page size (the host's cap may have lowered what you asked for). */
  size: number;
  /** Total number of matching rows across all pages. */
  totalElements: number;
  /** Total number of pages. */
  totalPages: number;
}

/**
 * Read access to the relational tables the platform provisioned for a plugin that declares
 * `storage.schema` (ARCHITECTURE §7.6) — the frontend counterpart of the Java `SchemaStore`.
 *
 * `ctx.schema` is **`null`** unless the manifest declares a schema, mirroring the Java
 * `PluginContext.schema()`. The manifest is the one place a plugin says which store it uses, so a
 * doc-store plugin finds `null` here rather than a client that would 404 on every call.
 *
 * ## Why reads only
 *
 * A v1 plugin authors no HTTP routes, so there is no request-time hook where plugin code could validate a
 * write — no place to enforce slug uniqueness, append a revision atomically, or reject malformed input.
 * Exposing writes here would hand clients direct row access with no plugin code in the path. So the
 * plugin's **backend stays the only writer** of relational truth: a frontend that needs to write puts a
 * document in the doc store and the backend ingests it on its schedule. That makes such a write eventually
 * consistent — a deliberate consequence of the v1 contract, not an oversight.
 *
 * ## What the host enforces
 *
 * The same `data.readableBy` floor as the doc surface — one rule to learn, and a plugin that already
 * declares one is already covered. Beyond that, **a plugin cannot express the wrong question**: entity and
 * field names are resolved against your own manifest and the host builds the statement, so another
 * plugin's tables (or core's) are not so much blocked as unnameable. An entity you did not declare is a
 * 404; a field you did not declare, an unreadable value, or `search` on a field that is not `:fulltext` is
 * a 400.
 *
 * The endpoints, if you ever need them through {@link PluginApiClient} directly:
 *
 * ```text
 * GET /api/plugins/{id}/schema/{entity}?where=&orderBy=&page=&size=
 * GET /api/plugins/{id}/schema/{entity}/search?field=&q=&where=&orderBy=&page=&size=
 * GET /api/plugins/{id}/schema/{entity}/count?where=
 * GET /api/plugins/{id}/schema/{entity}/{rowId}
 * ```
 *
 * @example Search, then read one page
 * ```ts
 * if (!ctx.schema) return;                       // doc-store plugin: nothing to query
 *
 * interface Page { id: number; slug: string; title: string; markdown: string; updatedAt: string }
 *
 * const hits = await ctx.schema.search<Page>('page', 'markdown', term, {
 *   where: [{ field: 'published', op: 'eq', value: true }],
 *   size: 20,
 * });
 * hits.items.forEach((p) => renderHit(p.title, p.slug));
 *
 * const [page] = (await ctx.schema.select<Page>('page', {
 *   where: [{ field: 'slug', op: 'eq', value: ctx.route.path }],
 *   size: 1,
 * })).items;
 * ```
 *
 * @since 0.7.0
 */
export interface SchemaClient {
  /**
   * Rows of one declared entity, filtered and ordered by `query`.
   *
   * @param entity the entity name your manifest declares
   * @param query  the filter; omit to page through everything
   * @returns one page of rows
   */
  select<T = Record<string, unknown>>(entity: string, query?: SchemaQuery): Promise<SchemaPage<T>>;
  /**
   * Full-text search over a field declared `:fulltext`, on the index the platform provisioned.
   *
   * `text` is taken as a person would type it — quotes, `OR`, a leading minus — and a stray operator
   * never throws. An **empty** `text` matches nothing rather than everything, the same rule the Java
   * `SchemaStore.search` follows. Results come back best-match first unless `query.orderBy` replaces that
   * ordering.
   *
   * @param entity the entity name your manifest declares
   * @param field  the field declared `:fulltext`; anything else is a 400
   * @param text   what the user typed
   * @param query  extra filtering/paging, applied on top of the match
   * @returns one page of rows, ranked unless you ordered them yourself
   */
  search<T = Record<string, unknown>>(
    entity: string,
    field: string,
    text: string,
    query?: SchemaQuery,
  ): Promise<SchemaPage<T>>;
  /**
   * One row by its platform-assigned `id`.
   *
   * Resolves **`null`** when there is no such row, rather than rejecting: a row that is not there is an
   * answer to this question, and every caller would otherwise wrap it in a try/catch. This differs from
   * {@link PluginApiClient.get}, which rejects on a 404.
   *
   * @param entity the entity name your manifest declares
   * @param id     the platform-assigned row id
   * @returns the row, or `null` if it does not exist
   */
  find<T = Record<string, unknown>>(entity: string, id: number): Promise<T | null>;
  /**
   * How many rows match — without fetching them.
   *
   * @param entity the entity name your manifest declares
   * @param query  the filter; ordering and paging are ignored, as in the Java `SchemaStore.count`
   * @returns the number of matching rows
   */
  count(entity: string, query?: Pick<SchemaQuery, 'where'>): Promise<number>;
}

/**
 * One stored file (ARCHITECTURE §11), as the host describes it.
 *
 * `ref` is the file's whole identity — opaque, host-assigned. Store *that*, never the URL: the URL is
 * derived from it and the host is entitled to change how, while the ref stays true.
 *
 * `mime` is what the host determined the file to be from its bytes, not what was claimed on upload. The
 * two differ exactly when someone lied, which is why this is the value worth keeping.
 *
 * @since 0.8.0
 */
export interface BlobInfo {
  /** The host-assigned identifier; opaque, stable, never reused. Do not parse or construct one. */
  ref: string;
  /** The original filename, for display; `null` when none was supplied. Never a path the host resolves. */
  filename: string | null;
  /** The content type the host determined from the bytes. */
  mime: string;
  /** Size in bytes. */
  size: number;
  /** When the file was stored, as an ISO-8601 instant. */
  updatedAt: string;
}

/** One page of {@link BlobInfo}, in the host's usual paging shape. @since 0.8.0 */
export interface BlobPage {
  items: BlobInfo[];
  page: number;
  size: number;
  total: number;
}

/**
 * How much room this plugin has for files, as the host currently sees it. @since 0.8.0
 *
 * These are the **effective** numbers, not what the manifest asked for: an operator caps both, so a plugin
 * that declared more than the install allows gets the install's answer. Worth reading before letting
 * someone pick a file — telling them up front beats a refusal after the upload.
 */
export interface BlobQuota {
  usedBytes: number;
  quotaBytes: number;
  maxFileBytes: number;
}

/**
 * File storage for a plugin that declares a `blobs` block in its manifest (ARCHITECTURE §11) — **`null`**
 * on `ctx` when it does not, exactly like {@link SchemaClient}.
 *
 * Unlike the schema surface, **writes are the point here**. A schema write has relational invariants a
 * client cannot be trusted with; a file has none, so the manifest's `data.writableBy` floor plus a quota
 * is the whole authorization story, and a plugin's own editing UI can upload directly.
 *
 * ```ts
 * const blobs = ctx.blobs;
 * if (!blobs) return; // this plugin declared no `blobs` block
 *
 * const stored = await blobs.upload(file);
 * img.src = blobs.urlFor(stored.ref);
 * await ctx.api.put(`data/site/main/logo`, { ref: stored.ref }); // keep the ref, not the URL
 * ```
 *
 * The host checks the size against your effective ceiling, the type against the effective allow-list, and
 * then the *actual* type read from the leading bytes — so a file whose content contradicts its extension is
 * refused, and SVG is never accepted at all. A refusal rejects the promise; show it, do not swallow it,
 * because the person who picked the file is the only one who can pick a different one.
 *
 * Nothing collects orphans: a file outlives the document that referred to it, and only your plugin knows
 * which those are. Delete what you stop pointing at, or clean up from the backend on a schedule.
 *
 * @since 0.8.0
 */
export interface BlobClient {
  /**
   * Stores a file.
   *
   * The declared content type is **normalised by default** ({@link declaredTypeFor}), because the
   * browser's own answer is not reliable across engines — see that function for the failure it fixes.
   * Pass `declaredType: 'preserve'` to send `file.type` exactly as the browser reported it.
   *
   * @param file the file or blob to store — a `File` from an `<input type="file">` carries its own name
   * @param opts `filename` overrides the name and supplies one for a bare `Blob`; `declaredType`
   *             chooses between the normalised claim (default) and the browser's raw one
   * @returns the stored file's identity, carrying the host-determined MIME type
   */
  upload(
    file: File | Blob,
    opts?: { filename?: string; declaredType?: 'normalize' | 'preserve' },
  ): Promise<BlobInfo>;
  /**
   * Lists this plugin's files, newest first.
   *
   * @param opts `page` from 0 and `size` (the host caps it)
   */
  list(opts?: { page?: number; size?: number }): Promise<BlobPage>;
  /**
   * Deletes a file. Idempotent — removing what is already gone resolves rather than rejecting.
   *
   * @param ref the identifier from {@link upload}
   */
  remove(ref: string): Promise<void>;
  /**
   * The URL to load this file from — host-served, same origin, so no CSP host and no consent decision.
   *
   * Derive it at render time; do not store it.
   *
   * @param ref the identifier from {@link upload}
   */
  urlFor(ref: string): string;
  /** How much room is left, as the host currently sees it. */
  quota(): Promise<BlobQuota>;
}

/**
 * Filename extension → content type, for {@link declaredTypeFor}.
 *
 * Only the types the host's file storage accepts (§11.1) — SVG is deliberately absent, since it is never
 * storable, and guessing a type the host refuses outright would turn a clear refusal into a confusing
 * one. `jfif` is here because Windows still produces it for ordinary JPEGs.
 */
const EXTENSION_MIME_TYPES: Readonly<Record<string, string>> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  jfif: 'image/jpeg',
  webp: 'image/webp',
  gif: 'image/gif',
  avif: 'image/avif',
};

/**
 * The content type to claim for a file, restoring the one the browser was supposed to report.
 *
 * ## The bug this exists for
 *
 * Chromium fills `File.type` from its own built-in extension table. **Firefox asks the operating
 * system's MIME database**, and where that lookup fails — a sparse `shared-mime-info` on Linux, a missing
 * or hijacked registry association on Windows — it hands over `File.type === ''`. `FormData` then sends
 * the part as `application/octet-stream`, and the host refuses on the *declared* type before it ever
 * sniffs the bytes (§11.1 checks size, declared type, actual type, quota — in that order). The result is
 * a valid PNG rejected as "not a type this plugin may store", **in one browser only**, on a machine the
 * plugin author cannot reproduce.
 *
 * ## Why guessing is safe here
 *
 * Specifically because the host still reads the leading bytes. A wrong guess becomes the same 415 it
 * would have been, never a stored file of the wrong kind — this restores a claim, it does not grant
 * anything. An extension nothing maps stays untouched, so the refusal still arrives in the host's own
 * wording rather than one the SDK invented.
 *
 * {@link BlobClient.upload} calls this by default; use it directly only to show someone what will be
 * sent before they commit to the upload.
 *
 * @param file the file or blob about to be uploaded
 * @returns the browser's `type` when it gave one, else the type its extension implies, else `''`
 * @since 0.9.0
 */
export function declaredTypeFor(file: File | Blob): string {
  if (file.type) {
    return file.type;
  }
  const name = file instanceof File ? file.name : '';
  const dot = name.lastIndexOf('.');
  if (dot < 0) {
    return '';
  }
  return EXTENSION_MIME_TYPES[name.slice(dot + 1).toLowerCase()] ?? '';
}

/**
 * A transparent 1×1 SVG, as a `mask-image` value.
 *
 * This is the fallback every {@link iconMask} reference carries, and getting it wrong is a visible bug —
 * see {@link iconMask} for why `none` is the obvious guess and the wrong one.
 */
const BLANK_MASK = `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1 1'/%3E")`;

/** Icon names must be plain CSS identifiers — anything else could escape the `var()` it is spliced into. */
const ICON_NAME_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;

/**
 * The host icon names known when this SDK was published — **a hint, never a limit**.
 *
 * The `(string & {})` arm keeps the set open: autocomplete offers these, and any other string is still
 * accepted without a type error. Closing this union would pin the icon set to the SDK version and undo
 * the exact property the design protects (see {@link iconCss}).
 *
 * @since 0.9.0
 */
export type KnownIconName =
  | 'star'
  | 'clock'
  | 'search'
  | 'play'
  | 'pause'
  | 'close'
  | 'check'
  | 'chevron-left'
  | 'chevron-right'
  | 'external'
  | 'info'
  | 'warning'
  | (string & {});

/**
 * A `mask-image` value for one host icon, with the fallback that keeps a missing icon invisible.
 *
 * ## The failure mode this prevents
 *
 * An unresolved `var()` makes the whole declaration invalid at computed-value time, so `mask-image`
 * falls back to its *initial* value — `none` — leaving an unmasked element painting `currentColor`
 * across its entire box. **A missing icon renders as a solid square, not as blank space.** Writing
 * `mask-image: none` as the fallback is the obvious guess and produces exactly that; the fallback has to
 * be a real, blank image, which is what this returns.
 *
 * @param name the icon name; must be a plain CSS identifier (`chevron-left`), since it is spliced into a
 *             custom-property name
 * @returns `var(--mc-icon-<name>, <blank svg>)`, ready to assign to `mask-image`
 * @throws Error if the name is not a plain CSS identifier
 * @since 0.9.0
 */
export function iconMask(name: KnownIconName): string {
  if (!ICON_NAME_PATTERN.test(name)) {
    throw new Error(`icon name must match ${ICON_NAME_PATTERN.source}, got: ${JSON.stringify(name)}`);
  }
  return `var(--mc-icon-${name}, ${BLANK_MASK})`;
}

/**
 * A block of CSS rules for the host icons you name, to concatenate into your component's own `<style>`.
 *
 * ## Why this is a string helper and not something on `ctx`
 *
 * `--mc-icon-*` is deliberately not on the context (§12.3), and that decision is right: custom
 * properties inherit through the shadow boundary, so a plugin built against **this** SDK version picks
 * up an icon the day core publishes it — no SDK release, no manifest bump, no version skew. Putting the
 * icon set on `ctx` would trade that away. So does typing the names as a closed union, which is why
 * {@link KnownIconName} stays open.
 *
 * What was worth owning here is the *mechanics*, which are subtle enough to get wrong three ways:
 *
 * 1. **Mask, never `background-image`.** `background: currentColor` behind a mask is what makes an icon
 *    re-theme with the text beside it.
 * 2. **Every reference needs a blank-image fallback** — see {@link iconMask}, where a missing icon
 *    otherwise paints a solid square.
 * 3. **Don't declare into `--mc-*` yourself.** Those are the host's namespace.
 *
 * ## Put it in the shadow root
 *
 * The returned string belongs in your component's own `<style>` element. A bundled `.css` file lands in
 * the host document, where it **cannot reach any shadow root** — that is the other thing everyone tries
 * first, and it silently does nothing.
 *
 * ```ts
 * const style = document.createElement('style');
 * style.textContent = iconCss(['star', 'clock']);
 * root.append(style);
 * root.insertAdjacentHTML('beforeend', '<i class="mc-icon mc-icon-star" aria-hidden="true"></i>');
 * ```
 *
 * @param names the icons to emit rules for
 * @param opts  `className` renames the base class (default `mc-icon`); each icon gets
 *              `<className>-<name>`
 * @returns the CSS block — a base rule plus one rule per icon
 * @since 0.9.0
 */
export function iconCss(names: readonly KnownIconName[], opts?: { className?: string }): string {
  const base = opts?.className ?? 'mc-icon';
  if (!ICON_NAME_PATTERN.test(base)) {
    throw new Error(`className must match ${ICON_NAME_PATTERN.source}, got: ${JSON.stringify(base)}`);
  }
  // `background: currentColor` behind the mask is the whole point: the icon takes the colour of the text
  // it sits beside, so it re-themes with the host without the plugin naming a single colour.
  const rules = [
    `.${base} { display: inline-block; width: 1em; height: 1em; background: currentColor;` +
      ` -webkit-mask-repeat: no-repeat; mask-repeat: no-repeat;` +
      ` -webkit-mask-position: center; mask-position: center;` +
      ` -webkit-mask-size: contain; mask-size: contain; }`,
  ];
  for (const name of names) {
    const mask = iconMask(name);
    rules.push(`.${base}-${name} { -webkit-mask-image: ${mask}; mask-image: ${mask}; }`);
  }
  return rules.join('\n');
}

/**
 * Builders for links to the host's own pages (ARCHITECTURE §6.4). @since 0.8.0
 *
 * **Strings only.** Nothing here navigates, and nothing here is a capability: a plugin can already put any
 * `href` in its own markup. What it could not do was know the *shape* of a core URL without hardcoding it,
 * so every plugin that wanted to link to an episode wrote `` `/episodes/${slug}` `` and became a thing that
 * breaks when the host changes a route. This moves that knowledge back to the host.
 *
 * It is deliberately not part of {@link PluginRoute}, which is namespace-confined by construction —
 * `navigate` cannot name a core route and that is a property worth keeping. Producing a link is not
 * navigating: the visitor still clicks, and a real `href` is what middle-click, "open in new tab" and
 * crawlers need.
 *
 * ```ts
 * // "…discussed in The Kraken, from 12:04"
 * const href = ctx.links.episode('kraken', { t: 724 });
 * ```
 */
export interface PluginLinks {
  /**
   * A link to an episode's detail page, optionally at a position inside it.
   *
   * @param slug the episode's public slug — the one in `ctx.episodes`
   * @param opts `t` is a start position in seconds; a negative or non-finite value is ignored
   * @returns a root-relative URL
   */
  episode(slug: string, opts?: { t?: number }): string;
  /**
   * A link to a feed tab, optionally filtered.
   *
   * @param slug the feed's public slug
   * @param opts the host's filter axes (§6.1); omitted or default values are left out of the URL
   * @returns a root-relative URL
   */
  feed(slug: string, opts?: { season?: string; tag?: string; order?: 'newest' | 'oldest' }): string;
}

/**
 * The plugin's own URL subtree (ARCHITECTURE §6.4): where it is, when that changes, and how to move
 * within it.
 *
 * The host reserves `/p/<pluginId>/*` for a plugin with a `page` slot and hands it the subpath below
 * that prefix — which is what makes a wiki page, or anything else a plugin renders, linkable and
 * shareable at all.
 *
 * @since 0.7.0 — `navigate`; `path` and `onChange` have been here since 0.1.0
 */
export interface PluginRoute {
  /**
   * The subpath below `/p/<pluginId>/`; empty when the plugin is not rendered as a page.
   *
   * The path only — the query string and fragment are {@link query} and {@link hash}.
   */
  path: string;
  /**
   * The current subtree's query string, parsed by the host.
   *
   * {@link navigate} has always accepted a `?query`, and until 0.9.0 there was no way to read back what
   * it wrote: the only route was `location.search`, which is exactly the "do not reach past this handle"
   * that `navigate` forbids, for the reason it gives. Filters, sort order and pagination are the obvious
   * shareable-link state, so a plugin either invented path segments for them or broke the rule.
   *
   * Treat it as read-only — mutating it changes nothing. Write with `navigate('page?sort=new')`.
   *
   * @since 0.9.0
   */
  readonly query: URLSearchParams;
  /**
   * The current fragment, **without** the leading `#`; empty when there is none.
   *
   * @since 0.9.0
   */
  readonly hash: string;
  /**
   * Subscribes to subpath changes — a back button, a shared link, your own {@link navigate}.
   *
   * Returns an {@link Unsubscribe}. Note that the host also re-renders your element on a route change,
   * so a component that reads {@link path} at render time often needs no subscription at all.
   */
  onChange(cb: (p: string) => void): Unsubscribe;
  /**
   * Navigates within this plugin's subtree, without reloading the page.
   *
   * `subpath` is relative to `/p/<pluginId>/`, the same coordinate {@link path} hands you — so
   * `navigate('glossary/kraken')` lands on `/p/wiki/glossary/kraken`. A plugin **cannot name another
   * plugin's route or a core one**: the host prefixes its namespace and drops any attempt to climb out of
   * it, the same "cannot express the question" property {@link SchemaClient} has.
   *
   * This is real SPA navigation: a history entry, a working back button, and no re-fetch of the shell or
   * of any plugin bundle. Which is the point — an `<a href>` to your own page costs a full document load,
   * and on a densely cross-linked page set that is the common path rather than an edge case.
   *
   * **Do not reach past this handle.** `history.pushState` plus a synthetic `popstate` happens to work
   * against the host's current router and will break when it changes; it is not part of the contract.
   *
   * @param subpath the target below `/p/<pluginId>/`; may carry `?query` and `#hash`
   * @param opts    `replace: true` swaps the current history entry instead of adding one — for state
   *                that should not become a back-button step, like an in-page tab or filter
   */
  navigate(subpath: string, opts?: { replace?: boolean }): void;
}

/**
 * What {@link matchRoute} returns when a pattern matched.
 *
 * @typeParam P the literal pattern type, so `match.pattern` narrows against the array you passed
 * @since 0.9.0
 */
export interface RouteMatch<P extends string = string> {
  /** The pattern that matched, verbatim from the list you gave. */
  pattern: P;
  /** The `:name` segments, keyed by name and already `decodeURIComponent`-ed. */
  params: Record<string, string>;
}

/**
 * Matches a {@link PluginRoute.path} against a list of patterns, first match wins.
 *
 * Every page plugin hand-rolls this — a prefix check for `highlight/<slug>`, an `else` for the index —
 * and the hand-rolled version is usually a `startsWith`, which matches `highlights-archive` too.
 *
 * A pattern is segments separated by `/`. A segment beginning with `:` captures one **non-empty**
 * segment into {@link RouteMatch.params}; every other segment must match literally. The whole path must
 * be consumed, so `moments` does not match `moments/3`. The empty pattern `''` is the plugin root.
 *
 * Leading and trailing slashes are ignored, and anything from a `?` or `#` is dropped — so passing a raw
 * subpath works even where the host has not split it for you.
 *
 * **Order is yours**: list the specific pattern before the general one, since the first match wins.
 *
 * ```ts
 * const match = matchRoute(ctx.route.path, ['', 'moments', 'highlight/:slug'] as const);
 * switch (match?.pattern) {
 *   case 'highlight/:slug': return renderDetail(match.params.slug);
 *   case 'moments':         return renderMoments();
 *   case '':                return renderIndex();
 *   default:                return renderNotFound();   // null: nothing matched
 * }
 * ```
 *
 * @param path     the subpath to match, typically `ctx.route.path`
 * @param patterns the patterns to try, in priority order
 * @returns the first match, or **`null`** when none matched — which is your not-found branch
 * @since 0.9.0
 */
export function matchRoute<P extends string>(
  path: string,
  patterns: readonly P[],
): RouteMatch<P> | null {
  const segments = routeSegments(path);
  for (const pattern of patterns) {
    const expected = routeSegments(pattern);
    if (expected.length !== segments.length) {
      continue;
    }
    const params: Record<string, string> = {};
    let matched = true;
    for (let i = 0; i < expected.length; i++) {
      const part = expected[i]!;
      if (part.startsWith(':')) {
        params[part.slice(1)] = decodeURIComponent(segments[i]!);
      } else if (part !== segments[i]) {
        matched = false;
        break;
      }
    }
    if (matched) {
      return { pattern, params };
    }
  }
  return null;
}

/** Splits a subpath into its non-empty segments, dropping any query string or fragment. */
function routeSegments(path: string): string[] {
  const end = Math.min(
    ...[path.indexOf('?'), path.indexOf('#')].map((at) => (at < 0 ? path.length : at)),
  );
  return path.slice(0, end).split('/').filter((segment) => segment !== '');
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
 * The most slugs {@link FeedsClient.displayMany} will resolve in one call.
 *
 * Matching the host's existing clamp on its scope-episodes endpoint. Asking for more is not an error —
 * the extras are simply not in the answer, so check what came back rather than assuming.
 *
 * @since 0.9.0
 */
export const DISPLAY_BATCH_LIMIT = 200;

/**
 * Read access to episode display snapshots — the frontend half of the Java `FeedAccess`.
 *
 * The Java contract could read a snapshot and the frontend could not, so a plugin that wanted to draw an
 * episode card copied the host's own data into its doc store and kept it fresh on a schedule. That cost
 * a backend, a scheduled ingest, a `backendOwned` key and a copy that is stale between runs — for fields
 * the host already has. `DisplaySnapshot` and {@link resolveArtwork} have shipped since 0.1 with nothing
 * in the contract that hands a frontend one; this is what they were for.
 *
 * ## What you get, and what you don't
 *
 * **The host filters, the plugin consumes** (ARCHITECTURE §7). The answer contains only what the caller
 * may see, so a `WITHDRAWN` or tier-gated episode is **absent** rather than redacted — and this cannot
 * be used to enumerate episodes {@link PluginContext.episodes} did not already give you.
 *
 * Unlike the doc store this has **no `readableBy` gate of its own**: it returns host data the same
 * visitor can already read from `/api/episodes/*`. It exists so a plugin need not know that URL shape —
 * the same argument {@link PluginLinks} makes.
 *
 * **Not authoritative, and it should keep saying so.** The snapshot is overwritten on every feed
 * refetch. That is a feature — a feed edit propagates — and the reason to read it live rather than copy
 * it. Cache per render, never per install.
 *
 * ```ts
 * const cards = await ctx.feeds.displayMany(ctx.episodes.slice(0, 20));
 * for (const slug of ctx.episodes) {
 *   const snap = cards[slug];
 *   if (!snap) continue;                       // filtered out for this visitor — not an error
 *   render(slug, snap.title, resolveArtwork(snap));
 * }
 * ```
 *
 * @since 0.9.0
 */
export interface FeedsClient {
  /**
   * The display snapshot for one episode.
   *
   * @param slug the episode's public slug — one of {@link PluginContext.episodes}
   * @returns the snapshot, or **`null`** when the host has none or the caller may not see it. The two
   *          are deliberately indistinguishable: telling them apart would confirm the existence of an
   *          episode this visitor was not shown
   */
  display(slug: string): Promise<DisplaySnapshot | null>;
  /**
   * The same, for many slugs in one request.
   *
   * Use this whenever you are drawing more than one card — the N-request version is the thing this
   * surface exists to prevent.
   *
   * @param slugs the episode slugs; more than {@link DISPLAY_BATCH_LIMIT} is **clamped**, not rejected
   * @returns a map from slug to snapshot, containing only the episodes the caller may see — so a missing
   *          key is normal and must not be treated as a failure
   */
  displayMany(slugs: string[]): Promise<Record<string, DisplaySnapshot>>;
}

/**
 * One entry of the site's shared tag vocabulary — mirror of the Java `TagInfo` record.
 *
 * `tag` is the host's **canonical key** (trim, collapse internal whitespace, casefold), applied on every
 * path into the vocabulary including feed ingest; `label` is presentation, kept from first use. Send any
 * spelling, store and compare on the key, show the label.
 *
 * The two counts are scoped differently on purpose: `episodes` is site-wide, `subjects` counts only
 * **your own** plugin's subjects — you cannot see the size of a store you cannot read.
 *
 * @since 0.9.0
 */
export interface TagInfo {
  /** The canonical key. */
  tag: string;
  /** The display label the host kept from first use. */
  label: string;
  /** How many episodes carry this tag, across every source. */
  episodes: number;
  /** How many of *this plugin's* subjects carry it. */
  subjects: number;
}

/**
 * The site's shared tag vocabulary, and this plugin's assignments against it — the frontend half of the
 * Java `Tags` (ARCHITECTURE §6.1).
 *
 * `ctx.tags` is **`null`** unless the manifest declares a `tags` block, mirroring `ctx.schema` and
 * `ctx.blobs`. Tags existed in core only as a feed-derived filter axis with no vocabulary and no plugin
 * surface, so every plugin that wanted them grew a private free-text column — and a wiki's `lore` and an
 * episode's `lore` were unrelated strings that could not be linked, suggested or counted together.
 *
 * ```ts
 * const tags = ctx.tags;
 * if (!tags) return;                                  // no `tags` block in this plugin's manifest
 *
 * for (const t of await tags.all()) suggest(t.label, t.tag);        // the site's real vocabulary
 * const href = ctx.links.feed('the-sample-cast', { tag: 'kraken' }); // and it links to the feed view
 * ```
 *
 * ## Two writes that look alike and are not
 *
 * {@link tagSubject} touches only keys you invented in your own namespace; `data.writableBy` is the
 * whole authorization story. {@link tagEpisode} changes the shell's filter options **and** what core
 * recommends beside that episode, so it needs `"tags": { "writesEpisodes": true }` in the manifest and
 * rejects without it.
 *
 * ## What no plugin may do
 *
 * Delete a tag from the vocabulary (it is shared), rename one (that is a vocabulary-wide edit, and
 * belongs in admin), or remove another writer's assignment — the feed's included. Every write of yours
 * is recorded with your plugin as its source, which is what makes the last one enforceable rather than
 * merely discouraged.
 *
 * @since 0.9.0
 */
export interface TagsClient {
  /** The whole site vocabulary, most used first. */
  all(): Promise<TagInfo[]>;
  /**
   * The episode slugs carrying a tag, filtered to what this visitor may see.
   *
   * @param tag any spelling; the host canonicalises it. An unknown tag resolves to an empty list
   */
  episodesWith(tag: string): Promise<string[]>;
  /**
   * The canonical keys on one episode, whatever put them there.
   *
   * @param episodeSlug the episode's public slug
   */
  tagsOn(episodeSlug: string): Promise<string[]>;
  /**
   * Tags that tend to appear alongside this one, best first — "what else on this site is about this".
   *
   * The ranking is the host's and is **not** part of the contract: treat the order as advice, and do not
   * display or compare the counts as a similarity score.
   *
   * @param tag   any spelling of the tag
   * @param limit the most entries to return; the host clamps rather than failing
   */
  similarTo(tag: string, limit?: number): Promise<TagInfo[]>;
  /** This plugin's own subject keys carrying a tag. */
  subjectsWith(tag: string): Promise<string[]>;
  /** The canonical keys on one of this plugin's subjects. */
  tagsOnSubject(subjectKey: string): Promise<string[]>;
  /**
   * Tags one of this plugin's own subjects, adding the tag to the vocabulary if it is new. Idempotent.
   *
   * `subjectKey` is opaque and yours to invent — the same namespacing property `ctx.schema` has for
   * tables and `ctx.route.navigate` has for URLs. Use the same key a `SearchProvider` hit resolves to,
   * so a tag and a search result name one object rather than two.
   *
   * @param subjectKey the subject in your namespace
   * @param tag        any spelling; your spelling becomes the display label if the tag is new
   */
  tagSubject(subjectKey: string, tag: string): Promise<void>;
  /** Removes a tag from one of this plugin's subjects. Idempotent; never removes the tag itself. */
  untagSubject(subjectKey: string, tag: string): Promise<void>;
  /**
   * Tags an episode — **a capability, not a convenience**.
   *
   * Needs `tags.writesEpisodes` in the manifest; without it the host answers 403. Additive: an episode
   * already carrying the tag from its feed keeps the feed's assignment and gains yours.
   */
  tagEpisode(episodeSlug: string, tag: string): Promise<void>;
  /**
   * Removes **this plugin's** assignment from an episode, and only that one.
   *
   * If the feed or a podcaster also put this tag there, the episode keeps carrying it.
   */
  untagEpisode(episodeSlug: string, tag: string): Promise<void>;
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
 * The shape of your manifest's `data` block — who may reach the doc store over HTTP, and which keys only
 * your backend may write.
 *
 * **This type is documentation, not enforcement**, on the same terms as {@link ConsentServiceDeclaration}:
 * the manifest is owned and validated by the host, nothing here runs at load time, and if this type and
 * core disagree, core wins. It is typed because the block carries a security control — a typo in
 * `backendOwned` protects nothing and says nothing, which is the worst way for a declaration to fail.
 *
 * ```ts
 * const data: PluginDataDeclaration = {
 *   readableBy: 'anonymous',
 *   writableBy: 'podcaster',
 *   backendOwned: ['stats', 'agg:*'],
 * };
 * ```
 *
 * @since 0.6.0
 */
export interface PluginDataDeclaration {
  /**
   * The minimum role that may **read** the doc store.
   *
   * Omit the whole block and this falls back to {@link writableBy}, not to `anonymous` — saying nothing
   * gets the safe answer. Never applies to the `user` scope, which is the caller's own either way.
   */
  readableBy?: DataAccessRole;
  /**
   * The minimum role that may **write** the doc store. May not be `anonymous`.
   *
   * Governs shared scopes only: a `user` partition is unshared, so its owner writes it whatever this says.
   */
  writableBy: Exclude<DataAccessRole, 'anonymous'>;
  /**
   * Keys your backend authors, which clients may read but never `PUT` or `DELETE` (403).
   *
   * Each entry is an exact key, a prefix ending in `*`, or the bare `*` for "the whole store is computed"
   * — matching `^(\*|[A-Za-z0-9._:-]{1,200}\*?)$`, mirrored from the Java `DocStore.BACKEND_OWNED_PATTERN`.
   * A `*` in the middle, or an empty entry, is rejected when the plugin loads.
   *
   * Declare a key here **and write it in your backend's `register`**, not only on a schedule: the
   * declaration refuses new client writes but does not remove a value a client wrote before it existed, so
   * a forged document otherwise survives until the next tick. It is ignored for `user` partitions, which
   * the backend cannot write at all — even a bare `*` leaves those to their owner.
   */
  backendOwned?: string[];
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
 * The shape of your manifest's `tags` block — whether this plugin reads the site vocabulary, and whether
 * it may tag **episodes**.
 *
 * Documentation, not enforcement, on the same terms as {@link PluginDataDeclaration}. Typed because the
 * second flag is a capability: a plugin that tags episodes changes the shell's filter options and what
 * core recommends, so what it may do should be readable off its manifest.
 *
 * ```ts
 * const tags: PluginTagsDeclaration = { readsVocabulary: true, writesEpisodes: false };
 * ```
 *
 * @since 0.9.0
 */
export interface PluginTagsDeclaration {
  /**
   * Whether this plugin may read the vocabulary and tag its own subjects.
   *
   * Declaring the block at all is what makes `ctx.tags` non-`null`; this is the read-and-own-subjects
   * half, and `data.writableBy` governs the writes.
   */
  readsVocabulary?: boolean;
  /**
   * Whether this plugin may tag and untag **episodes**.
   *
   * Off by default. Without it `tagEpisode` / `untagEpisode` are refused — the host does not silently
   * drop the write. Ask for it only if the plugin genuinely classifies episodes.
   */
  writesEpisodes?: boolean;
}

/**
 * One entry of `slots[]`: which component the host mounts, where, and for whom (ARCHITECTURE §7.3).
 *
 * @since 0.9.0
 */
export interface PluginSlotDeclaration {
  /** The scope level the slot appears on. */
  scope: Scope['type'];
  /** The custom-element tag to mount — must also appear in `frontend.elements`. */
  element: string;
  /** The named region of the host shell (`main`, `sidebar`, `card`, `admin`, `feed`, `site`, …). */
  placement: string;
  /** The minimum role that sees it. **Rendering only** — it never governs data access. */
  visibleTo?: DataAccessRole;
  /** Sort order within a region; ties break on plugin id. */
  order?: number;
}

/**
 * One entry of `nav[]`: an entrance to this plugin in the host's menu.
 *
 * @since 0.9.0
 */
export interface PluginNavDeclaration {
  /** The subpath below `/p/<pluginId>/` this entry opens. */
  path: string;
  /**
   * The menu label.
   *
   * **Not translatable**, and that is a real constraint rather than an oversight: core has no plugin
   * catalogs, so it cannot translate a label a plugin supplied. Your own in-page tab bar *can* translate
   * it, which is exactly where the two legitimately diverge — pin `path`, `icon` and `role` between the
   * two lists, and let the label differ.
   */
  label: string;
  /** A host icon name (`--mc-icon-*`), if the menu should show one. */
  icon?: string;
  /** The minimum role that sees this entry. */
  role?: DataAccessRole;
}

/** One declared config field, rendered by core as a generic admin form (§7.2). @since 0.9.0 */
export interface PluginConfigField {
  /** The value type the admin form renders. */
  type: 'string' | 'number' | 'boolean';
  /** The value used until an operator changes it. */
  default?: string | number | boolean;
  /** The minimum role that may edit it. */
  editableBy?: DataAccessRole;
  /** A short explanation shown beside the field. */
  description?: string;
}

/** The shape of the manifest's `blobs` block (ARCHITECTURE §11.1). @since 0.9.0 */
export interface PluginBlobsDeclaration {
  /** The per-file ceiling you are asking for; the operator may grant less. */
  maxFileBytes: number;
  /** The total quota you are asking for; the operator may grant less. */
  quotaBytes: number;
  /** The content types you want to store. `image/svg+xml` is refused at load — SVG is never storable. */
  mimeTypes: string[];
}

/**
 * One kind of admin-configured third-party service (ARCHITECTURE §16).
 *
 * A closed union rather than `string`, and that is the point: the host ships one provider bean per kind, so
 * a kind it does not have is not a service that is merely unconfigured — it is a name nothing answers to.
 * Transcription, text-to-speech and embeddings are the shapes §16 is built to take next; each arrives as a
 * member here on a minor bump.
 *
 * @since 0.11.0
 */
export type ExternalServiceKind = 'translation';

/**
 * The shape of your manifest's `external` block — which admin-configured services this plugin uses, and who
 * may trigger a call (ARCHITECTURE §16).
 *
 * **Declaring this is what makes {@link PluginContext.translation} non-`null`.** Without it the handle is
 * `null` no matter what the operator configured, exactly as `blobs` and `tags` behave. That is not
 * bookkeeping: an external call spends the operator's money on somebody else's metered API, and the host's
 * rate limiter keys on kind and provider, so a site whose budget is gone should be able to read off the
 * installed manifests which plugins could have spent it.
 *
 * ```ts
 * const external: PluginExternalDeclaration = { kinds: ['translation'], usedBy: 'podcaster' };
 * ```
 *
 * @since 0.11.0
 */
export interface PluginExternalDeclaration {
  /**
   * Which service kinds this plugin will use.
   *
   * A list, though `'translation'` is the only member today: a plugin that later wants transcription adds
   * an entry rather than a second block.
   */
  kinds: ExternalServiceKind[];
  /**
   * The lowest role that may trigger a call **from this plugin's UI**. Defaults to `podcaster`, matching
   * {@link PluginDataDeclaration.writableBy}'s floor.
   *
   * **Browser-side only.** A backend has no visitor and no role — `register()` and `onSchedule` run at
   * startup or on a timer — so this never governs the Java `ctx.translation()`. There, declaring the kind
   * is the whole gate. Core enforces this floor at the endpoint the browser client calls.
   *
   * `anonymous` is legal and is almost always wrong: a metered provider plus an anonymous floor is an open
   * spending endpoint, reachable by anyone who can load the page. Ask for it only if you can say why the
   * operator would want to pay for a stranger's translation.
   *
   * One floor for the whole plugin, not one per kind. Should a later kind need its own, it arrives as an
   * additional field that **narrows** this one and never widens it — so a manifest written today keeps
   * meaning what it says.
   */
  usedBy?: DataAccessRole;
}

/**
 * The whole of `plugin.json`, typed.
 *
 * ## Read this before relying on it
 *
 * **Documentation, not enforcement** — the same caveat {@link PluginDataDeclaration} and
 * {@link ConsentServiceDeclaration} have carried since 0.4.0, now extended to the rest of the file. The
 * manifest is owned and validated by the **host**; the SDK does not read `plugin.json`, and nothing here
 * runs at build or load time. If this type and core disagree, **core wins** — treat a mismatch as a bug
 * in the SDK, not as permission to ignore the host. Unknown fields are ignored by the host, so this type
 * permits them too.
 *
 * The argument for typing the two nested blocks was that a typo in a *security declaration* protects
 * nothing and says nothing. The argument for the rest is drift: `nav[]` and a plugin's own in-page tab
 * bar are the same list of entrances declared twice — once here for the host's menu, once in code — and
 * a renamed path otherwise becomes a menu entry leading to an empty view with nothing to catch it. With
 * a full type you can generate `plugin.json` from a TypeScript module at build time and delete the
 * reconciliation test; even without generating it, the type alone catches an element listed in `slots[]`
 * but missing from `frontend.elements`, which is currently a load-time failure found by hand.
 *
 * @since 0.9.0
 */
export interface PluginManifest {
  /** The plugin id — the namespace for its data, its routes (`/p/<id>/…`) and its tables. */
  id: string;
  /** The plugin's own version. */
  version: string;
  /**
   * The contract version this plugin was built against — {@link PLATFORM_API_VERSION}.
   *
   * The host matches `major.minor` **exactly** and rejects a mismatch at startup. There is no forward or
   * backward tolerance, so this moves with every SDK minor.
   */
  platformApi: string;
  /** The plugin's display name. */
  name: string;
  /** SPDX licence id. Never validated — credit is not a correctness concern. */
  license?: string;
  /** Who wrote it. */
  author?: string;
  /** Where the plugin lives. */
  homepage?: string;
  /** Who deserves credit — borrowed data, artwork, an upstream library. Separate from `homepage`. */
  attribution?: string;
  /** The backend half: where its API lives and which classes implement its extension points. */
  backend?: {
    /** The base path the host serves this plugin's data surface on. */
    basePath?: string;
    /**
     * Fully-qualified class names implementing `PluginBackend` and any optional extension points —
     * `SitemapProvider`, `ShareMetadataProvider`, `SearchProvider`, `UserDataHandler`.
     */
    extensions: string[];
  };
  /** The frontend half: the bundle and the custom elements it registers. */
  frontend?: {
    /** The ES module entry, relative to the plugin's bundle. */
    entry: string;
    /** Every custom-element tag the entry registers. Each `slots[].element` must be one of these. */
    elements: string[];
  };
  /** Where this plugin's components mount. */
  slots?: PluginSlotDeclaration[];
  /** Entrances in the host's menu, for a plugin with a `page` placement. */
  nav?: PluginNavDeclaration[];
  /** `"doc"` for the generic JSON store, or a schema declaration for provisioned tables (§7.6). */
  storage?: 'doc' | { schema: Record<string, Record<string, string>> };
  /** Who may read and write the doc store, and which keys only the backend writes. */
  data?: PluginDataDeclaration;
  /** Opt-in file storage. Absent means no file storage at all. */
  blobs?: PluginBlobsDeclaration;
  /** Opt-in tag surface. Absent means `ctx.tags` is `null`. */
  tags?: PluginTagsDeclaration;
  /** Opt-in use of admin-configured third-party services. Absent means `ctx.translation` is `null`. */
  external?: PluginExternalDeclaration;
  /** Config fields core renders as an admin form; plugins never build their own config UI. */
  config?: Record<string, PluginConfigField>;
  /** Third-party services this plugin loads. Omit entirely when it loads none. */
  consent?: { services: ConsentServiceDeclaration[] };
  /** The host ignores fields it does not know, so this type does too. */
  [field: string]: unknown;
}

/**
 * Identity function that type-checks a manifest literal.
 *
 * It exists for the inference: writing `const manifest = { … }` gives you a widened object with no
 * checking, while `defineManifest({ … })` checks the shape and still infers the literal types. Emit the
 * result as `plugin.json` from a build step and the manifest stops being a second, unchecked copy of
 * what your code already knows.
 *
 * **It validates nothing at runtime** — see {@link PluginManifest}. The host is the validator.
 *
 * ```ts
 * export default defineManifest({
 *   id: 'sample', version: '1.0.0', platformApi: PLATFORM_API_VERSION, name: 'Sample',
 *   frontend: { entry: 'sample.es.js', elements: ['sample-card'] },
 *   slots: [{ scope: 'episode', element: 'sample-card', placement: 'main', visibleTo: 'anonymous' }],
 *   data: { writableBy: 'podcaster', readableBy: 'anonymous' },
 * });
 * ```
 *
 * @param m the manifest
 * @returns the same object, unchanged
 * @since 0.9.0
 */
export function defineManifest<const T extends PluginManifest>(m: T): T {
  return m;
}

/**
 * One language the host knows about (ARCHITECTURE §12.7).
 *
 * @since 0.10.0
 */
export interface LocaleInfo {
  /** The locale code, lower-cased: `en`, `de`, `pt-br`. */
  code: string;
  /** The language's name in its own language — `Nederlands`, not `Dutch`. Ready to render in a menu. */
  nativeName: string;
  /** Whether this is the site default: the last fallback for anything stored per locale. */
  isDefault: boolean;
}

/**
 * One thing to translate.
 *
 * @since 0.10.0
 */
export interface TranslationRequest {
  /** The text. The host caps length and rejects an oversized request rather than truncating it. */
  text: string;
  /** A locale code, or `'auto'` to let the provider detect it. Defaults to `'auto'`. */
  from?: string;
  /** A locale code. Required — there is no default target. */
  to: string;
  /**
   * `'text'` (default) or `'html'`. Markdown is neither: send it as text and expect links and code fences
   * to come back mangled, because a translator does not know they are markup.
   */
  format?: 'text' | 'html';
}

/**
 * What came back.
 *
 * @since 0.10.0
 */
export interface TranslationResult {
  text: string;
  /** What the provider thinks the source language was, or `null` when it does not say. */
  detectedSourceLanguage: string | null;
  /** Which provider produced it — the admin chooses one per site, and may change it. */
  providerId: string;
  /** Whether the host answered from its cache instead of calling the provider. */
  fromCache: boolean;
}

/**
 * Machine translation, mediated by the host (ARCHITECTURE §16, §12.7).
 *
 * **The host owns the provider, the credentials and the cache.** A plugin never talks to a translation
 * service directly: the site admin picks one, or none, and this is the only way through. That is
 * deliberate — the alternative is every plugin shipping its own API key, which is both a support burden
 * for the operator and a bill they never agreed to.
 *
 * **Reaching this interface takes a manifest declaration** — `external.kinds` must contain `'translation'`
 * ({@link PluginExternalDeclaration}), and `external.usedBy` is the floor the host enforces on the browser
 * call. See {@link PluginContext.translation} for the two ways you end up with `null` instead.
 *
 * **Machine output is a draft. Show it as one.** Core's own legal-page prefill never writes a translated
 * page without a human saving it, for the same reason §12.6 declines to ship legal texts at all: a
 * translation nobody has read is not made safer by being automatic.
 *
 * @since 0.10.0
 */
export interface TranslationClient {
  /**
   * Translates one string.
   *
   * @param request what to translate, and into what
   * @returns the translation
   * @throws PluginApiError 403 when the visitor is below {@link PluginExternalDeclaration.usedBy}, 409 when
   *         the admin removed the provider between your `available()` check and this call, 429 when the
   *         site is over its rate limit, 503 when the host is saturated, 504 on a provider timeout. Show
   *         the failure rather than silently falling back to the untranslated string — a reader who cannot
   *         tell a translation from an original is worse off than one who sees an error.
   */
  translate(request: TranslationRequest): Promise<TranslationResult>;
  /** Whether a call would even be attempted. Disable your button on this rather than letting a click 409. */
  available(): boolean;
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
   * A typed client for the same doc store {@link api} reaches — path building, key validation and
   * null-on-404 done for you.
   *
   * Never `null`: every plugin has a doc store. `ctx.api` remains the escape hatch for anything this
   * does not cover. See {@link DocClient}, and note `'self'` for the caller's own partition.
   *
   * @since 0.9.0
   */
  docs: DocClient;
  /**
   * Episode display snapshots, resolved and access-filtered by the host — the frontend half of the Java
   * `FeedAccess` (ARCHITECTURE §4.2).
   *
   * Never `null`, and gated by no manifest declaration: it returns host data the visitor can already
   * read. See {@link FeedsClient}, and read it live — the snapshot is not authoritative.
   *
   * @since 0.9.0
   */
  feeds: FeedsClient;
  /**
   * The site's shared tag vocabulary, or **`null`** when the manifest declares no `tags` block
   * (ARCHITECTURE §6.1).
   *
   * The frontend half of the Java `ctx.tags()`, `null` for the same reason `schema` and `blobs` are.
   * See {@link TagsClient} — and note that tagging an *episode* is a second, separate declaration.
   *
   * @since 0.9.0
   */
  tags: TagsClient | null;
  /**
   * Read access to this plugin's provisioned relational tables, or **`null`** when the manifest declares
   * no `storage.schema` (ARCHITECTURE §7.6).
   *
   * The frontend half of the Java `ctx.schema()`, and `null` for the same reason it is: a doc-store
   * plugin has no tables, and the manifest is the single place that is decided. Check it before use —
   * TypeScript will make you. See {@link SchemaClient} for why it is read-only.
   *
   * @since 0.7.0
   */
  schema: SchemaClient | null;
  /**
   * File storage for this plugin, or **`null`** when the manifest declares no `blobs` block
   * (ARCHITECTURE §11).
   *
   * The frontend half of the Java `ctx.blobs()`, `null` for the same reason `schema` is: what a plugin may
   * store is decided in the manifest and nowhere else. Check it before use — TypeScript will make you.
   *
   * This is what lets a plugin accept a file from the site's own podcaster instead of sending them to find
   * an image host first. See {@link BlobClient}.
   *
   * @since 0.8.0
   */
  blobs: BlobClient | null;
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
   * `onChange` returns an {@link Unsubscribe}. `navigate` moves within that subtree — see
   * {@link PluginRoute}.
   */
  route: PluginRoute;
  /**
   * Builders for links to the host's own pages — episodes, feed tabs (ARCHITECTURE §6.4).
   *
   * Strings, not navigation: put the result in an `href` and let the visitor click. See
   * {@link PluginLinks} for why this is separate from {@link route}.
   *
   * @since 0.8.0
   */
  links: PluginLinks;
  /**
   * The UI locale, and which languages this site has (ARCHITECTURE §12.7).
   *
   * `onChange` returns an {@link Unsubscribe}. {@link createPluginI18n} subscribes to this for you and
   * hands back a `dispose` to undo it.
   *
   * **`available` and `content` are different lists, and the difference matters.** `available` is what
   * the shell can *render* in — use it if you mirror the language switcher. `content` is what the admin
   * permits text to be *authored* in, and it is the one an editor wants: a site can require a Dutch
   * imprint without offering a Dutch UI, and a plugin that offered authoring tabs from `available` would
   * silently refuse the language its operator actually asked for.
   *
   * Both are the host's answer, not yours: they change when an admin edits them, and a language in
   * `content` may have no catalog at all — so do not assume `ctx.locale.content()` and your own
   * `createPluginI18n` catalogs line up. They routinely will not, and that is fine: your UI falls back to
   * English while the *content* is written in Dutch.
   */
  locale: {
    current(): string;
    onChange(cb: (l: string) => void): Unsubscribe;
    /**
     * The languages the shell can render in.
     *
     * @since 0.10.0
     */
    available(): LocaleInfo[];
    /**
     * The languages content may be authored in.
     *
     * @since 0.10.0
     */
    content(): LocaleInfo[];
  };
  /**
   * Machine translation, or **`null`** (ARCHITECTURE §16, §12.7).
   *
   * ## There are two independent reasons for `null`, and you must handle both
   *
   * 1. **Your manifest did not ask.** `external.kinds` does not contain `'translation'` — see
   *    {@link PluginExternalDeclaration}. Yours to fix, in your own `plugin.json`.
   * 2. **The operator configured no provider.** Which is every site until an admin chooses one, and it
   *    can change back while your plugin is running.
   *
   * They are deliberately **indistinguishable at runtime**: one `null`, no discriminator. The first is a
   * static fact about a file you wrote, so a plugin that wants to know can read its own manifest, and a
   * handle that answered it would be API surface for a question the author already has the answer to. If
   * you are staring at an unexpected `null`, check the manifest before the admin panel.
   *
   * `null` for the same reason `schema`, `blobs` and `tags` are — a capability that may not exist should be
   * one TypeScript makes you notice. Unlike those three, half of the gate moves under a running plugin, so
   * **do not cache the handle**: read `ctx.translation` at the point of use.
   *
   * {@link PluginExternalDeclaration.usedBy} is the other half of the browser story: the host refuses a
   * call from a visitor below that role even when this handle is non-`null`, so a low-privilege visitor can
   * hold a handle whose `translate()` is a 403.
   *
   * @since 0.10.0
   */
  translation: TranslationClient | null;
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
  /**
   * Translates a **count-dependent** key, picking the plural form the active locale actually needs.
   *
   * A catalog could not express "1 highlight" / "5 highlights" at all, so plugins shipped an
   * English-shaped `if (n === 1)` — wrong in Polish, Russian and Arabic, which have three to six forms —
   * or wrote two keys and chose between them by hand.
   *
   * Catalog keys are the base key plus a dot and a CLDR category: `zero`, `one`, `two`, `few`, `many`,
   * `other`. Only `other` is required; a locale that needs a form you did not write falls back to it.
   *
   * ```json
   * { "moments.one": "{{count}} moment", "moments.other": "{{count}} moments" }
   * ```
   * ```ts
   * i18n.plural('moments', list.length);          // `count` is interpolated for you
   * ```
   *
   * Resolution walks the same path as {@link t}: active locale → `en` → the base key itself. `count` is
   * added to `params` automatically, and a `count` you pass explicitly wins.
   *
   * @param key    the base message key, without the category suffix
   * @param count  how many
   * @param params values for `{{placeholder}}` interpolation, on top of `count`
   * @since 0.9.0
   */
  plural(key: string, count: number, params?: Record<string, string | number>): string;
  /**
   * Formats a number for the active locale.
   *
   * @param value the number
   * @param opts  passed straight to `Intl.NumberFormat`
   * @since 0.9.0
   */
  n(value: number, opts?: Intl.NumberFormatOptions): string;
  /**
   * Formats a date for the active locale.
   *
   * Accepts the ISO-8601 instant strings the contract hands over — `DisplaySnapshot.publishedAt`,
   * `BlobInfo.updatedAt` — as well as a `Date`.
   *
   * @param value an ISO-8601 instant or a `Date`
   * @param opts  passed straight to `Intl.DateTimeFormat`; defaults to `{ dateStyle: 'medium' }`
   * @returns the formatted date, or the input unchanged when it is not a readable date
   * @since 0.9.0
   */
  date(value: string | Date, opts?: Intl.DateTimeFormatOptions): string;
  /**
   * Formats a runtime as `H:MM:SS` (or `M:SS` under an hour), with the active locale's digits.
   *
   * Contract-adjacent by design: `DisplaySnapshot.duration` is an **ISO-8601 duration string**, so this
   * takes one directly as well as a plain number of seconds. Every plugin was hand-rolling the `90` →
   * `"1:30"` conversion.
   *
   * @param value seconds, or an ISO-8601 duration such as `PT1H2M3S`
   * @returns the formatted runtime, or `''` when the value cannot be read as one
   * @since 0.9.0
   */
  duration(value: number | string): string;
  /**
   * Formats a byte count for the active locale — for showing a `BlobQuota` to a podcaster.
   *
   * **Decimal units** (1 kB = 1000 B), so the number agrees with what the visitor's own file manager
   * showed them. Locale-correct throughout, including the decimal separator — the hand-rolled version
   * hardcodes `.`, which is simply wrong in `de`.
   *
   * @param value a size in bytes
   * @since 0.9.0
   */
  bytes(value: number): string;
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

/** The decimal byte units, largest last — `bytes` walks this from the bottom. */
const BYTE_UNITS: ReadonlyArray<[string, string]> = [
  ['byte', 'B'],
  ['kilobyte', 'kB'],
  ['megabyte', 'MB'],
  ['gigabyte', 'GB'],
  ['terabyte', 'TB'],
];

/** `PT1H2M3S` → seconds. Returns `null` for anything that is not an ISO-8601 duration. */
function parseIsoDuration(value: string): number | null {
  const match = /^P(?:\d+D)?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(value.trim());
  if (!match || (match[1] === undefined && match[2] === undefined && match[3] === undefined)) {
    return null;
  }
  return Number(match[1] ?? 0) * 3600 + Number(match[2] ?? 0) * 60 + Number(match[3] ?? 0);
}

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
  // Narrowed to the two members this actually uses. `ctx.locale` grew `available()` and `content()` in
  // 0.10.0, and asking for the whole handle would break every plugin test that hands in a two-method
  // double — for a widening this function does not care about.
  locale: Pick<PluginContext['locale'], 'current' | 'onChange'>,
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
    plural(key, count, params) {
      // The locale decides which forms exist — an English-shaped `count === 1` is wrong in most of them.
      const category = new Intl.PluralRules(active).select(count);
      const template =
        catalogs[active]?.[`${key}.${category}`] ??
        catalogs[active]?.[`${key}.other`] ??
        catalogs[SOURCE_LOCALE]?.[`${key}.${category}`] ??
        catalogs[SOURCE_LOCALE]?.[`${key}.other`] ??
        key;
      return interpolate(template, { count, ...params });
    },
    n(value, opts) {
      return new Intl.NumberFormat(active, opts).format(value);
    },
    date(value, opts) {
      const date = value instanceof Date ? value : new Date(value);
      if (Number.isNaN(date.getTime())) {
        // Better a visible bad value than a component that throws mid-render over one malformed field.
        return String(value);
      }
      return new Intl.DateTimeFormat(active, opts ?? { dateStyle: 'medium' }).format(date);
    },
    duration(value) {
      const seconds = typeof value === 'number' ? value : parseIsoDuration(value);
      if (seconds == null || !Number.isFinite(seconds) || seconds < 0) {
        return '';
      }
      const whole = Math.floor(seconds);
      const parts = [Math.floor(whole / 3600), Math.floor((whole % 3600) / 60), whole % 60];
      const lead = new Intl.NumberFormat(active, { useGrouping: false });
      const padded = new Intl.NumberFormat(active, { minimumIntegerDigits: 2, useGrouping: false });
      // Drop the hour when there isn't one, so a 90-second clip reads `1:30` rather than `0:01:30`.
      const shown = parts[0] === 0 ? parts.slice(1) : parts;
      return shown.map((part, i) => (i === 0 ? lead : padded).format(part)).join(':');
    },
    bytes(value) {
      if (!Number.isFinite(value)) {
        return '';
      }
      // Decimal units so the number matches what the visitor's file manager told them.
      let index = 0;
      let scaled = Math.abs(value);
      while (scaled >= 1000 && index < BYTE_UNITS.length - 1) {
        scaled /= 1000;
        index++;
      }
      const signed = value < 0 ? -scaled : scaled;
      const [unit, symbol] = BYTE_UNITS[index]!;
      // Whole bytes read oddly as `1.5 B`; everything above keeps one decimal.
      const digits = index === 0 ? 0 : 1;
      try {
        return new Intl.NumberFormat(active, {
          style: 'unit',
          unit,
          unitDisplay: 'short',
          maximumFractionDigits: digits,
        }).format(signed);
      } catch {
        // `style: 'unit'` is widely supported but not universal; the separator still has to be right.
        const number = new Intl.NumberFormat(active, { maximumFractionDigits: digits }).format(signed);
        return `${number} ${symbol}`;
      }
    },
  };
}
