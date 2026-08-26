// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it, vi } from 'vitest';
import {
  apiError,
  DEFAULT_THEME,
  flushMockApi,
  makeMockBlobs,
  makeMockConsent,
  makeMockCtx,
  makeMockDocs,
  makeMockFeeds,
  makeMockSchema,
  makeMockTags,
  makeMockTranslation,
} from './testing.js';
import { isPluginApiError } from './index.js';

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

describe('makeMockFeeds', () => {
  const kraken = { title: 'The Kraken', description: 'a big squid', duration: 'PT42M' };

  it('answers from registered snapshots and null for the rest', async () => {
    const feeds = makeMockFeeds().withDisplay('kraken', kraken);

    expect(await feeds.display('kraken')).toEqual(kraken);
    // Not an error: the host answers the same way for an episode this visitor may not see.
    expect(await feeds.display('gated')).toBeNull();
    expect(feeds.requested).toEqual(['kraken', 'gated']);
  });

  it('omits unknown slugs from a batch rather than returning null entries', async () => {
    const feeds = makeMockFeeds({ kraken });
    const many = await feeds.displayMany(['kraken', 'gated']);

    expect(Object.keys(many)).toEqual(['kraken']);
    expect('gated' in many).toBe(false);
  });

  it('clamps an oversized batch instead of rejecting it', async () => {
    const feeds = makeMockFeeds();
    const slugs = Array.from({ length: 250 }, (_, i) => `ep-${i}`);

    await expect(feeds.displayMany(slugs)).resolves.toEqual({});
    expect(feeds.requested).toHaveLength(200); // DISPLAY_BATCH_LIMIT — clamped, not an error
  });

  it('is wired into makeMockCtx by default, empty', async () => {
    const ctx = makeMockCtx();
    expect(await ctx.feeds.display('anything')).toBeNull();
  });
});

describe('makeMockTags', () => {
  it('canonicalises the way the host does', async () => {
    const tags = makeMockTags();
    await tags.tagSubject('page:kraken', '  Maritime   Lore ');

    expect(await tags.tagsOnSubject('page:kraken')).toEqual(['maritime lore']);
    // Any spelling reaches the same tag, which is the whole point of a shared vocabulary.
    expect(await tags.subjectsWith('MARITIME LORE')).toEqual(['page:kraken']);
  });

  it('keeps the display label from first use', async () => {
    const tags = makeMockTags();
    await tags.tagSubject('page:a', 'Maritime');
    await tags.tagSubject('page:b', 'maritime');

    const [entry] = await tags.all();
    expect(entry).toMatchObject({ tag: 'maritime', label: 'Maritime', subjects: 2 });
  });

  it('refuses episode writes unless the manifest declared them', async () => {
    const tags = makeMockTags();
    await expect(tags.tagEpisode('kraken', 'lore')).rejects.toThrow(/writesEpisodes/);

    const allowed = makeMockTags({ writesEpisodes: true });
    await expect(allowed.tagEpisode('kraken', 'lore')).resolves.toBeUndefined();
    expect(allowed.episodeTags.kraken).toEqual(['lore']);
  });

  it('cannot remove another writer\'s assignment', async () => {
    const tags = makeMockTags({ writesEpisodes: true }).withFeedTag('kraken', 'lore');
    await tags.tagEpisode('kraken', 'lore');

    await tags.untagEpisode('kraken', 'lore');

    // The plugin's own row is gone; the feed's stays, so the episode still carries the tag.
    expect(await tags.tagsOn('kraken')).toEqual(['lore']);
    expect(await tags.episodesWith('lore')).toEqual(['kraken']);
  });

  it('drops a tag from the vocabulary once nothing carries it', async () => {
    const tags = makeMockTags();
    await tags.tagSubject('page:a', 'lore');
    expect(await tags.all()).toHaveLength(1);

    await tags.untagSubject('page:a', 'lore');

    // A plugin never deletes a tag; it stops existing because nothing carries it any more.
    expect(await tags.all()).toEqual([]);
  });

  it('counts episodes site-wide but subjects only for this plugin', async () => {
    const tags = makeMockTags({ writesEpisodes: true })
      .withFeedTag('kraken', 'lore')
      .withFeedTag('lighthouse', 'lore');
    await tags.tagSubject('page:kraken', 'lore');

    expect(await tags.all()).toEqual([
      { tag: 'lore', label: 'lore', episodes: 2, subjects: 1 },
    ]);
  });

  it('answers similarTo from co-occurrence', async () => {
    const tags = makeMockTags()
      .withFeedTag('kraken', 'lore')
      .withFeedTag('kraken', 'maritime')
      .withFeedTag('lighthouse', 'lore')
      .withFeedTag('lighthouse', 'maritime')
      .withFeedTag('lighthouse', 'ghosts');

    const similar = await tags.similarTo('lore', 5);

    expect(similar.map((t) => t.tag)).toEqual(['maritime', 'ghosts']);
    expect(similar.map((t) => t.tag)).not.toContain('lore'); // never itself
  });

  it('is null on the context unless a test supplies one', () => {
    expect(makeMockCtx().tags).toBeNull();
    expect(makeMockCtx({ tags: makeMockTags() }).tags).not.toBeNull();
  });
});

