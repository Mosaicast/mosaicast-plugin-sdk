// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

// @vitest-environment jsdom

import { describe, expect, it } from 'vitest';
import { FEED_HTML_POLICY } from './index.js';
import { makeMockCtx, makeMockFeeds, sanitizeLikeHost } from './testing.js';

describe('FEED_HTML_POLICY', () => {
  it('never lets styling through, whichever list a later edit widens', () => {
    expect(FEED_HTML_POLICY.allowedTags).not.toContain('style');
    expect(FEED_HTML_POLICY.allowedAttrs).not.toContain('style');
    expect(FEED_HTML_POLICY.forbidTags).toContain('style');
    expect(FEED_HTML_POLICY.forbidAttrs).toContain('style');
  });

  it('is frozen, so a plugin cannot loosen the policy the host and the test kit share', () => {
    expect(Object.isFrozen(FEED_HTML_POLICY)).toBe(true);
    expect(Object.isFrozen(FEED_HTML_POLICY.allowedTags)).toBe(true);
    expect(() => (FEED_HTML_POLICY.allowedTags as string[]).push('style')).toThrow();
  });

  it('allows the link schemes show notes use and nothing that runs', () => {
    const ok = FEED_HTML_POLICY.allowedUriRegexp;
    for (const uri of ['https://x.example', 'http://x', 'mailto:a@b', 'tel:+49', '#t=1:00', '/episodes/x']) {
      expect(ok.test(uri), uri).toBe(true);
    }
    for (const uri of ['javascript:alert(1)', 'JAVASCRIPT:x', 'data:text/html,x', 'vbscript:x']) {
      expect(ok.test(uri), uri).toBe(false);
    }
  });
});

describe('ctx.sanitize in the test kit', () => {
  const { sanitize } = makeMockCtx();

  it('removes the stylesheet that defaced the wiki plugin', () => {
    const out = sanitize(
      '<style>:host{position:fixed;inset:0;z-index:99999}</style>' +
        '<p style="position:fixed;inset:0">covered</p>',
    );
    expect(out).toBe('<p>covered</p>');
  });

  it('removes scripts, handlers, srcset and unsafe links', () => {
    const out = sanitize(
      '<script>alert(1)</script><img src="https://a.example/x.png" srcset="https://evil/1x" onerror="x()">' +
        '<a href="javascript:alert(1)">run</a><iframe src="https://evil"></iframe>',
    );
    expect(out).not.toMatch(/script|onerror|srcset|javascript|iframe|alert/);
    expect(out).toContain('<img src="https://a.example/x.png">');
    expect(out).toContain('<a>run</a>');
  });

  it('keeps the prose of an element it does not allow, but not of one that is never prose', () => {
    expect(sanitize('<div><section>kept</section></div>')).toBe('kept');
    expect(sanitize('<form><p>kept</p><input value="x"></form>')).toBe('<p>kept</p>');
    expect(sanitize('<svg><text>gone</text></svg><template><p>gone</p></template>')).toBe('');
    expect(sanitize('<!-- note --><b>bold</b>')).toBe('<b>bold</b>');
  });

  it('sends an external link to a new tab with the host rel, and leaves an internal one alone', () => {
    const out = sanitize('<p><a href="https://publisher.example/post">notes</a> <a href="/p/wiki">wiki</a></p>');
    expect(out).toContain(
      '<a href="https://publisher.example/post" target="_blank" rel="noopener noreferrer nofollow ugc">notes</a>',
    );
    expect(out).toContain('<a href="/p/wiki">wiki</a>');
  });

  it('replaces whatever target or rel the input carried', () => {
    const out = sanitize('<a href="https://x.example" target="_top" rel="opener">x</a>');
    expect(out).toBe('<a href="https://x.example" target="_blank" rel="noopener noreferrer nofollow ugc">x</a>');
  });

  it('answers empty input with an empty string', () => {
    expect(sanitize(null)).toBe('');
    expect(sanitize(undefined)).toBe('');
    expect(sanitize('')).toBe('');
  });

  it('is exported for a test that exercises it without a context', () => {
    expect(sanitizeLikeHost('<em onclick="x()">hi</em>')).toBe('<em>hi</em>');
  });
});

describe('descriptionText in the feeds double', () => {
  it('is derived from description when a fixture leaves it out', async () => {
    const feeds = makeMockFeeds({
      kraken: { title: 'The Kraken', description: '<p>Deep &amp; <b>dark</b></p>\n<p>water</p>' },
    });
    expect((await feeds.display('kraken'))?.descriptionText).toBe('Deep & dark water');
  });

  it('keeps a value the fixture pins', async () => {
    const feeds = makeMockFeeds().withDisplay('k', { title: 'K', description: '<p>x</p>', descriptionText: 'pinned' });
    expect((await feeds.display('k'))?.descriptionText).toBe('pinned');
  });
});
