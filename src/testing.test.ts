// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_THEME, makeMockBlobs, makeMockConsent, makeMockCtx, makeMockSchema } from './testing.js';

describe('makeMockCtx', () => {
  it('produces a full context with sensible defaults', () => {
    const ctx = makeMockCtx();
    expect(ctx.scope).toEqual({ type: 'site', id: 'main' });
    expect(ctx.episodes).toEqual([]);
    expect(ctx.user).toBeNull();
    expect(ctx.consent.has('analytics')).toBe(false);
    expect(ctx.filter.current()).toEqual({});
    expect(ctx.player.currentTime()).toBe(0);
    expect(ctx.locale.current()).toBe('en');
    expect(ctx.theme).toBe(DEFAULT_THEME);
  });

  it('applies overrides', () => {
    const ctx = makeMockCtx({
      scope: { type: 'episode', id: 'ep-9' },
      user: { id: 'u1', role: 'podcaster' },
    });
    expect(ctx.scope.id).toBe('ep-9');
    expect(ctx.user?.role).toBe('podcaster');
  });

  it('records api calls and returns canned responses', async () => {
    const ctx = makeMockCtx({ apiResponses: { 'get /board': { cells: 3 }, '/save': { ok: true } } });

    const board = await ctx.api.get<{ cells: number }>('/board');
    await ctx.api.post('/save', { mark: 4 });

    expect(board).toEqual({ cells: 3 });
    expect(ctx.api.calls).toEqual([
      { method: 'get', path: '/board', body: undefined },
      { method: 'post', path: '/save', body: { mark: 4 } },
    ]);
  });

  it('resolves unknown paths to undefined', async () => {
    const ctx = makeMockCtx();
    await expect(ctx.api.get('/nope')).resolves.toBeUndefined();
  });

  it('records log calls in order', () => {
    const ctx = makeMockCtx();

    ctx.log('info', 'mounted');
    ctx.log('warn', 'no board for ep-9');

    expect(ctx.logs).toEqual([
      { level: 'info', message: 'mounted' },
      { level: 'warn', message: 'no board for ep-9' },
    ]);
  });

  it('denies consent by default, including request()', async () => {
    const ctx = makeMockCtx();

    expect(ctx.consent.has('analytics')).toBe(false);
    expect(ctx.consent.granted()).toEqual(['necessary']);
    // The visitor says no unless the test says otherwise — the placeholder path stays exercised.
    await expect(ctx.consent.request('analytics')).resolves.toBe(false);
    expect(ctx.consent.has('analytics')).toBe(false);
  });

  it('grants necessary without being asked', () => {
    const ctx = makeMockCtx();

    // The category the core itself uses: always on, never prompted for, not withdrawable.
    expect(ctx.consent.has('necessary')).toBe(true);
  });

  it('returns a working unsubscribe from every onChange', () => {
    const ctx = makeMockCtx();
    const off = [
      ctx.consent.onChange(() => {}),
      ctx.filter.onChange(() => {}),
      ctx.route.onChange(() => {}),
      ctx.locale.onChange(() => {}),
      ctx.player.on('timeupdate', () => {}),
    ];

    // Not just typed as callable — actually callable, and idempotent.
    off.forEach((unsubscribe) => {
      expect(typeof unsubscribe).toBe('function');
      expect(() => {
        unsubscribe();
        unsubscribe();
      }).not.toThrow();
    });
  });

  it('leaves episodeLabels absent unless supplied', () => {
    expect(makeMockCtx().episodeLabels).toBeUndefined();

    const ctx = makeMockCtx({
      episodes: ['the-sample-cast-s01e06'],
      episodeLabels: { 'the-sample-cast-s01e06': 'S01E06 · The Lighthouse' },
    });
    expect(ctx.episodeLabels?.['the-sample-cast-s01e06']).toBe('S01E06 · The Lighthouse');
  });

  it('leaves schema null unless supplied, so the doc-store case is exercised', () => {
    expect(makeMockCtx().schema).toBeNull();

    const schema = makeMockSchema({ page: [] });
    expect(makeMockCtx({ schema }).schema).toBe(schema);
  });

  it('leaves blobs null unless supplied, so the no-file-storage case is exercised', () => {
    expect(makeMockCtx().blobs).toBeNull();

    const blobs = makeMockBlobs();
    expect(makeMockCtx({ blobs }).blobs).toBe(blobs);
  });

  it('merges a route override over the default instead of replacing it', () => {
    // Pinning a subpath is the common case and `navigate` the uncommon one, so overriding `path` alone
    // must not cost two stubs — nor silently lose the recorder, which is what a wholesale replace did.
    const ctx = makeMockCtx({ route: { path: 'kraken' } });

    expect(ctx.route.path).toBe('kraken');
    ctx.route.navigate('glossary/squid');
    expect(ctx.navigations).toEqual([{ subpath: 'glossary/squid', replace: false }]);
    expect(typeof ctx.route.onChange(() => {})).toBe('function');
  });

  it('still lets a test replace navigate itself', () => {
    const seen: string[] = [];
    const ctx = makeMockCtx({ route: { path: 'kraken', navigate: (subpath) => seen.push(subpath) } });

    ctx.route.navigate('elsewhere');

    expect(seen).toEqual(['elsewhere']);
    // Replaced, so the default recorder never ran — the one case where `navigations` stays empty.
    expect(ctx.navigations).toEqual([]);
  });

  it('records navigate calls instead of moving the route', () => {
    const ctx = makeMockCtx({ route: { path: 'start', onChange: () => () => {}, navigate: () => {} } });
    expect(ctx.navigations).toEqual([]);

    const recording = makeMockCtx();
    recording.route.navigate('glossary/kraken');
    recording.route.navigate('index', { replace: true });

    expect(recording.navigations).toEqual([
      { subpath: 'glossary/kraken', replace: false },
      { subpath: 'index', replace: true },
    ]);
    // The double has no router: the path a component reads does not move under it.
    expect(recording.route.path).toBe('');
  });
});

