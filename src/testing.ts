// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * `@mosaicast/plugin-sdk/testing` — dev-only test doubles for frontend plugins (ARCHITECTURE §13.5).
 *
 * Mount your Web Component with {@link makeMockCtx} and assert against the rendered DOM and the recorded
 * `api` calls — no core, no network. This subpath is not part of the production surface.
 *
 * @packageDocumentation
 */

import type {
  BlobClient,
  BlobInfo,
  BlobPage,
  BlobQuota,
  ConsentApi,
  FilterState,
  LogLevel,
  PluginApiClient,
  PluginContext,
  PluginRoute,
  SchemaClient,
  SchemaPage,
  SchemaPredicate,
  SchemaQuery,
  ThemeTokens,
} from './index.js';

/** One recorded {@link PluginContext.log} call. */
export interface LogRecord {
  /** The severity it was logged at. */
  level: LogLevel;
  /** The logged message. */
  message: string;
}

/** One recorded {@link PluginRoute.navigate} call. */
export interface NavigationRecord {
  /** The subpath the component asked for, verbatim — the mock does not normalise it. */
  subpath: string;
  /** Whether the call asked to replace the current history entry. */
  replace: boolean;
}

/** One recorded {@link MockSchemaClient} query. */
export interface SchemaQueryRecord {
  /** Which method was called. */
  method: 'select' | 'search' | 'find' | 'count';
  /** The entity it was called on. */
  entity: string;
  /** The query, for `select` / `search` / `count`. */
  query?: SchemaQuery;
  /** The searched field, for `search`. */
  field?: string;
  /** The search text, for `search`. */
  text?: string;
  /** The row id, for `find`. */
  id?: number;
}

/** One recorded {@link MockApiClient} call. */
export interface RecordedCall {
  /** HTTP method, lowercase. */
  method: 'get' | 'post' | 'put' | 'delete';
  /** The requested path (relative to the plugin base). */
  path: string;
  /** The request body, if any. */
  body?: unknown;
}

/** A {@link PluginApiClient} that records calls and returns canned responses. */
export interface MockApiClient extends PluginApiClient {
  /** Every call made through this client, in order. */
  readonly calls: RecordedCall[];
  /**
   * Canned responses keyed by `"<method> <path>"` (e.g. `"get /board"`) or bare `path`; the more
   * specific key wins. Missing keys resolve to `undefined`.
   */
  responses: Record<string, unknown>;
}

/** The unsubscribe returned by the inert `onChange` doubles — there is nothing to detach. */
const noop = (): void => {};

function makeMockApi(responses: Record<string, unknown>): MockApiClient {
  const calls: RecordedCall[] = [];
  const resolve = (method: RecordedCall['method'], path: string, body?: unknown): Promise<never> => {
    calls.push({ method, path, body });
    const canned = api.responses[`${method} ${path}`] ?? api.responses[path];
    return Promise.resolve(canned as never);
  };
  const api: MockApiClient = {
    calls,
    responses,
    get: (path) => resolve('get', path),
    post: (path, body) => resolve('post', path, body),
    put: (path, body) => resolve('put', path, body),
    delete: (path) => resolve('delete', path),
  };
  return api;
}

/** A {@link ConsentApi} whose grants a test can drive. */
export interface MockConsent extends ConsentApi {
  /** Grants a category and notifies subscribers. Granting an already-granted category does nothing. */
  grant(category: string): void;
  /**
   * Withdraws a category and notifies subscribers, as the host's settings page can mid-session.
   *
   * Revoking `necessary` does nothing — the host cannot withdraw it either.
   */
  revoke(category: string): void;
  /** Every category passed to {@link ConsentApi.request}, in order. */
  readonly requests: string[];
  /**
   * What {@link ConsentApi.request} resolves with — i.e. what the visitor decides.
   *
   * `false` by default: the visitor says no. Set it to `true` to test the accepted path, which also
   * grants the category as the host would.
   */
  autoGrantOnRequest: boolean;
}

/**
 * Builds a {@link ConsentApi} for tests, **denying everything** unless told otherwise.
 *
 * Deny is the default on purpose. The host denies until granted, so a component written against a
 * permissive mock is a component whose placeholder path was never exercised — and that path is the entire
 * point of the consent contract (ARCHITECTURE §12.5).
 *
 * The one exception is `necessary`, which is granted from the start and cannot be revoked in the host
 * either — it is the category the core itself uses.
 *
 * @param initial categories granted from the start, on top of `necessary`
 * @returns a consent double with `grant`/`revoke` and a recorded `requests` list
 *
 * @example
 * ```ts
 * const consent = makeMockConsent();
 * const ctx = makeMockCtx({ consent });
 *
 * mount(ctx);                      // renders the click-to-load placeholder
 * consent.autoGrantOnRequest = true;
 * await clickPlaceholder();
 * expect(consent.requests).toEqual(['analytics']);
 *
 * consent.revoke('analytics');     // fires onChange; the component should go back to the placeholder
 * ```
 */
