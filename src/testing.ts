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
  DisplaySnapshot,
  DocClient,
  DocTarget,
  FeedsClient,
  FilterState,
  LogLevel,
  PagedDocs,
  PluginApiClient,
  PluginApiError,
  PluginContext,
  PluginRoute,
  NotifyClient,
  NotifyMessage,
  ProblemDetail,
  Role,
  SchemaClient,
  SchemaPage,
  SchemaPredicate,
  SchemaQuery,
  TagInfo,
  TagsClient,
  ThemeTokens,
  TranslationClient,
  TranslationRequest,
  TranslationResult,
  UserDirectory,
  UserRef,
} from './index.js';
import { DISPLAY_BATCH_LIMIT, DOC_KEY_PATTERN, declaredTypeFor } from './index.js';

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

/**
 * A canned failure: what the host would have answered instead of a body.
 *
 * Put one in {@link MockApiClient.responses} and the call rejects with a {@link PluginApiError} carrying
 * that status — which is how a component's 404 branch and its 500 branch become separately testable.
 *
 * ```ts
 * const ctx = makeMockCtx({ apiResponses: { 'get data/site/main/stats': apiError(403, {
 *   detail: 'this key is written by the plugin backend',
 * }) } });
 * ```
 *
 * @param status  the HTTP status to fail with
 * @param problem the RFC 7807 body, if the test cares about it
 * @returns a marker the mock client turns into a rejection
 * @since 0.9.0
 */
export function apiError(status: number, problem?: ProblemDetail): CannedApiError {
  return { __mosaicastApiError: true, status, problem };
}

/** The marker {@link apiError} produces. Recognised by {@link MockApiClient}; not a real error. */
export interface CannedApiError {
  /** Discriminator, so an ordinary response object is never mistaken for a failure. */
  __mosaicastApiError: true;
  /** The status to reject with. */
  status: number;
  /** The problem body to attach. */
  problem?: ProblemDetail;
}

function isCannedApiError(value: unknown): value is CannedApiError {
  return typeof value === 'object' && value !== null && '__mosaicastApiError' in value;
}

/** Builds the error shape the host produces, so `isPluginApiError` and `e.status` behave as in production. */
function toApiError(canned: CannedApiError, method: string, path: string): PluginApiError {
  const error = new Error(
    `${canned.status} on ${method.toUpperCase()} ${path}${canned.problem?.detail ? `: ${canned.problem.detail}` : ''}`,
  ) as Error & { status: number; problem?: ProblemDetail };
  error.status = canned.status;
  error.problem = canned.problem;
  return error;
}

/** A {@link PluginApiClient} that records calls and returns canned responses. */
export interface MockApiClient extends PluginApiClient {
  /** Every call made through this client, in order. */
  readonly calls: RecordedCall[];
  /**
   * Canned responses keyed by `"<method> <path>"` (e.g. `"get /board"`) or bare `path`; the more
   * specific key wins. Missing keys resolve to `undefined`.
   *
   * A value built by {@link apiError} **rejects** with a {@link PluginApiError} instead of resolving.
   */
  responses: Record<string, unknown>;
  /**
   * Resolves once every call made so far has settled.
   *
   * A component typically does `ctx.api.get(...).then(setState)`, sometimes with another `.then` behind
   * it, so the mock's already-resolved promise still needs one microtask hop per `.then` before the DOM
   * reflects it. A single `await Promise.resolve()` covers one hop and not two — which is why the
   * symptom is **an assertion that fails only sometimes**, depending on how many hops the component
   * happens to have. This waits for the calls themselves rather than for a guessed number of hops.
   *
   * Prefer {@link flushMockApi}, which also drains the hops the component adds after the call settles.
   *
   * @since 0.9.0
   */
  settled(): Promise<void>;
}

