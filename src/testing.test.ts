// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';
import { DEFAULT_THEME, makeMockCtx } from './testing.js';

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

  it('leaves episodeLabels absent unless supplied', () => {
    expect(makeMockCtx().episodeLabels).toBeUndefined();

    const ctx = makeMockCtx({
      episodes: ['the-sample-cast-s01e06'],
      episodeLabels: { 'the-sample-cast-s01e06': 'S01E06 · The Lighthouse' },
    });
    expect(ctx.episodeLabels?.['the-sample-cast-s01e06']).toBe('S01E06 · The Lighthouse');
  });
});
