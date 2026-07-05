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
export const PLATFORM_API_VERSION = '0.1.0' as const;

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
 * A thin authenticated REST client for the plugin's own backend.
 *
 * All paths are relative to the plugin's base (`/api/plugins/<id>/`); the host attaches the auth token
 * and base path. Methods reject on non-2xx responses (RFC 7807 `application/problem+json` body,
 * ARCHITECTURE §13).
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
  /** Authenticated client for the plugin's own backend. */
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
