// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';
import {
  ACTION_BODY_LIMIT,
  ACTION_NAME_PATTERN,
  CHANNEL_EVENT_LIMIT,
  CHANNEL_NAME_PATTERN,
  PROBLEM_TYPES,
  defineManifest,
  isActionRejected,
  isPluginApiError,
  isRateLimited,
  type ChannelEvent,
  type ChannelStatus,
  type PluginDataDeclaration,
} from './index.js';
import {
  actionRejection,
  apiError,
  makeMockActions,
  makeMockChannels,
  makeMockCtx,
  makeMockDocs,
  rateLimited,
} from './testing.js';

const ep = { type: 'episode', id: 's1e01' } as const;

describe('the realtime grammar (0.20.0)', () => {
  it('mirrors the Java patterns and limits', () => {
    expect(ACTION_NAME_PATTERN.test('move')).toBe(true);
    expect(ACTION_NAME_PATTERN.test('a'.repeat(64))).toBe(true);
    expect(ACTION_NAME_PATTERN.test('a'.repeat(65))).toBe(false);
    expect(ACTION_NAME_PATTERN.test('Move')).toBe(false);
    expect(ACTION_NAME_PATTERN.test('-x')).toBe(false);
    expect(CHANNEL_NAME_PATTERN.test('game:42_a-B')).toBe(true);
    expect(CHANNEL_NAME_PATTERN.test('a/b')).toBe(false);
    expect(ACTION_BODY_LIMIT).toBe(16384);
    expect(CHANNEL_EVENT_LIMIT).toBe(8192);
  });

  it('types the manifest blocks', () => {
    const m = defineManifest({
      id: 'game',
      version: '1.0.0',
      platformApi: '0.20.0',
      name: 'Game',
      data: { readableBy: 'fan', writableBy: 'podcaster', backendPrivate: ['game:*'] },
      actions: { callableBy: 'fan', names: { join: {}, start: { callableBy: 'podcaster' } }, perUserPerMinute: 120 },
      channels: { subscribableBy: 'fan', perUserSubscriptions: 4 },
    });
    expect(Object.keys(m.actions.names)).toEqual(['join', 'start']);
  });
});

describe('isActionRejected / isRateLimited', () => {
  const err = (status: number, problem?: object) => Object.assign(new Error('x'), { status, problem });

  it('narrows a rejection to its code', () => {
    const e: unknown = err(422, { type: PROBLEM_TYPES.actionRejected, code: 'not-your-turn' });
    expect(isActionRejected(e)).toBe(true);
    if (isActionRejected(e)) expect(e.problem.code).toBe('not-your-turn');
    expect(isActionRejected(err(422, { type: PROBLEM_TYPES.actionRejected }))).toBe(false);
    expect(isActionRejected(err(403, { type: PROBLEM_TYPES.forbidden }))).toBe(false);
    expect(isActionRejected({ status: 422 })).toBe(false);
  });

  it('treats a 429 and a busy plugin alike', () => {
    expect(isRateLimited(err(429, { type: PROBLEM_TYPES.tooManyRequests, retryAfterMs: 500 }))).toBe(true);
    expect(isRateLimited(err(503, { type: PROBLEM_TYPES.actionBusy, retryAfterMs: 500 }))).toBe(true);
    expect(isRateLimited(err(503, { type: PROBLEM_TYPES.actionUnavailable }))).toBe(false);
    expect(isRateLimited(err(429))).toBe(false);
  });
});

describe('makeMockActions', () => {
  it('answers through the handler after a JSON round trip, and records the call', async () => {
    const actions = makeMockActions({ echo: (p, { scope }) => ({ p, scope, at: new Date(0) }), quiet: () => undefined });
    const answer = await actions.call<{ p: unknown; scope: unknown; at: string }>('echo', { when: new Date(0) });

    expect(answer).toEqual({ p: { when: '1970-01-01T00:00:00.000Z' }, scope: { type: 'site', id: 'main' }, at: '1970-01-01T00:00:00.000Z' });
    expect(await actions.call('quiet')).toBeNull();
    expect(actions.calls).toEqual([
      { name: 'echo', payload: { when: '1970-01-01T00:00:00.000Z' }, scope: { type: 'site', id: 'main' } },
      { name: 'quiet', payload: null, scope: { type: 'site', id: 'main' } },
    ]);
  });

  it('uses the scope it was given, and the one a call passes', async () => {
    const actions = makeMockActions({ a: () => 1 }, { scope: ep });
    await actions.call('a');
    await actions.call('a', null, { scope: { type: 'site', id: 'main' } });
    expect(actions.calls.map((c) => c.scope)).toEqual([ep, { type: 'site', id: 'main' }]);
  });

  it('rejects the way the host does', async () => {
    const actions = makeMockActions({
      move: () => {
        throw actionRejection('not-your-turn', 'it is Bob');
      },
      spam: () => rateLimited(250),
      broken: () => apiError(504, { type: PROBLEM_TYPES.actionTimeout }),
    });

    const rejected = await actions.call('move').catch((e: unknown) => e);
    expect(isActionRejected(rejected) && rejected.problem.code).toBe('not-your-turn');
    const limited = await actions.call('spam').catch((e: unknown) => e);
    expect(isRateLimited(limited) && limited.problem.retryAfterMs).toBe(250);
    await expect(actions.call('broken')).rejects.toMatchObject({ status: 504 });
    await expect(actions.call('nope')).rejects.toMatchObject({ status: 404, problem: { type: PROBLEM_TYPES.unknownAction } });
    await expect(actions.call('move', 'x'.repeat(ACTION_BODY_LIMIT))).rejects.toMatchObject({
      status: 413,
      problem: { type: PROBLEM_TYPES.payloadTooLarge },
    });
    expect(() => actionRejection('Bad Code')).toThrow();
  });

  it('refuses a signed-out caller before any handler', async () => {
    let ran = false;
    const actions = makeMockActions({ a: () => (ran = true) }, { signedIn: false });
    const e = await actions.call('a').catch((x: unknown) => x);
    expect(isPluginApiError(e) && e.status).toBe(401);
    expect(ran).toBe(false);
  });

  it('is null on a context until passed', () => {
    expect(makeMockCtx().actions).toBeNull();
    expect(makeMockCtx().channels).toBeNull();
    const actions = makeMockActions();
    expect(makeMockCtx({ actions }).actions).toBe(actions);
  });
});