describe('makeMockDocs', () => {
  it('resolves the singleton partitions the way the host does', async () => {
    const docs = makeMockDocs();

    await docs.put('self', 'marks', { b3: true });
    await docs.put('site', 'logo', { ref: 'blob-1' });
    await docs.put({ type: 'episode', id: 'kraken' }, 'board', { cells: 25 });

    expect(docs.stored).toEqual({
      'data/user/me/marks': { b3: true },
      'data/site/main/logo': { ref: 'blob-1' },
      'data/episode/kraken/board': { cells: 25 },
    });
  });

  it('resolves null for an absent document rather than rejecting', async () => {
    expect(await makeMockDocs().get('self', 'nothing')).toBeNull();
  });

  it('rejects a malformed key at the call site, with the pattern in the message', async () => {
    const docs = makeMockDocs();
    // A slash is the one that bites: a key travels as the final path segment.
    await expect(docs.get('self', 'marks/s2e04')).rejects.toThrow(/doc key must match/);
    await expect(docs.put('self', '', {})).rejects.toThrow(/doc key must match/);
  });

  it('lists a partition by prefix, paged', async () => {
    const docs = makeMockDocs();
    await docs.put('self', 'mark:a', 1);
    await docs.put('self', 'mark:b', 2);
    await docs.put('self', 'other', 3);
    await docs.put('site', 'mark:c', 4); // a different partition — must not leak in

    const page = await docs.list('self', { prefix: 'mark:' });

    expect(page.items).toEqual([{ key: 'mark:a', value: 1 }, { key: 'mark:b', value: 2 }]);
    expect(page.totalElements).toBe(2);
  });

  it('removes idempotently', async () => {
    const docs = makeMockDocs({ 'data/user/me/marks': { b3: true } });
    await docs.remove('self', 'marks');
    await expect(docs.remove('self', 'marks')).resolves.toBeUndefined();
    expect(docs.stored['data/user/me/marks']).toBeUndefined();
  });
});

describe('the mock api client', () => {
  it('rejects with the host error shape for a canned status', async () => {
    const ctx = makeMockCtx({
      apiResponses: {
        'get data/site/main/stats': apiError(403, { detail: 'written by the plugin backend' }),
      },
    });

    await expect(ctx.api.get('data/site/main/stats')).rejects.toThrow(/403/);
    await ctx.api.get('data/site/main/stats').catch((e: unknown) => {
      expect(isPluginApiError(e)).toBe(true);
      expect((e as { status: number }).status).toBe(403);
    });
  });

  it('getOrNull resolves null on 404 and still rejects everything else', async () => {
    const ctx = makeMockCtx({
      apiResponses: {
        'get missing': apiError(404),
        'get broken': apiError(500),
        'get there': { ok: true },
      },
    });

    expect(await ctx.api.getOrNull('missing')).toBeNull();
    expect(await ctx.api.getOrNull('there')).toEqual({ ok: true });
    // The failure the old `catch(() => undefined)` swallowed alongside the 404.
    await expect(ctx.api.getOrNull('broken')).rejects.toThrow(/500/);
  });

  it('flushMockApi drains the hops a component adds behind a call', async () => {
    const ctx = makeMockCtx({ apiResponses: { 'get board': { cells: 3 } } });
    let rendered: number | undefined;

    // Two `.then` hops — the shape that makes a single `await Promise.resolve()` flaky.
    void ctx.api
      .get<{ cells: number }>('board')
      .then((b) => b.cells)
      .then((cells) => {
        rendered = cells;
      });

    await flushMockApi(ctx.api);

    expect(rendered).toBe(3);
  });

  it('settled() waits for calls started while it was waiting', async () => {
    const ctx = makeMockCtx({ apiResponses: { 'get a': 1, 'get b': 2 } });
    const seen: unknown[] = [];

    void ctx.api.get('a').then((a) => {
      seen.push(a);
      return ctx.api.get('b').then((b) => seen.push(b));
    });

    await flushMockApi(ctx.api);

    expect(seen).toEqual([1, 2]);
  });
});

