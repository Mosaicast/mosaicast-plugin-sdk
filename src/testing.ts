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
  FilterState,
  PluginApiClient,
  PluginContext,
  ThemeTokens,
} from './index.js';

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
 * Defaults: `site` scope, no episodes, anonymous user, all consent denied, empty filter, a player
 * parked at 0s, empty route, `en` locale, unknown progress, and {@link DEFAULT_THEME}. Pass overrides to
 * change any field; the returned `api` is a {@link MockApiClient} recording every call.
 *
 * @param overrides partial context plus optional `apiResponses`
 * @returns a full context whose `api` is a {@link MockApiClient}
 */
export function makeMockCtx(overrides: MockCtxOverrides = {}): PluginContext & { api: MockApiClient } {
  const { apiResponses, api, ...rest } = overrides;
  const mockApi = api ?? makeMockApi(apiResponses ?? {});
  const filter: FilterState = rest.filter?.current() ?? {};

  const base: PluginContext & { api: MockApiClient } = {
    scope: { type: 'site', id: 'main' },
    episodes: [],
    user: null,
    api: mockApi,
    consent: { has: () => false, onChange: () => {} },
    filter: { current: () => filter, onChange: () => {} },
    player: { currentTime: () => 0, seekTo: () => {}, on: () => {} },
    route: { path: '', onChange: () => {} },
    locale: { current: () => 'en', onChange: () => {} },
    progress: { get: () => Promise.resolve(null) },
    theme: DEFAULT_THEME,
  };

  return { ...base, ...rest, api: mockApi };
}
