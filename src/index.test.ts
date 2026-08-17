// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

// @vitest-environment jsdom

import { describe, expect, it } from 'vitest';
import {
  createPluginI18n,
  defineMosaicastElement,
  PLATFORM_API_VERSION,
  resolveArtwork,
  SELF_SCOPE_ID,
  type DataScopeType,
  type DisplaySnapshot,
  type PluginContext,
  type PluginDataDeclaration,
  type PluginRoute,
  type SchemaPage,
  type SchemaPredicate,
  type SchemaQuery,
  type Scope,
} from './index.js';
import { makeMockCtx, makeMockSchema } from './testing.js';

describe('PLATFORM_API_VERSION', () => {
  it('is the mirrored SemVer anchor', () => {
    expect(PLATFORM_API_VERSION).toBe('0.7.1');
  });
});

describe('the data declaration', () => {
  it('types the manifest block that carries the access floors', () => {
    const data: PluginDataDeclaration = {
      readableBy: 'anonymous',
      writableBy: 'podcaster',
      backendOwned: ['stats', 'agg:*'],
    };
    expect(data.backendOwned).toContain('stats');

    // The floors are documentation-only, but the one rule the type does carry is this one.
    // @ts-expect-error a write floor of `anonymous` is rejected by the host at load.
    const bad: PluginDataDeclaration = { writableBy: 'anonymous' };
    expect(bad.writableBy).toBe('anonymous');
  });
});

describe('the user data scope', () => {
  it('is addressed by the sentinel the host resolves from the session', () => {
    expect(SELF_SCOPE_ID).toBe('me');
    expect(`data/user/${SELF_SCOPE_ID}/mark:s2e04`).toBe('data/user/me/mark:s2e04');
  });

  it('exists on the data surface but not as a slot scope', () => {
    const data: DataScopeType = 'user';
    expect(data).toBe('user');

    // @ts-expect-error a slot is never mounted on a user — there is no user page.
    const slot: Scope = { type: 'user', id: 'me' };
    expect(slot.id).toBe('me');
  });
});

describe('the schema client', () => {
  it('is nullable on the context, so the doc-store case has to be handled', () => {
    const ctx = makeMockCtx();

    // @ts-expect-error a doc-store plugin has no schema; the type says so before the runtime does.
    expect(() => ctx.schema.count('page')).toThrow();
    expect(ctx.schema).toBeNull();
  });

  it('describes a query rather than writing one', () => {
    const query: SchemaQuery = {
      where: [
        { field: 'published', op: 'eq', value: true },
        { field: 'updatedAt', op: 'gte', value: '2026-01-01T00:00:00Z' },
        { field: 'slug', op: 'in', value: ['kraken', 'lighthouse'] },
        { field: 'deletedAt', op: 'isNull' },
      ],
      orderBy: [{ field: 'updatedAt', direction: 'desc' }],
      size: 20,
    };
    expect(query.where).toHaveLength(4);

    // @ts-expect-error the operators are a closed vocabulary mirroring the Java `Criteria.Op`.
    const bad: SchemaPredicate = { field: 'title', op: 'contains', value: 'x' };
    expect(bad.op).toBe('contains');
  });

  it('pages in the same envelope as the doc surface', async () => {
    const schema = makeMockSchema({ page: [{ id: 1, slug: 'kraken' }] });
    const page: SchemaPage<{ slug: string }> = await schema.select('page');

    expect(page).toEqual({ items: [{ id: 1, slug: 'kraken' }], page: 0, size: 50, totalElements: 1, totalPages: 1 });
  });
});

describe('the route handle', () => {
  it('navigates by subpath, with an optional history replace', () => {
    const ctx = makeMockCtx();
    const route: PluginRoute = ctx.route;

    route.navigate('glossary/kraken');
    route.navigate('glossary/kraken', { replace: true });

    // @ts-expect-error a plugin names a subpath below its own prefix, never a whole URL target.
    route.navigate('/p/other-plugin/page', { push: true });

    expect(ctx.navigations).toHaveLength(3);
  });
});

describe('resolveArtwork', () => {
  const base: DisplaySnapshot = { title: 't', description: 'd' };

  it('prefers the episode cover, falls back to the feed cover, then undefined', () => {
    expect(resolveArtwork({ ...base, imageUrl: 'ep.jpg', feedImageUrl: 'feed.jpg' })).toBe('ep.jpg');
    expect(resolveArtwork({ ...base, feedImageUrl: 'feed.jpg' })).toBe('feed.jpg');
    expect(resolveArtwork(base)).toBeUndefined();
  });
});

