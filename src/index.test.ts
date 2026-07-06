// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

// @vitest-environment jsdom

import { describe, expect, it } from 'vitest';
import {
  createPluginI18n,
  defineMosaicastElement,
  PLATFORM_API_VERSION,
  type PluginContext,
} from './index.js';
import { makeMockCtx } from './testing.js';

describe('PLATFORM_API_VERSION', () => {
  it('is the mirrored SemVer anchor', () => {
    expect(PLATFORM_API_VERSION).toBe('0.1.1');
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

  function localeHandle(initial: string) {
    let cb: ((l: string) => void) | undefined;
    return {
      handle: { current: () => initial, onChange: (fn: (l: string) => void) => (cb = fn) },
      change: (l: string) => cb?.(l),
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
});