/**
 * Waits until every call recorded by a mock client has settled *and* the `.then` chains behind them have
 * run.
 *
 * The two-part wait is the point: `await client.settled()` knows when the *requests* finished, and the
 * microtask drain after it lets the component's own `.then(setState)` hops reach the DOM. Use it instead
 * of counting `await Promise.resolve()` calls by hand.
 *
 * ```ts
 * mount(ctx);
 * await flushMockApi(ctx.api);
 * expect(root.querySelector('.title')).toHaveTextContent('The Kraken');
 * ```
 *
 * @param client the mock client — `ctx.api` from {@link makeMockCtx}
 * @since 0.9.0
 */
export async function flushMockApi(client: MockApiClient): Promise<void> {
  await client.settled();
  // Two hops, because a component that chains `.then(parse).then(setState)` needs one per link and the
  // cost of an extra hop is nothing. Anything deeper than this is a component doing its own async work,
  // which is the component's to await.
  await Promise.resolve();
  await Promise.resolve();
}

/** The unsubscribe returned by the inert `onChange` doubles — there is nothing to detach. */
const noop = (): void => {};

function makeMockApi(responses: Record<string, unknown>): MockApiClient {
  const calls: RecordedCall[] = [];
  const inFlight: Promise<unknown>[] = [];
  const resolve = (method: RecordedCall['method'], path: string, body?: unknown): Promise<never> => {
    calls.push({ method, path, body });
    const canned = api.responses[`${method} ${path}`] ?? api.responses[path];
    const result = isCannedApiError(canned)
      ? Promise.reject(toApiError(canned, method, path))
      : Promise.resolve(canned as never);
    // Tracked separately, and with its rejection absorbed, so `settled()` can await every call without
    // becoming an unhandled-rejection source of its own.
    inFlight.push(result.catch(() => undefined));
    return result as Promise<never>;
  };
  const api: MockApiClient = {
    calls,
    responses,
    get: (path) => resolve('get', path),
    getOrNull: async (path) => {
      try {
        return await resolve('get', path);
      } catch (e) {
        // Exactly the host's rule: 404 is an answer, everything else is a failure.
        if (e instanceof Error && (e as PluginApiError).status === 404) {
          return null;
        }
        throw e;
      }
    },
    post: (path, body) => resolve('post', path, body),
    put: (path, body) => resolve('put', path, body),
    delete: (path) => resolve('delete', path),
    settled: async () => {
      // Re-read the list each round: awaiting one call can start another.
      let pending = inFlight.length;
      do {
        pending = inFlight.length;
        await Promise.all(inFlight.slice());
      } while (inFlight.length !== pending);
    },
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

/** A {@link FeedsClient} answering from registered snapshots. @since 0.9.0 */
export interface MockFeedsClient extends FeedsClient {
  /**
   * Registers the snapshot `display`/`displayMany` return for a slug.
   *
   * Mirrors the Java kit's `FakeFeedAccess.withDisplay(...)`, so both halves of a plugin describe the
   * host's answer the same way.
   *
   * @param slug     the episode's public slug
   * @param snapshot what the host would hand over
   * @returns this client, for chaining
   */
  withDisplay(slug: string, snapshot: DisplaySnapshot): MockFeedsClient;
  /** Every slug asked for, in order — batched calls contribute each slug separately. */
  readonly requested: string[];
}

/**
 * A {@link FeedsClient} double: snapshots you register, and **nothing for the ones you don't**.
 *
 * An unregistered slug resolves `null` (or is absent from a batch) rather than throwing, because that is
 * what the host does for an episode this visitor may not see — a `WITHDRAWN` or tier-gated one is absent,
 * not redacted. So the empty case a component must handle is the double's default, and a component that
 * assumes every slug comes back fails here rather than in front of a visitor.
 *
 * ```ts
 * const feeds = makeMockFeeds().withDisplay('kraken', { title: 'The Kraken', description: '' });
 * const ctx = makeMockCtx({ feeds, episodes: ['kraken', 'gated'] });
 *
 * await mount(ctx);
 * expect(root.textContent).toContain('The Kraken');   // and renders nothing for `gated`
 * ```
 *
 * @param snapshots snapshots to start with, keyed by slug
 * @returns a feeds double with `withDisplay` and a recorded `requested` list
 * @since 0.9.0
 */
export function makeMockFeeds(snapshots: Record<string, DisplaySnapshot> = {}): MockFeedsClient {
  const stored: Record<string, DisplaySnapshot> = { ...snapshots };
  const requested: string[] = [];

  const client: MockFeedsClient = {
    requested,
    withDisplay(slug, snapshot) {
      stored[slug] = snapshot;
      return client;
    },
    display: (slug) => {
      requested.push(slug);
      return Promise.resolve(stored[slug] ?? null);
    },
    displayMany: (slugs) => {
      // Clamped, not rejected — the host's behaviour past the batch ceiling.
      const asked = slugs.slice(0, DISPLAY_BATCH_LIMIT);
      requested.push(...asked);
      const result: Record<string, DisplaySnapshot> = {};
      for (const slug of asked) {
        const snapshot = stored[slug];
        // Absent rather than null: a slug the caller may not see is simply not a key in the answer.
        if (snapshot) result[slug] = snapshot;
      }
      return Promise.resolve(result);
    },
  };
  return client;
}

/** A {@link TagsClient} backed by an in-memory vocabulary. @since 0.9.0 */
export interface MockTagsClient extends TagsClient {
  /** Seeds a tag the **feed** put on an episode — a row the plugin may read but must not remove. */
  withFeedTag(episodeSlug: string, tag: string): MockTagsClient;
  /** The canonical keys currently on each episode, for asserting without going through the client. */
  readonly episodeTags: Record<string, string[]>;
  /** The canonical keys currently on each of this plugin's subjects. */
  readonly subjectTags: Record<string, string[]>;
}

/** The host's canonicalisation rule: trim, collapse internal whitespace, casefold. */
function canonicalTag(tag: string): string {
  const canonical = tag.trim().replace(/\s+/g, ' ').toLowerCase();
  if (canonical === '') {
    throw new Error('tag must not be blank');
  }
  return canonical;
}

/**
 * A {@link TagsClient} double that **refuses what the host refuses**.
 *
 * Two refusals matter, and both are invisible to a component tested against a permissive double:
 *
 * - **Episode writes throw** unless you pass `writesEpisodes: true`, standing in for the manifest
 *   declaration. Off by default, so the branch where the host says no gets exercised.
 * - **`untagEpisode` removes only this plugin's own assignment.** Seed the feed's side with
 *   {@link MockTagsClient.withFeedTag} and the tag survives your removal, exactly as the host's `source`
 *   column makes it survive.
 *
 * Canonicalisation is applied as the host applies it, so a test that writes `'Maritime '` and reads
 * `'maritime'` passes here for the same reason it passes against core.
 *
 * @param opts `writesEpisodes` grants the episode-write capability; `subjects` and `episodes` seed
 *             assignments this plugin owns
 * @returns a tags double
 * @since 0.9.0
 */
export function makeMockTags(
  opts: {
    writesEpisodes?: boolean;
    subjects?: Record<string, string[]>;
    episodes?: Record<string, string[]>;
  } = {},
): MockTagsClient {
  const labels = new Map<string, string>();
  // `plugin` vs `feed`: which rows this plugin is allowed to take back.
  const episodeRows: { slug: string; tag: string; source: 'feed' | 'plugin' }[] = [];
  const subjects = new Map<string, Set<string>>();

  const remember = (canonical: string, spelling: string): void => {
    if (!labels.has(canonical)) labels.set(canonical, spelling.trim().replace(/\s+/g, ' '));
  };
  const addEpisodeRow = (slug: string, tag: string, source: 'feed' | 'plugin'): void => {
    const canonical = canonicalTag(tag);
    remember(canonical, tag);
    if (!episodeRows.some((r) => r.slug === slug && r.tag === canonical && r.source === source)) {
      episodeRows.push({ slug, tag: canonical, source });
    }
  };
  const addSubject = (subjectKey: string, tag: string): void => {
    if (subjectKey === '') throw new Error('subjectKey must not be blank');
    const canonical = canonicalTag(tag);
    remember(canonical, tag);
    const existing = subjects.get(subjectKey) ?? new Set<string>();
    existing.add(canonical);
    subjects.set(subjectKey, existing);
  };
  const requireEpisodeWrites = (): void => {
    if (!opts.writesEpisodes) {
      throw new Error(
        "this plugin's manifest does not declare tags.writesEpisodes; " +
          'pass { writesEpisodes: true } to makeMockTags to test the granted case',
      );
    }
  };
  const infoFor = (canonical: string): TagInfo => ({
    tag: canonical,
    label: labels.get(canonical) ?? canonical,
    episodes: new Set(episodeRows.filter((r) => r.tag === canonical).map((r) => r.slug)).size,
    subjects: [...subjects.values()].filter((tags) => tags.has(canonical)).length,
  });
  const tagsOnEpisode = (slug: string): string[] =>
    [...new Set(episodeRows.filter((r) => r.slug === slug).map((r) => r.tag))].sort();

  for (const [slug, tags] of Object.entries(opts.episodes ?? {})) {
    for (const tag of tags) addEpisodeRow(slug, tag, 'plugin');
  }
  for (const [subjectKey, tags] of Object.entries(opts.subjects ?? {})) {
    for (const tag of tags) addSubject(subjectKey, tag);
  }

  const client: MockTagsClient = {
    get episodeTags() {
      const byEpisode: Record<string, string[]> = {};
      for (const row of episodeRows) byEpisode[row.slug] = tagsOnEpisode(row.slug);
      return byEpisode;
    },
    get subjectTags() {
      const bySubject: Record<string, string[]> = {};
      for (const [key, tags] of subjects) bySubject[key] = [...tags].sort();
      return bySubject;
    },
    withFeedTag(episodeSlug, tag) {
      addEpisodeRow(episodeSlug, tag, 'feed');
      return client;
    },
    all: async () =>
      [...labels.keys()]
        .map(infoFor)
        // A tag stops existing when nothing carries it — the host keeps no empty vocabulary rows.
        .filter((info) => info.episodes > 0 || info.subjects > 0)
        .sort((a, b) => b.episodes + b.subjects - (a.episodes + a.subjects) || a.tag.localeCompare(b.tag)),
    episodesWith: async (tag) => {
      const canonical = canonicalTag(tag);
      return [...new Set(episodeRows.filter((r) => r.tag === canonical).map((r) => r.slug))].sort();
    },
    tagsOn: async (episodeSlug) => tagsOnEpisode(episodeSlug),
    similarTo: async (tag, limit = 10) => {
      const canonical = canonicalTag(tag);
      const shared = new Map<string, number>();
      const bump = (other: string): void => {
        if (other !== canonical) shared.set(other, (shared.get(other) ?? 0) + 1);
      };
      for (const slug of new Set(episodeRows.filter((r) => r.tag === canonical).map((r) => r.slug))) {
        tagsOnEpisode(slug).forEach(bump);
      }
      for (const tags of subjects.values()) {
        if (tags.has(canonical)) tags.forEach(bump);
      }
      return [...shared.entries()]
        .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
        .slice(0, Math.max(0, limit))
        .map(([other]) => infoFor(other));
    },
    subjectsWith: async (tag) => {
      const canonical = canonicalTag(tag);
      return [...subjects.entries()]
        .filter(([, tags]) => tags.has(canonical))
        .map(([key]) => key)
        .sort();
    },
    tagsOnSubject: async (subjectKey) => [...(subjects.get(subjectKey) ?? [])].sort(),
    tagSubject: async (subjectKey, tag) => {
      addSubject(subjectKey, tag);
    },
    untagSubject: async (subjectKey, tag) => {
      const tags = subjects.get(subjectKey);
      if (!tags) return;
      tags.delete(canonicalTag(tag));
      if (tags.size === 0) subjects.delete(subjectKey);
    },
    tagEpisode: async (episodeSlug, tag) => {
      requireEpisodeWrites();
      addEpisodeRow(episodeSlug, tag, 'plugin');
    },
    untagEpisode: async (episodeSlug, tag) => {
      requireEpisodeWrites();
      const canonical = canonicalTag(tag);
      // Only this plugin's row. A tag the feed also put here stays, and the episode keeps carrying it.
      const at = episodeRows.findIndex(
        (r) => r.slug === episodeSlug && r.tag === canonical && r.source === 'plugin',
      );
      if (at >= 0) episodeRows.splice(at, 1);
    },
  };
  return client;
}

/** A {@link UserDirectory} double over a seeded set of people. @since 0.13.0 */
export interface MockUserDirectory extends UserDirectory {
  /** Every id passed to {@link UserDirectory.resolve}, in call order and including duplicates. */
  readonly resolved: string[];
  /**
   * Forgets a user, standing in for an account erased or pseudonymised after the plugin stored its id.
   *
   * The interesting case: the row survives, its author does not.
   */
  forget(id: string): void;
}

/**
 * A {@link UserDirectory} double that **models absence the way the host models it**.
 *
 * An id with no seeded entry is simply *missing* from the resolved array — not `undefined` in it, not a
 * placeholder. That is the one behaviour a permissive double would hide, and hiding it means a
 * leaderboard passes its tests and then renders `undefined` the first time somebody deletes their
 * account. Seed the people you know about, resolve a stranger too, and the branch that has to survive an
 * erased author gets exercised.
 *
 * `avatarUrl` is built the way the host builds it (`/api/users/{id}/avatar`), so an assertion on a
 * rendered `src` pins the string production actually produces. Every seeded user has one — there is no
 * unset case.
 *
 * Passing it stands in for a plugin whose manifest declares `identity`. Leaving `ctx.users` at its
 * `null` default is the other test, and the one more plugins get wrong.
 *
 * ```ts
 * const users = makeMockUsers({ 'u-1': 'Ana', 'u-2': { displayName: 'Bo', role: 'podcaster' } });
 * const ctx = makeMockCtx({ users });
 *
 * const found = await users.resolve(['u-1', 'u-gone']);   // length 1 — not index-aligned with the ask
 * ```
 *
 * @param seed the people this directory knows: a display name, or `{ displayName, role }` when the role
 *             matters. Keys are the user ids
 * @returns a recording user-directory double
 * @since 0.13.0
 */
export function makeMockUsers(
  seed: Record<string, string | { displayName: string; role?: Role }> = {},
): MockUserDirectory {
  const known = new Map<string, UserRef>();
  const resolved: string[] = [];

  for (const [id, entry] of Object.entries(seed)) {
    const spec = typeof entry === 'string' ? { displayName: entry } : entry;
    known.set(id, {
      id,
      displayName: spec.displayName,
      // Derived, not accepted: the host always answers this path, so letting a test invent one would let
      // it assert a shape production never produces.
      avatarUrl: `/api/users/${encodeURIComponent(id)}/avatar`,
      // `fan` by default — the role a listener on a leaderboard actually has.
      role: spec.role ?? 'fan',
    });
  }

  return {
    resolved,
    forget: (id) => {
      known.delete(id);
    },
    resolve: (ids) => {
      resolved.push(...ids);
      const found: UserRef[] = [];
      // Deduplicated, like the host: a board naming the same author twice resolves them once.
      for (const id of new Set(ids)) {
        const ref = known.get(id);
        // Absent, not redacted: an unknown id contributes nothing rather than an entry to skip.
        if (ref) found.push(ref);
      }
      return Promise.resolve(found);
    },
  };
}

/** One notification a {@link MockNotifyClient} delivered. @since 0.14.0 */
export interface DeliveryRecord {
  /** Who received it. */
  userId: string;
  /** What they were told. */
  msg: NotifyMessage;
}

/** A {@link NotifyClient} double over a seeded set of notifiable users. @since 0.14.0 */
export interface MockNotifyClient extends NotifyClient {
  /** Every delivery, in send order — the assertion surface. */
  readonly delivered: DeliveryRecord[];
  /** What one recipient was told, in order. */
  messagesFor(userId: string): NotifyMessage[];
}

/**
 * A {@link NotifyClient} double that **models the partial send**.
 *
 * The host only delivers to users the plugin already holds `user`-scope data for, so a recipient outside
 * `notifiable` is left out of the resolved array rather than failing the call. That is the behaviour a
 * permissive double would hide, and hiding it means a plugin working from a stale participant list
 * notifies nobody and looks exactly like one working perfectly.
 *
 * The send cap is off unless you pass `perUserPerDay`, so the branch where the host refuses gets
 * exercised on purpose rather than never — it rejects with the same 429 the host answers, which a
 * scheduled sender is supposed to hold its batch on.
 *
 * ```ts
 * const notify = makeMockNotify({ notifiable: ['u-1'] });
 * const ctx = makeMockCtx({ notify });
 *
 * const told = await notify.send(['u-1', 'u-stranger'], { key: 'bingo.resolved' });
 * // told === ['u-1'] — the stranger has no rows, so the host would not have reached them either
 * ```
 *
 * @param opts `notifiable` lists the users this plugin holds data for (nobody by default, which is the
 *             case a component has to survive); `perUserPerDay` arms the cap
 * @returns a recording notify double
 * @since 0.14.0
 */
export function makeMockNotify(
  opts: { notifiable?: string[]; perUserPerDay?: number } = {},
): MockNotifyClient {
  const eligible = new Set(opts.notifiable ?? []);
  const delivered: DeliveryRecord[] = [];
  const sentPerUser = new Map<string, number>();
  const cap = opts.perUserPerDay;

  return {
    delivered,
    messagesFor: (userId) => delivered.filter((d) => d.userId === userId).map((d) => d.msg),
    send: (userIds, msg) => {
      if (msg.key.trim() === '') {
        // The host refuses it; a double that accepted it would let a component ship an empty inbox row.
        return Promise.reject(toApiError(apiError(400, { detail: 'key must not be blank' }), 'post', 'notify'));
      }
      // Deduplicated, like the host: naming the same participant twice sends one notification, and must
      // not count twice against their cap either.
      const recipients = [...new Set(userIds)];
      if (cap != null) {
        for (const id of recipients) {
          // Only an eligible recipient consumes a send — checking before filtering would refuse a call
          // the host would have let through.
          if (eligible.has(id) && (sentPerUser.get(id) ?? 0) >= cap) {
            return Promise.reject(
              toApiError(apiError(429, { detail: `over the cap of ${cap} for ${id}` }), 'post', 'notify'),
            );
          }
        }
      }
      const told: string[] = [];
      for (const id of recipients) {
        if (!eligible.has(id)) continue;
        delivered.push({ userId: id, msg });
        sentPerUser.set(id, (sentPerUser.get(id) ?? 0) + 1);
        told.push(id);
      }
      return Promise.resolve(told);
    },
  };
}

/** A {@link DocClient} storing in memory, keyed by resolved partition path. @since 0.9.0 */
export interface MockDocClient extends DocClient {
  /** What is currently stored, keyed `"<partition>/<key>"` — e.g. `"data/user/me/marks"`. */
  readonly stored: Record<string, unknown>;
}

/** Resolves a {@link DocTarget} to the partition path the host would address. */
function docPath(target: DocTarget): string {
  if (target === 'self') return 'data/user/me';
  if (target === 'site') return 'data/site/main';
  return `data/${target.type}/${encodeURIComponent(target.id)}`;
}

function requireDocKey(key: string): string {
  if (!DOC_KEY_PATTERN.test(key)) {
    throw new Error(`doc key must match ${DOC_KEY_PATTERN.source}, got: ${JSON.stringify(key)}`);
  }
  return key;
}

/**
 * A {@link DocClient} double: an in-memory store that **validates keys the way the client does**.
 *
 * A key with a `/` in it, or one over 200 characters, throws here rather than silently working — which
 * matters, because against a permissive double the failure would first appear as a 400 in production.
 *
 * ```ts
 * const docs = makeMockDocs({ 'data/user/me/marks': { b3: true } });
 * const ctx = makeMockCtx({ docs });
 *
 * await mount(ctx);
 * expect(docs.stored['data/user/me/marks']).toEqual({ b3: true, b4: true });
 * ```
 *
 * @param initial documents to start with, keyed `"<partition>/<key>"`
 * @returns a doc client double with an inspectable `stored` map
 * @since 0.9.0
 */
export function makeMockDocs(initial: Record<string, unknown> = {}): MockDocClient {
  const stored: Record<string, unknown> = { ...initial };

  return {
    stored,
    get: async (target, key) => (stored[`${docPath(target)}/${requireDocKey(key)}`] ?? null) as never,
    put: async (target, key, value) => {
      stored[`${docPath(target)}/${requireDocKey(key)}`] = value;
    },
    list: async (target, opts) => {
      const prefix = `${docPath(target)}/`;
      const items = Object.entries(stored)
        .filter(([path]) => path.startsWith(prefix))
        .map(([path, value]) => ({ key: path.slice(prefix.length), value }))
        .filter((entry) => entry.key.startsWith(opts?.prefix ?? ''))
        .sort((a, b) => a.key.localeCompare(b.key));
      const size = opts?.size ?? 50;
      const page = opts?.page ?? 0;
      const result: PagedDocs<never> = {
        items: items.slice(page * size, page * size + size) as never,
        page,
        size,
        totalElements: items.length,
        totalPages: Math.ceil(items.length / size),
      };
      return result;
    },
    remove: async (target, key) => {
      // Idempotent, like the host: removing what is gone resolves rather than rejecting.
      delete stored[`${docPath(target)}/${requireDocKey(key)}`];
    },
  };
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
      // The same normalisation the real client applies, so a `File` with an empty `type` — what Firefox
      // hands over when the OS MIME lookup fails — is accepted here exactly as the host accepts it.
      const declared =
        uploadOpts?.declaredType === 'preserve' ? file.type : declaredTypeFor(file);
      uploads.push({ filename, mime: declared, size: file.size });
      if (!mimeTypes.includes(declared)) {
        return Promise.reject(new Error(`content type not allowed for this plugin: ${declared}`));
      }
      if (filename != null && rejected.has(filename)) {
        return Promise.reject(new Error(`content does not match the declared type: ${declared}`));
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
        mime: declared,
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

/** A {@link TranslationClient} that records what it was asked to translate. */
export interface MockTranslationClient extends TranslationClient {
  /** Every {@link TranslationClient.translate} call, in order. */
  readonly requests: TranslationRequest[];
}

/**
 * A translation double.
 *
 * The default `translate` marks the text rather than pretending to translate it — `"[nl] Hello"` — so an
 * assertion says *that the component asked for Dutch*, which is the thing worth pinning. A double that
 * returned plausible Dutch would let a test pass while the component sent the wrong target language.
 *
 * Passing it stands in for a plugin that declared `external.kinds: ['translation']` running on a site whose
 * admin selected a provider — both gates open. Leaving `ctx.translation` at its `null` default is the other
 * test, and the one more plugins get wrong.
 *
 * ```ts
 * const ctx = makeMockCtx({ translation: makeMockTranslation() });
 * // …and for the failure paths a component must handle:
 * const angry = makeMockTranslation({ fail: apiError(429, { detail: 'slow down' }) });
 * const refused = makeMockTranslation({ fail: apiError(403, { detail: 'below external.usedBy' }) });
 * ```
 *
 * @param opts `translate` replaces the marker, `providerId` and `fromCache` fill the result, and `fail`
 *             (from {@link apiError}) makes every call reject instead
 * @returns a recording translation double
 * @since 0.10.0
 */
export function makeMockTranslation(
  opts: {
    translate?: (request: TranslationRequest) => string;
    providerId?: string;
    fromCache?: boolean;
    fail?: CannedApiError;
  } = {},
): MockTranslationClient {
  const requests: TranslationRequest[] = [];
  const mark = opts.translate ?? ((request: TranslationRequest) => `[${request.to}] ${request.text}`);
  return {
    requests,
    available: () => true,
    translate: (request) => {
      requests.push(request);
      if (opts.fail) {
        return Promise.reject(toApiError(opts.fail, 'post', 'translate'));
      }
      const result: TranslationResult = {
        text: mark(request),
        // Null unless a test says otherwise: a provider that does not report a detected language is the
        // ordinary case, and a component reading this has to survive it.
        detectedSourceLanguage: request.from && request.from !== 'auto' ? request.from : null,
        providerId: opts.providerId ?? 'mock',
        fromCache: opts.fromCache ?? false,
      };
      return Promise.resolve(result);
    },
  };
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
 * parked at 0s, empty route with an empty query and hash, `en` locale, unknown progress, and
 * {@link DEFAULT_THEME}. Pass overrides to change any field; the returned `api` is a
 * {@link MockApiClient} recording every call, and `logs` collects every `ctx.log(...)`.
 *
 * `docs` and `feeds` are real doubles ({@link makeMockDocs}, {@link makeMockFeeds}), because every plugin
 * has both — the empty ones give a component nothing to render, which is the case worth defaulting to.
 *
 * `episodeLabels` is deliberately **absent** by default, and `schema`, `blobs`, `tags`, `users` and
 * `notify` are **`null`**. All six are optional on the real context — the host supplies labels partially,
 * and gives a plugin no schema, no blob store, no tag surface, no user directory and no notifier unless
 * its manifest declares them — so a component that needs any of them has to survive its absence. The mock
 * makes you face that unless you pass one in ({@link makeMockSchema}, {@link makeMockBlobs},
 * {@link makeMockTags}, {@link makeMockUsers}, {@link makeMockNotify}).
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
    // Real doubles rather than null: every plugin has a doc store and every plugin can read snapshots,
    // so there is no "declared it or not" case for a component to handle here.
    docs: makeMockDocs(),
    feeds: makeMockFeeds(),
    // Null, like schema and blobs: `ctx.tags` exists only for a plugin whose manifest declares a `tags`
    // block, and a component written against a context that always has one breaks on every plugin that
    // does not.
    tags: null,
    // Null, for the same reason and with a sharper edge: a plugin only resolves people if its manifest
    // declares `identity`, and the default keeps an existing test exercising the undeclared case. Pass
    // {@link makeMockUsers} when the test is about rendering people.
    users: null,
    // Null, and the sharpest edge of the five: `ctx.notify` writes into somebody else's site, so a
    // component written against a notifier that is always there both breaks on every plugin that
    // declares no `notifications` block and hides the one surface an operator is most likely to withhold.
    notify: null,
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
      // Parsed like the host's, and empty by default — a component reading a filter off the query has to
      // survive arriving without one, which is how a visitor first reaches the page.
      query: new URLSearchParams(),
      hash: '',
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
    // English only, and English is the default: the smallest site there is. A component that lists
    // languages has to survive there being exactly one, which is what a fresh install looks like.
    locale: {
      current: () => 'en',
      onChange: () => noop,
      available: () => [{ code: 'en', nativeName: 'English', isDefault: true }],
      content: () => [{ code: 'en', nativeName: 'English', isDefault: true }],
    },
    // Null, like `tags`/`schema`/`blobs`, and since 0.11.0 for either of two reasons the contract keeps
    // indistinguishable: a manifest that never declared `external.kinds: ['translation']`, or an operator
    // who configured no provider — which is every site by default. A component written against a
    // translator that is always there breaks on both. Pass {@link makeMockTranslation} when the test is
    // about translating; leaving it null is the test that you handled its absence.
    translation: null,
    progress: { get: () => Promise.resolve(null) },
    theme: DEFAULT_THEME,
  };

  // `route` merges instead of replacing, unlike every other member. Pinning a subpath is the common
  // override and `navigate` is the uncommon one, so a wholesale replacement made the usual case spell out
  // two stubs and silently lose the `navigations` recorder in the bargain.
  return { ...base, ...rest, route: { ...base.route, ...rest.route }, api: mockApi, logs, navigations };
}
