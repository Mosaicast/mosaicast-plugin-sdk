// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_THEME, makeMockConsent, makeMockCtx } from './testing.js';

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
