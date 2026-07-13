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
 * version. The host rejects a plugin whose manifest `platformApi` is incompatible with this value
 * (ARCHITECTURE §7.2). **These move together — a breaking change is a major bump.**
 */
export const PLATFORM_API_VERSION = '0.2.0' as const;

/** A user's role (ARCHITECTURE §8.5). Anonymous visitors have no role (`user` is `null`). */
export type Role = 'admin' | 'podcaster' | 'fan';

/** The level plus concrete entity a view is scoped to (ARCHITECTURE §6.1). */
export interface Scope {
  /** The scope level. */
  type: 'site' | 'feed' | 'season' | 'episode';
  /** The id of the entity at that level (e.g. the `EpisodeRef` id for `episode`). */
  id: string;
}

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
 * A thin authenticated REST client for the **host-provided** endpoints of this plugin's namespace.
 *
 * All paths are relative to the plugin's base (`/api/plugins/<id>/`); the host attaches the base path and
 * the user's auth (session or personal access token). Methods reject on non-2xx responses (RFC 7807
 * `application/problem+json` body, ARCHITECTURE §13).
 *
 * **These are not plugin-authored routes.** A v1 plugin cannot declare HTTP endpoints — its server side
 * is `register(ctx)` and nothing else (see the Java `PluginBackend`). What this client talks to is a
 * fixed, generic surface the host exposes over the plugin's hard-scoped doc store, mirroring `DocStore`
 * exactly — get / put / list, nothing more:
 *
 * ```text
 * GET /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}
 *       → one JSON doc; 404 if absent
 * GET /api/plugins/{id}/data/{scopeType}/{scopeId}?prefix=&page=&size=
 *       → { items: [{ key, value }], page, size, totalElements, totalPages }
 * PUT /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}   (JSON body)
 *       → upsert, last-write-wins
 * ```
 *
 * `scopeType` is `site | feed | season | episode` and `scopeId` the id of that entity — i.e. the
 * {@link Scope} the backend addresses with. For `site` the id is the literal `main` (one site, one
 * singleton scope), so the path always has four non-empty segments. `key` must match
 * `^[A-Za-z0-9._:-]{1,200}$` — the host answers 400 otherwise — and is the final path segment verbatim:
 * no `/`, so structure keys with `:` / `.` / `-` (e.g. `mark:userId:cell`). The list is paginated and
 * carries each doc's key, since you cannot address a doc without it.
 *
 * **No `DELETE` in v1:** `DocStore` has no `delete`, and this surface stays symmetric with it. Removal
 * comes with `DocStore.delete(scope, key)` in 0.3.0 and the `DELETE` endpoint alongside it — don't fake
 * it with tombstone values. (`delete`/`post` exist on this client as plain HTTP verbs; the v1 data
 * surface simply exposes no endpoint for them.)
 *
 * The doc a backend writes with `ctx.store().put(scope, key, value)` is the one read here at
 * `GET /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}`: one store, two ends.
 *
 * Data is hard-scoped to the plugin id — a plugin can only ever see its own. Reads are gated by the
 * slot's `visibleTo`, writes require the mapped {@link Role}, and the host validates that the scope
 * exists. A write is plain persistence: no plugin code runs at request time, so anything derived or
 * validated server-side must be precomputed in the backend's `register`/`onSchedule` and read back from
 * the store.
 *
 * Custom plugin-defined server routes may arrive in a later `platformApi` version; v1 plugins use the
 * doc store.
 *
 * @example Read, list and write docs from a Web Component
 * ```ts
 * const base = `data/${ctx.scope.type}/${ctx.scope.id}`;   // scope.id is `main` on the site scope
 * const key = `board:${ctx.user!.id}`;                     // no `/` in keys
 *
 * const board = await ctx.api.get<Board>(`${base}/${key}`);        // rejects with a 404 problem if absent
 * await ctx.api.put(`${base}/${key}`, { ...board, marked });       // upsert, last-write-wins
 *
 * // the SDK ships no type for the envelope — declare the shape you expect at the call site
 * type Paged<T> = { items: { key: string; value: T }[]; page: number; size: number;
 *                   totalElements: number; totalPages: number };
 * const page = await ctx.api.get<Paged<Board>>(`${base}?prefix=board:&page=0&size=50`);
 * page.items.forEach(({ key, value }) => render(key, value));
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
 * Everything a frontend plugin is given, set by the host on the mounted custom element
 * (ARCHITECTURE §7.5). This is the **entire** interface a plugin author must learn.
 */
export interface PluginContext {
  /** The scope this plugin instance is mounted in. */
  scope: Scope;
  /** The `EpisodeRef` ids in scope, resolved (and access-filtered) by the host. */
  episodes: string[];
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
  /** Cookie/consent gate for third-party resources (ARCHITECTURE §12.5). */
  consent: { has(cat: string): boolean; onChange(cb: () => void): void };
  /** Read-only access to the host's URL filter state (ARCHITECTURE §6.1). */
  filter: { current(): FilterState; onChange(cb: (f: FilterState) => void): void };
  /** Player position + control, for sync plugins (ARCHITECTURE §6.5). */
  player: { currentTime(): number; seekTo(s: number): void; on(ev: string, cb: (...args: unknown[]) => void): void };
  /** The subpath under `/p/<pluginId>/`, for deep-linkable plugin content (ARCHITECTURE §6.4). */
  route: { path: string; onChange(cb: (p: string) => void): void };
  /** The active UI locale (ARCHITECTURE §12.7). */
  locale: { current(): string; onChange(cb: (l: string) => void): void };
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
 * @param catalogs catalogs keyed by locale code
 * @param locale   the host locale handle, i.e. `ctx.locale`; its `onChange` drives re-selection
 * @returns a translator whose `t` and `locale` reflect the currently active locale
 */
export function createPluginI18n(
  catalogs: I18nCatalogs,
  locale: PluginContext['locale'],
): PluginI18n {
  let active = locale.current();
  locale.onChange((l) => {
    active = l;
  });

  return {
    get locale() {
      return active;
    },
    t(key, params) {
      const template =
        catalogs[active]?.[key] ?? catalogs[SOURCE_LOCALE]?.[key] ?? key;
      return interpolate(template, params);
    },
  };
}