describe('makeMockConsent', () => {
  it('starts denied unless seeded', () => {
    expect(makeMockConsent().has('analytics')).toBe(false);

    const seeded = makeMockConsent(['functional']);
    expect(seeded.has('functional')).toBe(true);
    expect(seeded.granted()).toEqual(['necessary', 'functional']);
  });

  it('treats necessary as always granted and never withdrawable', () => {
    const consent = makeMockConsent();
    const cb = vi.fn();
    consent.onChange(cb);

    expect(consent.has('necessary')).toBe(true);
    consent.revoke('necessary');

    // The host cannot withdraw it, so revoking is a no-op and fires nothing.
    expect(consent.has('necessary')).toBe(true);
    expect(cb).not.toHaveBeenCalled();
  });

  it('shares a grant across everything gated on the same category', () => {
    const consent = makeMockConsent();

    // Services describe, categories decide: one grant covers every service under it, in any plugin.
    consent.grant('analytics');

    expect(consent.has('analytics')).toBe(true);
    expect(consent.granted()).toContain('analytics');
  });

  it('records requests and resolves with what the visitor decides', async () => {
    const consent = makeMockConsent();

    await expect(consent.request('analytics')).resolves.toBe(false);

    consent.autoGrantOnRequest = true;
    await expect(consent.request('analytics')).resolves.toBe(true);

    expect(consent.requests).toEqual(['analytics', 'analytics']);
    expect(consent.has('analytics')).toBe(true);
  });

  it('notifies subscribers on grant, revoke and an accepted request', async () => {
    const consent = makeMockConsent();
    const cb = vi.fn();
    consent.onChange(cb);

    consent.grant('analytics');
    consent.grant('analytics');   // already granted — nothing changed, so nothing fires
    consent.revoke('analytics');
    consent.autoGrantOnRequest = true;
    await consent.request('analytics');

    expect(cb).toHaveBeenCalledTimes(3);
  });

  it('stops delivering once unsubscribed', () => {
    const consent = makeMockConsent();
    const cb = vi.fn();

    const off = consent.onChange(cb);
    consent.grant('analytics');
    off();
    consent.revoke('analytics');

    expect(cb).toHaveBeenCalledTimes(1);
  });
});