export function makeMockConsent(initial: string[] = []): MockConsent {
  // `necessary` is what the core itself uses: always granted, never prompted for. Seeding it means a
  // component gated on it behaves in tests the way it will in the host.
  const granted = new Set(['necessary', ...initial]);
  const subscribers = new Set<() => void>();
  const requests: string[] = [];
  const notify = (): void => {
    subscribers.forEach((cb) => cb());
  };

  return {
    requests,
    autoGrantOnRequest: false,
    has: (category) => granted.has(category),
    granted: () => [...granted],
    request(category) {
      requests.push(category);
      if (this.autoGrantOnRequest && !granted.has(category)) {
        granted.add(category);
        notify();
      }
      return Promise.resolve(granted.has(category));
    },
    grant(category) {
      if (!granted.has(category)) {
        granted.add(category);
        notify();
      }
    },
    revoke(category) {
      // The host cannot withdraw `necessary`, so neither can the double.
      if (category !== 'necessary' && granted.delete(category)) {
        notify();
      }
    },
    onChange(cb) {
      subscribers.add(cb);
      return () => subscribers.delete(cb);
    },
  };
}

/** A {@link SchemaClient} that answers from in-memory rows and records every query. */
export interface MockSchemaClient extends SchemaClient {
  /** Every query made through this client, in order. */
  readonly queries: SchemaQueryRecord[];
  /** The rows it answers from, keyed by entity — mutable, so a test can change them mid-run. */
  rows: Record<string, Record<string, unknown>[]>;
}

/**
 * Builds a {@link SchemaClient} for tests, answering from plain arrays of rows.
 *
 * Pass the entities your **manifest** declares; an entity that is not there rejects, the way the host
 * answers 404 for one you never declared — so a typo in a test fails as a typo rather than as an empty
 * result.
 *
 * **`search` is a case-insensitive substring match, not Postgres full-text search.** The double cannot
 * reproduce stemming, `websearch_to_tsquery` operators or `ts_rank` ordering, and pretending otherwise
 * would let a test assert a ranking the host does not produce. Use it to prove your component *renders*
 * hits and handles none; prove the searching itself against the host.
 *
 * Comparisons are JavaScript's: fine for numbers, booleans and strings, and correct for `timestamp`
 * fields as long as the rows carry ISO-8601 strings, which sort lexicographically.
 *
 * @param rows entity name → its rows; each row should carry the platform-assigned `id`
 * @returns a schema double with `queries` recorded and mutable `rows`
 *
 * @example
 * ```ts
 * const schema = makeMockSchema({
 *   page: [{ id: 1, slug: 'kraken', title: 'The Kraken', markdown: 'a big squid' }],
 * });
 * const ctx = makeMockCtx({ schema });
 *
 * await mount(ctx);
 * expect(schema.queries[0]).toMatchObject({ method: 'search', entity: 'page' });
 * ```
 */