describe('defineMosaicastElement', () => {
  it('mounts, renders into the shadow root and injects theme tokens as --mc-* vars', () => {
    const tag = 'mc-test-card';
    defineMosaicastElement({
      tag,
      render: ({ ctx, root }) => {
        root.innerHTML = `<p class="who">${ctx.scope.type}</p>`;
      },
    });

    const el = document.createElement(tag) as HTMLElement & { ctx: PluginContext };
    el.ctx = makeMockCtx({ scope: { type: 'episode', id: 'ep-1' } });
    document.body.appendChild(el);

    const shadow = el.shadowRoot!;
    expect(shadow).not.toBeNull();
    expect(shadow.querySelector('.who')?.textContent).toBe('episode');

    const style = shadow.querySelector('style')!.textContent ?? '';
    expect(style).toContain('--mc-bg: #ffffff');
    expect(style).toContain('--mc-text-muted: #666666');
    expect(style).toContain('--mc-accent: #3b5bdb');
  });

  it('runs cleanup on re-render and clears prior content', () => {
    const tag = 'mc-test-cleanup';
    let cleanups = 0;
    defineMosaicastElement({
      tag,
      render: ({ ctx, root }) => {
        root.textContent = ctx.scope.id;
        return () => {
          cleanups += 1;
        };
      },
    });

    const el = document.createElement(tag) as HTMLElement & { ctx: PluginContext };
    el.ctx = makeMockCtx({ scope: { type: 'feed', id: 'a' } });
    document.body.appendChild(el);
    const mount = () => el.shadowRoot!.querySelector('div')!.textContent;
    expect(mount()).toBe('a');

    el.ctx = makeMockCtx({ scope: { type: 'feed', id: 'b' } });
    expect(cleanups).toBe(1);
    expect(mount()).toBe('b');
  });

  it('is a no-op when the tag is already defined', () => {
    const tag = 'mc-test-twice';
    defineMosaicastElement({ tag, render: () => {} });
    expect(() => defineMosaicastElement({ tag, render: () => {} })).not.toThrow();
  });
});

describe('createPluginI18n', () => {
  const catalogs = {
    en: { greeting: 'Hello {{name}}', only_en: 'English' },
    de: { greeting: 'Hallo {{name}}' },
  };

  /** Models the host handle, unsubscribe included, so `dispose` has something real to detach from. */
  function localeHandle(initial: string) {
    let cb: ((l: string) => void) | undefined;
    return {
      handle: {
        current: () => initial,
        onChange: (fn: (l: string) => void) => {
          cb = fn;
          return () => {
            cb = undefined;
          };
        },
      },
      change: (l: string) => cb?.(l),
      subscribed: () => cb !== undefined,
    };
  }

  it('translates against the active locale and interpolates', () => {
    const { handle } = localeHandle('de');
    const i18n = createPluginI18n(catalogs, handle);
    expect(i18n.locale).toBe('de');
    expect(i18n.t('greeting', { name: 'Ada' })).toBe('Hallo Ada');
  });

  it('falls back to the source locale then the key', () => {
    const { handle } = localeHandle('de');
    const i18n = createPluginI18n(catalogs, handle);
    expect(i18n.t('only_en')).toBe('English'); // de missing → en
    expect(i18n.t('missing')).toBe('missing'); // absent everywhere → key
  });

  it('re-selects the catalog on locale change', () => {
    const { handle, change } = localeHandle('en');
    const i18n = createPluginI18n(catalogs, handle);
    expect(i18n.t('greeting', { name: 'X' })).toBe('Hello X');
    change('de');
    expect(i18n.locale).toBe('de');
    expect(i18n.t('greeting', { name: 'X' })).toBe('Hallo X');
  });

  it('detaches from the locale handle on dispose', () => {
    const { handle, change, subscribed } = localeHandle('en');
    const i18n = createPluginI18n(catalogs, handle);
    expect(subscribed()).toBe(true);

    i18n.dispose();

    expect(subscribed()).toBe(false);
    change('de');
    // A disposed translator stops tracking rather than following a locale it no longer belongs to.
    expect(i18n.locale).toBe('en');
  });
});
