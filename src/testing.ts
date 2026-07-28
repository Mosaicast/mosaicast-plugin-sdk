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
  ConsentApi,
  FilterState,
  LogLevel,
  PluginApiClient,
  PluginContext,
  ThemeTokens,
} from './index.js';

/** One recorded {@link PluginContext.log} call. */
export interface LogRecord {
  /** The severity it was logged at. */
  level: LogLevel;
  /** The logged message. */
  message: string;
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
  /** Withdraws a category and notifies subscribers, as the host's settings page can mid-session. */
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
 * @param initial categories granted from the start
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
  const granted = new Set(initial);
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
      if (granted.delete(category)) {
        notify();
      }
    },
    onChange(cb) {
      subscribers.add(cb);
      return () => subscribers.delete(cb);
    },
  };
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
};

/** Overrides accepted by {@link makeMockCtx}. */
export interface MockCtxOverrides extends Partial<Omit<PluginContext, 'api'>> {
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
 * `episodeLabels` is deliberately **absent** by default. It is optional on the real context and the host
 * may supply it partially, so a component that renders labels has to survive their absence — the mock
 * makes you face that unless you pass labels in.
 *
 * @param overrides partial context plus optional `apiResponses`
 * @returns a full context whose `api` is a {@link MockApiClient} and whose `log` calls are recorded
 */
export function makeMockCtx(overrides: MockCtxOverrides = {}): MockPluginContext {
  const { apiResponses, api, ...rest } = overrides;
  const mockApi = api ?? makeMockApi(apiResponses ?? {});
  const filter: FilterState = rest.filter?.current() ?? {};
  const logs: LogRecord[] = [];

  const base: MockPluginContext = {
    scope: { type: 'site', id: 'main' },
    episodes: [],
    user: null,
    api: mockApi,
    logs,
    log: (level, message) => {
      logs.push({ level, message });
    },
    consent: makeMockConsent(),
    // The no-op handles still return a working unsubscribe, so a component that detaches on cleanup
    // behaves the same against the mock as against the host.
    filter: { current: () => filter, onChange: () => noop },
    player: { currentTime: () => 0, seekTo: () => {}, on: () => noop },
    route: { path: '', onChange: () => noop },
    locale: { current: () => 'en', onChange: () => noop },
    progress: { get: () => Promise.resolve(null) },
    theme: DEFAULT_THEME,
  };

  return { ...base, ...rest, api: mockApi, logs };
}