describe('makeMockSchema', () => {
  const rows = () => ({
    page: [
      { id: 1, slug: 'kraken', title: 'The Kraken', markdown: 'a very big squid', views: 30, published: true },
      { id: 2, slug: 'lighthouse', title: 'The Lighthouse', markdown: 'a tall lamp', views: 10, published: true },
      { id: 3, slug: 'draft', title: 'Draft', markdown: 'squid notes', views: 0, published: false },
    ],
    revision: [],
  });

  it('filters, orders and pages like the host does', async () => {
    const schema = makeMockSchema(rows());

    const published = await schema.select('page', {
      where: [{ field: 'published', op: 'eq', value: true }],
      orderBy: [{ field: 'views', direction: 'desc' }],
      size: 1,
    });

    expect(published.items).toEqual([expect.objectContaining({ slug: 'kraken' })]);
    expect(published).toMatchObject({ page: 0, size: 1, totalElements: 2, totalPages: 2 });
  });

  it('supports every operator', async () => {
    const schema = makeMockSchema(rows());
    const slugs = async (where: Parameters<typeof schema.select>[1]) =>
      (await schema.select<{ slug: string }>('page', where)).items.map((p) => p.slug);

    expect(await slugs({ where: [{ field: 'views', op: 'gte', value: 30 }] })).toEqual(['kraken']);
    expect(await slugs({ where: [{ field: 'slug', op: 'ne', value: 'draft' }] })).toEqual(['kraken', 'lighthouse']);
    expect(await slugs({ where: [{ field: 'slug', op: 'in', value: ['draft', 'kraken'] }] })).toEqual(['kraken', 'draft']);
    expect(await slugs({ where: [{ field: 'slug', op: 'like', value: 'light%' }] })).toEqual(['lighthouse']);
    expect(await slugs({ where: [{ field: 'title', op: 'isNull' }] })).toEqual([]);
    expect(await slugs({ where: [{ field: 'title', op: 'isNotNull' }] })).toHaveLength(3);
  });

  it('anchors a like pattern rather than matching anywhere', async () => {
    const schema = makeMockSchema(rows());
    const hits = await schema.select<{ slug: string }>('page', {
      where: [{ field: 'slug', op: 'like', value: 'house' }],
    });
    expect(hits.items).toEqual([]);
  });

  it('matches nothing on an empty search, and substrings otherwise', async () => {
    const schema = makeMockSchema(rows());

    expect((await schema.search('page', 'markdown', '')).items).toEqual([]);
    expect((await schema.search<{ slug: string }>('page', 'markdown', 'SQUID')).items.map((p) => p.slug))
      .toEqual(['kraken', 'draft']);
  });

  it('applies extra criteria on top of a search', async () => {
    const schema = makeMockSchema(rows());
    const hits = await schema.search<{ slug: string }>('page', 'markdown', 'squid', {
      where: [{ field: 'published', op: 'eq', value: true }],
    });
    expect(hits.items.map((p) => p.slug)).toEqual(['kraken']);
  });

  it('finds one row by id and answers null for a missing one', async () => {
    const schema = makeMockSchema(rows());
    expect(await schema.find<{ slug: string }>('page', 2)).toMatchObject({ slug: 'lighthouse' });
    expect(await schema.find('page', 99)).toBeNull();
  });

  it('counts without paging', async () => {
    const schema = makeMockSchema(rows());
    expect(await schema.count('page')).toBe(3);
    expect(await schema.count('page', { where: [{ field: 'published', op: 'eq', value: false }] })).toBe(1);
  });

  it('rejects an entity the plugin never declared, as the host 404s it', async () => {
    const schema = makeMockSchema(rows());
    await expect(schema.select('pages')).rejects.toThrow(/not declared/);
    await expect(schema.find('pages', 1)).rejects.toThrow(/page, revision/);
  });

  it('records every query in order', async () => {
    const schema = makeMockSchema(rows());
    await schema.select('page', { size: 5 });
    await schema.search('page', 'markdown', 'squid');
    await schema.find('page', 1);
    await schema.count('revision');

    expect(schema.queries).toEqual([
      { method: 'select', entity: 'page', query: { size: 5 } },
      { method: 'search', entity: 'page', query: undefined, field: 'markdown', text: 'squid' },
      { method: 'find', entity: 'page', id: 1 },
      { method: 'count', entity: 'revision', query: undefined },
    ]);
  });

  it('answers from rows a test swaps in mid-run', async () => {
    const schema = makeMockSchema(rows());
    schema.rows.revision = [{ id: 7, pageSlug: 'kraken' }];
    expect(await schema.count('revision')).toBe(1);
  });
});