export function makeMockSchema(rows: Record<string, Record<string, unknown>[]> = {}): MockSchemaClient {
  const queries: SchemaQueryRecord[] = [];

  const entity = (name: string): Record<string, unknown>[] => {
    const found = client.rows[name];
    if (!found) {
      throw new Error(
        `Entity '${name}' is not declared by this plugin; declared: [${Object.keys(client.rows).join(', ')}]`,
      );
    }
    return found;
  };

  const matches = (row: Record<string, unknown>, predicate: SchemaPredicate): boolean => {
    const actual = row[predicate.field];
    const expected = predicate.value;
    switch (predicate.op) {
      case 'eq':
        return actual === expected;
      case 'ne':
        return actual !== expected;
      case 'lt':
        return (actual as never) < (expected as never);
      case 'lte':
        return (actual as never) <= (expected as never);
      case 'gt':
        return (actual as never) > (expected as never);
      case 'gte':
        return (actual as never) >= (expected as never);
      case 'like':
        // `%` is the host's wildcard; anchor the rest so `abc%` does not match `xabc`.
        return new RegExp(`^${String(expected).split('%').map(escapeRegExp).join('.*')}$`).test(
          String(actual),
        );
      case 'in':
        return Array.isArray(expected) && expected.includes(actual);
      case 'isNull':
        return actual == null;
      case 'isNotNull':
        return actual != null;
    }
  };

  const apply = <T>(name: string, query: SchemaQuery | undefined, extra?: (row: Record<string, unknown>) => boolean): SchemaPage<T> => {
    let result = entity(name).filter(
      (row) => (query?.where ?? []).every((p) => matches(row, p)) && (extra?.(row) ?? true),
    );
    for (const order of [...(query?.orderBy ?? [])].reverse()) {
      // Applied last-to-first so the first term ends up dominant, as a multi-column sort is read.
      result = [...result].sort((a, b) => {
        const [x, y] = [a[order.field] as never, b[order.field] as never];
        const sign = x < y ? -1 : x > y ? 1 : 0;
        return order.direction === 'desc' ? -sign : sign;
      });
    }
    const size = query?.size ?? 50;
    const page = query?.page ?? 0;
    return {
      items: result.slice(page * size, page * size + size) as T[],
      page,
      size,
      totalElements: result.length,
      totalPages: Math.ceil(result.length / size),
    };
  };

  const client: MockSchemaClient = {
    queries,
    rows,
    // Every method is `async` so an undeclared entity comes back as a *rejection*, the way the host's 404
    // reaches a component — a synchronous throw would be caught somewhere the real thing never is.
    async select(name, query) {
      queries.push({ method: 'select', entity: name, query });
      return apply(name, query);
    },
    async search(name, field, text, query) {
      queries.push({ method: 'search', entity: name, query, field, text });
      // An empty query matches nothing rather than everything — the one search rule the double does share
      // with the host, because a component that renders "everything" on an empty box is a real bug.
      const needle = text.toLowerCase();
      return apply(name, query, (row) => needle !== '' && String(row[field] ?? '').toLowerCase().includes(needle));
    },
    async find(name, id) {
      queries.push({ method: 'find', entity: name, id });
      return (entity(name).find((row) => row.id === id) ?? null) as never;
    },
    async count(name, query) {
      queries.push({ method: 'count', entity: name, query });
      return apply(name, query).totalElements;
    },
  };
  return client;
}