describe('makeMockChannels', () => {
  it('opens, delivers and closes', () => {
    const channels = makeMockChannels();
    const got: ChannelEvent[] = [];
    const statuses: ChannelStatus[] = [];
    const sub = channels.subscribe('game:1', (e) => got.push(e), { onStatus: (s) => statuses.push(s) });

    channels.emit('game:1', 'moved', { at: new Date(0) }, { at: '2026-01-01T00:00:00.000Z' });
    channels.emit('game:2', 'moved', {});
    expect(got).toEqual([{ event: 'moved', data: { at: '1970-01-01T00:00:00.000Z' }, at: '2026-01-01T00:00:00.000Z' }]);

    sub.close();
    sub.close();
    channels.emit('game:1', 'moved', {});
    expect(got).toHaveLength(1);
    expect(statuses).toEqual(['connecting', 'open', 'closed']);
    expect(channels.subscriptions[0].statuses).toEqual(statuses);
  });

  it('scopes delivery when asked', () => {
    const channels = makeMockChannels({ scope: ep });
    const here: ChannelEvent[] = [];
    const site: ChannelEvent[] = [];
    channels.subscribe('inbox', (e) => here.push(e));
    channels.subscribe('inbox', (e) => site.push(e), { scope: { type: 'site', id: 'main' } });

    channels.emit('inbox', 'invited', null, { scope: { type: 'site', id: 'main' } });
    channels.emit('inbox', 'ping');
    expect(here.map((e) => e.event)).toEqual(['ping']);
    expect(site.map((e) => e.event)).toEqual(['invited', 'ping']);
  });

  it('pushes resets, refusals and the end of access', () => {
    const channels = makeMockChannels();
    let resets = 0;
    const statuses: ChannelStatus[] = [];
    channels.subscribe('game:1', () => {}, { onReset: () => resets++, onStatus: (s) => statuses.push(s) });
    channels.reset('game:1');
    expect(resets).toBe(1);

    channels.refuse('game:1');
    expect(statuses.at(-1)).toBe('refused');
    const again: ChannelStatus[] = [];
    channels.subscribe('game:1', () => {}, { onStatus: (s) => again.push(s) });
    expect(again).toEqual(['refused']);

    const other: ChannelStatus[] = [];
    channels.subscribe('game:2', () => {}, { onStatus: (s) => other.push(s) });
    channels.end('game:2');
    expect(other).toEqual(['connecting', 'open', 'closed']);
  });

  it('refuses a signed-out visitor without connecting, and malformed names throw', () => {
    const statuses: ChannelStatus[] = [];
    makeMockChannels({ signedIn: false }).subscribe('inbox', () => {}, { onStatus: (s) => statuses.push(s) });
    expect(statuses).toEqual(['refused']);

    const channels = makeMockChannels();
    expect(() => channels.subscribe('a/b', () => {})).toThrow();
    expect(() => channels.emit('inbox', 'Bad Event')).toThrow();
  });
});

describe('makeMockDocs with backendPrivate', () => {
  const data: PluginDataDeclaration = { readableBy: 'anonymous', writableBy: 'podcaster', backendPrivate: ['game:*'] };
  const initial = { 'data/site/main/game:1': 'hidden', 'data/site/main/board': 'public', 'data/user/me/game:mine': 1 };

  it('refuses every client, admins included, and hides the keys from lists and batches', async () => {
    const docs = makeMockDocs(initial, { data, viewer: 'admin' });
    const forbidden = { status: 403, problem: { type: PROBLEM_TYPES.backendPrivateKey } };

    await expect(docs.get('site', 'game:1')).rejects.toMatchObject(forbidden);
    await expect(docs.put('site', 'game:1', 'x')).rejects.toMatchObject(forbidden);
    await expect(docs.remove('site', 'game:1')).rejects.toMatchObject(forbidden);
    expect((await docs.list('site')).items.map((i) => i.key)).toEqual(['board']);
    expect(await docs.getMany('site', ['main'], ['game:1', 'board'])).toEqual({ main: { board: 'public' } });
  });

  it('leaves the user partition alone', async () => {
    const docs = makeMockDocs(initial, { data, viewer: 'fan' });
    expect(await docs.get('self', 'game:mine')).toBe(1);
  });

  it('rejects a malformed selector like the host', () => {
    expect(() => makeMockDocs({}, { data: { writableBy: 'fan', backendPrivate: ['a*b'] }, viewer: 'fan' })).toThrow();
  });
});