describe('makeMockBlobs', () => {
  const png = (name: string, size = 16) =>
    new File([new Uint8Array(size)], name, { type: 'image/png' });

  it('stores, lists newest-first and removes', async () => {
    const blobs = makeMockBlobs();

    const first = await blobs.upload(png('a.png'));
    const second = await blobs.upload(png('b.png'));

    expect(first.ref).not.toBe(second.ref);
    expect(first.mime).toBe('image/png');
    expect((await blobs.list()).items.map((b) => b.filename)).toEqual(['b.png', 'a.png']);

    await blobs.remove(first.ref);
    expect((await blobs.list()).items.map((b) => b.filename)).toEqual(['b.png']);
    // Idempotent, like the host — cleanup code needs no existence check.
    await expect(blobs.remove(first.ref)).resolves.toBeUndefined();
    expect(blobs.removals).toEqual([first.ref, first.ref]);
  });

  it('records what was asked of it, including the calls it refuses', async () => {
    const blobs = makeMockBlobs({ mimeTypes: ['image/png'] });

    await expect(blobs.upload(new File(['%PDF'], 'notes.pdf', { type: 'application/pdf' }))).rejects.toThrow(
      /not allowed/,
    );

    // Recorded before the refusal: a test asserting "the component tried" needs the attempt, not just
    // the outcome.
    expect(blobs.uploads).toEqual([{ filename: 'notes.pdf', mime: 'application/pdf', size: 4 }]);
    expect(blobs.stored).toHaveLength(0);
  });

  it('refuses what the host refuses: per-file ceiling, then quota', async () => {
    const blobs = makeMockBlobs({ maxFileBytes: 32, quotaBytes: 48 });

    await expect(blobs.upload(png('big.png', 64))).rejects.toThrow(/larger than/);
    await blobs.upload(png('a.png', 32));
    // Each file fits; the collection does not. That is the failure a plugin actually meets.
    await expect(blobs.upload(png('b.png', 32))).rejects.toThrow(/quota/);
    expect(await blobs.quota()).toEqual({ usedBytes: 32, quotaBytes: 48, maxFileBytes: 32 });
  });

  it('stands in for the host content check on a named file', async () => {
    const blobs = makeMockBlobs({ rejectContent: ['actually-a-script.png'] });

    await expect(blobs.upload(png('actually-a-script.png'))).rejects.toThrow(/does not match/);
  });

  it('takes a filename override, and needs one for a bare Blob', async () => {
    const blobs = makeMockBlobs();

    const named = await blobs.upload(png('a.png'), { filename: 'renamed.png' });
    expect(named.filename).toBe('renamed.png');

    const bare = await blobs.upload(new Blob([new Uint8Array(4)], { type: 'image/png' }));
    expect(bare.filename).toBeNull();
  });

  it('derives a URL from a ref rather than handing one back on upload', async () => {
    const blobs = makeMockBlobs();
    const stored = await blobs.upload(png('a.png'));

    expect(blobs.urlFor(stored.ref)).toBe(`/api/plugins/test/blob/${stored.ref}`);
  });
});

describe('ctx.links', () => {
  it('builds episode links, with and without a timestamp', () => {
    const { links } = makeMockCtx();

    expect(links.episode('kraken')).toBe('/episodes/kraken');
    expect(links.episode('kraken', { t: 724 })).toBe('/episodes/kraken?t=724');
    // A position of zero is the start, which is what a bare link already means.
    expect(links.episode('kraken', { t: 0 })).toBe('/episodes/kraken');
    expect(links.episode('kraken', { t: -5 })).toBe('/episodes/kraken');
    expect(links.episode('kraken', { t: 12.7 })).toBe('/episodes/kraken?t=12');
  });

  it('builds feed links and omits the default order', () => {
    const { links } = makeMockCtx();

    expect(links.feed('main')).toBe('/feeds/main');
    expect(links.feed('main', { season: '2', tag: 'christmas' })).toBe('/feeds/main?season=2&tag=christmas');
    // `newest` is the host's default: carrying it would make one view canonicalize two ways.
    expect(links.feed('main', { order: 'newest' })).toBe('/feeds/main');
    expect(links.feed('main', { order: 'oldest' })).toBe('/feeds/main?order=oldest');
  });

  it('escapes a slug rather than trusting it', () => {
    const { links } = makeMockCtx();
    expect(links.episode('a b/c')).toBe('/episodes/a%20b%2Fc');
  });
});