/** Escapes a string for literal use inside a `RegExp` — the non-wildcard parts of a `like` pattern. */
function escapeRegExp(literal: string): string {
  return literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Default theme tokens (neutral light values) used when a test does not override them. */
export const DEFAULT_THEME: ThemeTokens = {
  bg: '#ffffff',
  surface: '#f5f5f5',
  text: '#111111',
  textMuted: '#666666',
  accent: '#3b5bdb',
  accentContrast: '#ffffff',
  accent2: '#7048e8',
  border: '#dddddd',
};

/** One recorded {@link BlobClient.upload} call. @since 0.8.0 */
export interface UploadRecord {
  /** The filename the host would have been given — the override, else the `File`'s own name. */
  filename: string | null;
  /** The declared content type, as the browser reported it. */
  mime: string;
  /** The size in bytes. */
  size: number;
}

/** A {@link BlobClient} that stores in memory and records what was asked of it. @since 0.8.0 */
export type MockBlobClient = BlobClient & {
  /** Every {@link BlobClient.upload} call, in order. */
  uploads: UploadRecord[];
  /** Every ref passed to {@link BlobClient.remove}, in order — including ones that stored nothing. */
  removals: string[];
  /** What is currently stored, newest last. */
  stored: BlobInfo[];
};

/**
 * A {@link BlobClient} double: uploads land in memory, `urlFor` returns the host's URL shape, and every
 * call is recorded.
 *
 * **It refuses what the host refuses.** The per-file ceiling, the quota and the type allow-list are
 * enforced here, because a component that only ever meets an accepting double will meet its first refusal
 * in front of a podcaster — and a refusal is the case worth writing a test for, since the person who picked
 * the file is the only one who can pick a different one.
 *
 * What it does **not** do is read file formats: the host refuses a file whose bytes contradict its declared
 * type, and a second, diverging copy of that rule here would be worse than none. Drive that case with a
 * `File` named in `rejectContent`.
 *
 * @param opts limits and refusals; the defaults are small on purpose, so a test moving real quantities of
 *             data has to say so
 * @returns a recording, in-memory blob client
 *
 * @example
 * ```ts
 * const blobs = makeMockBlobs({ mimeTypes: ['image/png'] });
 * const ctx = makeMockCtx({ blobs });
 *
 * await mount(ctx);
 * expect(blobs.uploads[0]).toMatchObject({ filename: 'diagram.png', mime: 'image/png' });
 * ```
 */
export function makeMockBlobs(
  opts: {
    /** Accepted content types; anything else is refused as the host would refuse it. */
    mimeTypes?: string[];
    /** Per-file ceiling in bytes. */
    maxFileBytes?: number;
    /** Total ceiling in bytes. */
    quotaBytes?: number;
    /** Filenames to refuse as "content does not match", standing in for the host's sniffing. */
    rejectContent?: string[];
  } = {},
): MockBlobClient {
  const mimeTypes = opts.mimeTypes ?? ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];
  const maxFileBytes = opts.maxFileBytes ?? 1024 * 1024;
  const quotaBytes = opts.quotaBytes ?? 8 * 1024 * 1024;
  const rejected = new Set(opts.rejectContent ?? []);

  const uploads: UploadRecord[] = [];
  const removals: string[] = [];
  const stored: BlobInfo[] = [];
  let nextRef = 1;

  const usedBytes = () => stored.reduce((sum, blob) => sum + blob.size, 0);

  const client: MockBlobClient = {
    uploads,
    removals,
    stored,
    upload: (file, uploadOpts) => {
      const filename = uploadOpts?.filename ?? (file instanceof File ? file.name : null);
      uploads.push({ filename, mime: file.type, size: file.size });
      if (!mimeTypes.includes(file.type)) {
        return Promise.reject(new Error(`content type not allowed for this plugin: ${file.type}`));
      }
      if (filename != null && rejected.has(filename)) {
        return Promise.reject(new Error(`content does not match the declared type: ${file.type}`));
      }
      if (file.size > maxFileBytes) {
        return Promise.reject(
          new Error(`file is larger than this plugin may store: ${file.size} > ${maxFileBytes}`),
        );
      }
      if (usedBytes() + file.size > quotaBytes) {
        return Promise.reject(new Error(`storing this file would exceed the plugin's quota`));
      }
      const info: BlobInfo = {
        ref: `blob-${nextRef++}`,
        filename,
        mime: file.type,
        size: file.size,
        updatedAt: new Date().toISOString(),
      };
      stored.push(info);
      return Promise.resolve(info);
    },
    list: (listOpts) => {
      const page = listOpts?.page ?? 0;
      const size = listOpts?.size ?? 50;
      // Newest first, like the host — a component that renders the list in order shows the same thing here.
      const newestFirst = [...stored].reverse();
      const from = Math.min(page * size, newestFirst.length);
      const result: BlobPage = {
        items: newestFirst.slice(from, from + size),
        page,
        size,
        total: newestFirst.length,
      };
      return Promise.resolve(result);
    },
    remove: (ref) => {
      removals.push(ref);
      const at = stored.findIndex((blob) => blob.ref === ref);
      if (at >= 0) {
        stored.splice(at, 1);
      }
      // Idempotent, like the host: removing what is gone resolves rather than rejecting.
      return Promise.resolve();
    },
    urlFor: (ref) => `/api/plugins/test/blob/${ref}`,
    quota: () => {
      const quota: BlobQuota = { usedBytes: usedBytes(), quotaBytes, maxFileBytes };
      return Promise.resolve(quota);
    },
  };

  return client;
}

/** A {@link PluginContext} wired for tests: the mock `api` and the recorded `log` calls are reachable. */
export type MockPluginContext = PluginContext & {
  /** The recording client every `api` call goes through. */
  api: MockApiClient;
  /**
   * Every {@link PluginContext.log} call, in order.
   *
   * Stays empty if you override `log` yourself — the recording lives in the default implementation.
   */
  logs: LogRecord[];
  /**
   * Every `ctx.route.navigate(...)` call, in order.
   *
   * A `route` override is merged over the default, so overriding `path` alone keeps this recording; it
   * stays empty only if you replace `navigate` itself.
   */
  navigations: NavigationRecord[];
};

/** Overrides accepted by {@link makeMockCtx}. */
export interface MockCtxOverrides extends Partial<Omit<PluginContext, 'api' | 'route'>> {
  /**
   * Route override, **merged** over the default rather than replacing it.
   *
   * `path` is the only part most tests care about, and it is the part that has to be a fixed value — so
   * `route: { path: 'kraken' }` is enough, and what you leave out keeps working: `navigate` still records
   * into {@link MockPluginContext.navigations} and `onChange` still returns a working unsubscribe. Pass
   * the other members only when the test is about them.
   */
  route?: Partial<PluginRoute>;
  /** Canned responses for the mock `api`, keyed as described on {@link MockApiClient.responses}. */
  apiResponses?: Record<string, unknown>;
  /** A ready-made mock api; if omitted one is created from {@link apiResponses}. */
  api?: MockApiClient;
}