describe('makeMockBlobs and the declared type', () => {
  it('normalises an empty File.type before checking the allow-list', async () => {
    const blobs = makeMockBlobs({ mimeTypes: ['image/png'] });
    // What Firefox hands over when the OS MIME lookup fails — and what used to be refused as
    // application/octet-stream, in one browser only.
    const file = new File(['x'], 'diagram.png', { type: '' });

    const stored = await blobs.upload(file);

    expect(stored.mime).toBe('image/png');
    expect(blobs.uploads[0]).toMatchObject({ filename: 'diagram.png', mime: 'image/png' });
  });

  it('sends the browser\'s raw value when asked to preserve it', async () => {
    const blobs = makeMockBlobs({ mimeTypes: ['image/png'] });
    const file = new File(['x'], 'diagram.png', { type: '' });

    await expect(blobs.upload(file, { declaredType: 'preserve' })).rejects.toThrow(/not allowed/);
  });
});

describe('the route double', () => {
  it('carries an empty query and hash by default', () => {
    const ctx = makeMockCtx();
    expect(ctx.route.query.get('sort')).toBeNull();
    expect(ctx.route.hash).toBe('');
  });

  it('merges a query override while keeping the navigate recorder', () => {
    const ctx = makeMockCtx({ route: { query: new URLSearchParams('sort=new'), path: 'moments' } });

    expect(ctx.route.query.get('sort')).toBe('new');
    expect(ctx.route.path).toBe('moments');
    ctx.route.navigate('moments?sort=old');
    expect(ctx.navigations).toEqual([{ subpath: 'moments?sort=old', replace: false }]);
  });
});

describe('the locale double', () => {
  it('is a one-language site by default, which is what a fresh install is', () => {
    const ctx = makeMockCtx();

    expect(ctx.locale.available()).toEqual([{ code: 'en', nativeName: 'English', isDefault: true }]);
    expect(ctx.locale.content()).toEqual(ctx.locale.available());
  });

  it('lets a test make content and shell languages differ', () => {
    // The asymmetry is the point: a Dutch imprint on an English-only site is a real configuration, and a
    // component that filled its editor tabs from `available()` would offer the wrong list.
    const ctx = makeMockCtx({
      locale: {
        current: () => 'en',
        onChange: () => () => {},
        available: () => [{ code: 'en', nativeName: 'English', isDefault: true }],
        content: () => [
          { code: 'en', nativeName: 'English', isDefault: true },
          { code: 'nl', nativeName: 'Nederlands', isDefault: false },
        ],
      },
    });

    expect(ctx.locale.available().map((l) => l.code)).toEqual(['en']);
    expect(ctx.locale.content().map((l) => l.code)).toEqual(['en', 'nl']);
  });
});

describe('the translation double', () => {
  it('is absent by default, like every other operator-gated capability', () => {
    expect(makeMockCtx().translation).toBeNull();
  });

  it('marks the text and records what it was asked for', async () => {
    const translation = makeMockTranslation();
    const ctx = makeMockCtx({ translation });

    const result = await ctx.translation!.translate({ text: 'Hello', to: 'nl' });

    expect(result.text).toBe('[nl] Hello');
    expect(result.detectedSourceLanguage).toBeNull();
    expect(result.fromCache).toBe(false);
    expect(translation.requests).toEqual([{ text: 'Hello', to: 'nl' }]);
  });

  it('echoes a stated source language rather than inventing a detection', async () => {
    const translation = makeMockTranslation();

    const result = await translation.translate({ text: 'Hallo', from: 'de', to: 'nl' });

    expect(result.detectedSourceLanguage).toBe('de');
  });

  it('can refuse, so the branch that breaks in production is testable', async () => {
    const translation = makeMockTranslation({ fail: apiError(429, { detail: 'slow down' }) });

    await expect(translation.translate({ text: 'Hello', to: 'nl' })).rejects.toMatchObject({ status: 429 });
  });
});
