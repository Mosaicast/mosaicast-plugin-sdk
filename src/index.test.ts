// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

// @vitest-environment jsdom

import { describe, expect, it } from 'vitest';
import {
  createPluginI18n,
  declaredTypeFor,
  defineManifest,
  defineMosaicastElement,
  DOC_KEY_PATTERN,
  iconCss,
  iconMask,
  isPluginApiError,
  matchRoute,
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
    expect(PLATFORM_API_VERSION).toBe('0.14.0');
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

describe('isPluginApiError', () => {
  it('recognises the shape the host produces, structurally', () => {
    const error = Object.assign(new Error('403'), { status: 403, problem: { detail: 'backendOwned' } });
    expect(isPluginApiError(error)).toBe(true);
    if (isPluginApiError(error)) {
      // The whole point: the two 403s the contract words differently are now distinguishable.
      expect(error.status).toBe(403);
      expect(error.problem?.detail).toBe('backendOwned');
    }
  });

  it('rejects everything that is not an Error carrying a numeric status', () => {
    expect(isPluginApiError(new Error('plain'))).toBe(false);
    expect(isPluginApiError({ status: 404 })).toBe(false); // not an Error — a bare object won't do
    expect(isPluginApiError(Object.assign(new Error('x'), { status: '404' }))).toBe(false);
    expect(isPluginApiError(null)).toBe(false);
    expect(isPluginApiError(undefined)).toBe(false);
  });
});

describe('declaredTypeFor', () => {
  const fileNamed = (name: string, type: string): File => new File(['x'], name, { type });

  it('keeps what the browser reported', () => {
    expect(declaredTypeFor(fileNamed('a.png', 'image/png'))).toBe('image/png');
    // Even when it disagrees with the extension: the host sniffs the bytes, so the claim is not ours to
    // second-guess — only to supply when the browser supplied none.
    expect(declaredTypeFor(fileNamed('a.png', 'image/jpeg'))).toBe('image/jpeg');
  });

  it('fills in from the extension when the browser gave nothing', () => {
    // The Firefox case: an OS MIME lookup that failed leaves `type` empty, and FormData then sends
    // application/octet-stream, which the host refuses on the declared type before it sniffs anything.
    expect(declaredTypeFor(fileNamed('diagram.png', ''))).toBe('image/png');
    expect(declaredTypeFor(fileNamed('photo.JPG', ''))).toBe('image/jpeg');
    expect(declaredTypeFor(fileNamed('scan.jfif', ''))).toBe('image/jpeg'); // Windows still produces these
    expect(declaredTypeFor(fileNamed('anim.webp', ''))).toBe('image/webp');
  });

  it('leaves an unmapped extension alone, so the refusal stays the host\'s', () => {
    expect(declaredTypeFor(fileNamed('notes.txt', ''))).toBe('');
    expect(declaredTypeFor(fileNamed('noextension', ''))).toBe('');
    // SVG is never storable, so guessing it would turn a clear refusal into a confusing one.
    expect(declaredTypeFor(fileNamed('logo.svg', ''))).toBe('');
  });

  it('handles a bare Blob, which carries no name at all', () => {
    expect(declaredTypeFor(new Blob(['x'], { type: 'image/png' }))).toBe('image/png');
    expect(declaredTypeFor(new Blob(['x']))).toBe('');
  });
});

describe('the icon helpers', () => {
  it('falls back to a real blank image, not to none', () => {
    const mask = iconMask('star');
    expect(mask).toContain('var(--mc-icon-star,');
    // The regression this exists for: `mask-image: none` leaves the element unmasked, painting
    // currentColor across its whole box — a solid square where the missing icon should have been.
    expect(mask).not.toContain('none');
    expect(mask).toContain('data:image/svg+xml');
    expect(mask).toContain('%3Csvg'); // URL-encoded, so the url() needs no quoting gymnastics
  });

  it('masks with currentColor rather than painting a background-image', () => {
    const css = iconCss(['star', 'chevron-left']);
    expect(css).toContain('background: currentColor');
    expect(css).not.toContain('background-image');
    expect(css).toContain('.mc-icon-star {');
    expect(css).toContain('.mc-icon-chevron-left {');
    // Both spellings, since the unprefixed property is not universal yet.
    expect(css).toContain('-webkit-mask-image:');
    expect(css).toContain(' mask-image:');
  });

  it('renames the base class on request', () => {
    const css = iconCss(['star'], { className: 'wiki-icon' });
    expect(css).toContain('.wiki-icon {');
    expect(css).toContain('.wiki-icon-star {');
  });

  it('refuses a name that could escape the var() it is spliced into', () => {
    expect(() => iconMask('star); color: red; --x:(')).toThrow(/icon name must match/);
    expect(() => iconMask('Star')).toThrow(); // custom property names are case-sensitive
    expect(() => iconCss(['star'], { className: 'a b' })).toThrow(/className must match/);
  });

  it('accepts an icon the SDK has never heard of', () => {
    // The zero-skew property: a plugin picks up an icon the day core publishes it, with no SDK release.
    expect(iconMask('kraken')).toContain('var(--mc-icon-kraken,');
  });
});

describe('matchRoute', () => {
  const patterns = ['', 'moments', 'highlight/:slug', 'highlight/:slug/edit'] as const;

  it('matches literal patterns and the plugin root', () => {
    expect(matchRoute('', patterns)?.pattern).toBe('');
    expect(matchRoute('moments', patterns)?.pattern).toBe('moments');
  });

  it('captures :params', () => {
    const match = matchRoute('highlight/kraken', patterns);
    expect(match?.pattern).toBe('highlight/:slug');
    expect(match?.params).toEqual({ slug: 'kraken' });
    expect(matchRoute('highlight/kraken/edit', patterns)?.pattern).toBe('highlight/:slug/edit');
  });

  it('requires the whole path, unlike the startsWith everyone hand-rolls', () => {
    expect(matchRoute('moments/3', patterns)).toBeNull();
    expect(matchRoute('moments-archive', patterns)).toBeNull();
    expect(matchRoute('highlight', patterns)).toBeNull(); // a :param needs a segment to capture
  });

  it('ignores surrounding slashes, the query string and the fragment', () => {
    expect(matchRoute('/moments/', patterns)?.pattern).toBe('moments');
    expect(matchRoute('moments?sort=new', patterns)?.pattern).toBe('moments');
    expect(matchRoute('moments#top', patterns)?.pattern).toBe('moments');
  });

  it('decodes a captured segment', () => {
    expect(matchRoute('highlight/the%20kraken', patterns)?.params.slug).toBe('the kraken');
  });

  it('returns null when nothing matches — the not-found branch', () => {
    expect(matchRoute('nowhere', patterns)).toBeNull();
  });
});

describe('defineManifest', () => {
  it('type-checks a manifest literal and hands it back unchanged', () => {
    const manifest = defineManifest({
      id: 'sample',
      version: '1.0.0',
      platformApi: PLATFORM_API_VERSION,
      name: 'Sample',
      frontend: { entry: 'sample.es.js', elements: ['sample-card'] },
      slots: [{ scope: 'episode', element: 'sample-card', placement: 'main', visibleTo: 'anonymous' }],
      nav: [{ path: '', label: 'Sample', icon: 'star' }],
      data: { writableBy: 'podcaster', readableBy: 'anonymous' },
      tags: { readsVocabulary: true, writesEpisodes: false },
      identity: { resolvesUsers: true },
      notifications: { sends: true, perUserPerDay: 5 },
      blobs: { maxFileBytes: 1024, quotaBytes: 4096, mimeTypes: ['image/png'] },
      external: { kinds: ['translation'], usedBy: 'podcaster' },
    });

    // Identity, not validation — the host owns the manifest and remains the validator.
    expect(manifest.id).toBe('sample');
    expect(manifest.slots?.[0]?.element).toBe(manifest.frontend?.elements[0]);
    expect(manifest.tags?.writesEpisodes).toBe(false);
    expect(manifest.identity?.resolvesUsers).toBe(true);
    expect(manifest.notifications?.perUserPerDay).toBe(5);
    expect(manifest.external?.kinds).toEqual(['translation']);
  });

  it('accepts an external block that leaves usedBy to the podcaster default', () => {
    const manifest = defineManifest({
      id: 'sample',
      version: '1.0.0',
      platformApi: PLATFORM_API_VERSION,
      name: 'Sample',
      external: { kinds: ['translation'] },
    });

    // Absent, not defaulted — the SDK fills nothing in. The host applies the `podcaster` floor.
    expect(manifest.external?.usedBy).toBeUndefined();
  });
});

describe('DOC_KEY_PATTERN', () => {
  it('mirrors the Java DocStore.KEY_PATTERN', () => {
    expect(DOC_KEY_PATTERN.test('mark:s2e04:b3')).toBe(true);
    expect(DOC_KEY_PATTERN.test('a.b-c_d')).toBe(true);
    // The one that bites: a key travels as the final path segment, so it cannot carry a slash.
    expect(DOC_KEY_PATTERN.test('marks/s2e04')).toBe(false);
    expect(DOC_KEY_PATTERN.test('')).toBe(false);
    expect(DOC_KEY_PATTERN.test('x'.repeat(201))).toBe(false);
  });
});

describe('the i18n formatting helpers', () => {
  const handle = (locale: string): PluginContext['locale'] => ({
    current: () => locale,
    onChange: () => () => {},
  });

  it('picks the plural form the locale actually needs', () => {
    const i18n = createPluginI18n(
      {
        en: { 'moments.one': '{{count}} moment', 'moments.other': '{{count}} moments' },
        pl: {
          'moments.one': '{{count}} moment',
          'moments.few': '{{count}} momenty',
          'moments.many': '{{count}} momentów',
          'moments.other': '{{count}} momentu',
        },
      },
      handle('pl'),
    );

    // The forms an English-shaped `count === 1` cannot produce, which is the whole reason this exists.
    expect(i18n.plural('moments', 1)).toBe('1 moment');
    expect(i18n.plural('moments', 3)).toBe('3 momenty');
    expect(i18n.plural('moments', 5)).toBe('5 momentów');
  });

  it('falls back to .other, then to the source locale, then to the key', () => {
    const i18n = createPluginI18n(
      { en: { 'moments.other': '{{count}} moments' }, de: {} },
      handle('de'),
    );
    expect(i18n.plural('moments', 1)).toBe('1 moments'); // de has neither form → en.other
    expect(i18n.plural('unknown', 2)).toBe('unknown');
  });

  it('formats numbers and dates for the active locale', () => {
    const de = createPluginI18n({}, handle('de'));
    expect(de.n(1234.5)).toBe('1.234,5');
    expect(de.date('2026-03-04T10:00:00Z')).toContain('2026');
    // A malformed value is shown, not thrown: one bad field must not take a render down.
    expect(de.date('not a date')).toBe('not a date');
  });

  it('formats a runtime from seconds or from an ISO-8601 duration', () => {
    const en = createPluginI18n({}, handle('en'));
    expect(en.duration(90)).toBe('1:30');
    expect(en.duration(3723)).toBe('1:02:03');
    expect(en.duration(0)).toBe('0:00');
    // DisplaySnapshot.duration arrives as an ISO-8601 string, so it is taken directly.
    expect(en.duration('PT1H2M3S')).toBe('1:02:03');
    expect(en.duration('PT90S')).toBe('1:30');
    expect(en.duration('nonsense')).toBe('');
    expect(en.duration(-5)).toBe('');
  });

  it('formats bytes in decimal units with the locale\'s own separator', () => {
    const en = createPluginI18n({}, handle('en'));
    const de = createPluginI18n({}, handle('de'));

    // Decimal, so the number agrees with what the visitor's file manager showed them.
    expect(en.bytes(5_242_880)).toMatch(/^5\.2 MB$/);
    expect(en.bytes(512)).toMatch(/^512 (byte|B)/);
    // The `de` bug in the sample plugin: a hardcoded `.` is simply wrong here.
    expect(de.bytes(5_242_880)).toContain('5,2');
    expect(de.bytes(5_242_880)).not.toContain('5.2');
  });
});