/**
 * Builds a {@link PluginContext} wired with in-memory doubles for unit tests.
 *
 * Defaults: `site` scope, no episodes, anonymous user, **all consent denied** (a {@link makeMockConsent}
 * double — pass your own to drive grants), empty filter, a player
 * parked at 0s, empty route, `en` locale, unknown progress, and {@link DEFAULT_THEME}. Pass overrides to
 * change any field; the returned `api` is a {@link MockApiClient} recording every call, and `logs`
 * collects every `ctx.log(...)`.
 *
 * `episodeLabels` is deliberately **absent** by default, and `schema` and `blobs` are **`null`**. All three
 * are optional on the real context — the host supplies labels partially, and gives a plugin no schema and
 * no blob store unless its manifest declares them — so a component that needs any of them has to survive
 * its absence. The mock makes you face that unless you pass one in ({@link makeMockSchema},
 * {@link makeMockBlobs}).
 *
 * **`route` is the one override that merges** rather than replacing: `route: { path: 'kraken' }` pins the
 * subpath and keeps the recording `navigate`. Everything else is all-or-nothing, because everything else
 * is a single behaviour rather than a value plus the handles around it.
 *
 * @param overrides partial context plus optional `apiResponses`
 * @returns a full context whose `api` is a {@link MockApiClient}, whose `log` calls are recorded in
 *          `logs`, and whose `route.navigate` calls are recorded in `navigations`
 */
export function makeMockCtx(overrides: MockCtxOverrides = {}): MockPluginContext {
  const { apiResponses, api, ...rest } = overrides;
  const mockApi = api ?? makeMockApi(apiResponses ?? {});
  const filter: FilterState = rest.filter?.current() ?? {};
  const logs: LogRecord[] = [];
  const navigations: NavigationRecord[] = [];

  const base: MockPluginContext = {
    scope: { type: 'site', id: 'main' },
    episodes: [],
    user: null,
    api: mockApi,
    // Null, not a client: a plugin only has one if its manifest declares `storage.schema`, and a
    // component written against a schema that is always there never handles the doc-store case.
    schema: null,
    // Null for the same reason, and it matters more here: `blobs` is the newer member, so a component
    // written against a context that always has it would break on every plugin that declares no `blobs`
    // block — which is most of them.
    blobs: null,
    logs,
    navigations,
    log: (level, message) => {
      logs.push({ level, message });
    },
    consent: makeMockConsent(),
    // The no-op handles still return a working unsubscribe, so a component that detaches on cleanup
    // behaves the same against the mock as against the host.
    filter: { current: () => filter, onChange: () => noop },
    player: { currentTime: () => 0, seekTo: () => {}, on: () => noop },
    route: {
      path: '',
      onChange: () => noop,
      // Recorded rather than applied: the double has no router and no URL, so `path` does not move.
      // Assert on `navigations`; drive a route change by rendering again with a different `path`.
      navigate: (subpath, opts) => {
        navigations.push({ subpath, replace: opts?.replace ?? false });
      },
    },
    // A real implementation, not a stub: these are pure string builders, so the double can simply be
    // right. It mirrors the host's URL shapes (`ShellController`), and core has a test pinning them
    // together — a mock that invented its own would let a component pass here and link nowhere in
    // production.
    links: {
      episode: (slug, linkOpts) => {
        const t = linkOpts?.t;
        const at = t != null && Number.isFinite(t) && t > 0 ? `?t=${Math.floor(t)}` : '';
        return `/episodes/${encodeURIComponent(slug)}${at}`;
      },
      feed: (slug, linkOpts) => {
        const params = new URLSearchParams();
        if (linkOpts?.season) params.set('season', linkOpts.season);
        if (linkOpts?.tag) params.set('tag', linkOpts.tag);
        // `newest` is the host's default and is left out, so one view has one URL.
        if (linkOpts?.order === 'oldest') params.set('order', 'oldest');
        const query = params.toString();
        return `/feeds/${encodeURIComponent(slug)}${query ? `?${query}` : ''}`;
      },
    },
    locale: { current: () => 'en', onChange: () => noop },
    progress: { get: () => Promise.resolve(null) },
    theme: DEFAULT_THEME,
  };

  // `route` merges instead of replacing, unlike every other member. Pinning a subpath is the common
  // override and `navigate` is the uncommon one, so a wholesale replacement made the usual case spell out
  // two stubs and silently lose the `navigations` recorder in the bargain.
  return { ...base, ...rest, route: { ...base.route, ...rest.route }, api: mockApi, logs, navigations };
}
