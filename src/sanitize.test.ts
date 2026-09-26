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

describe('ordinary Markdown survives the policy (#81)', () => {
  const { sanitize } = makeMockCtx();

  it('keeps where a resumed numbered list starts', () => {
    // `3. three` after a code block: without `start` the list silently renumbers from 1.
    expect(sanitize('<ol start="3"><li>three</li><li>four</li></ol>')).toBe('<ol start="3"><li>three</li><li>four</li></ol>');
  });

  it('keeps Markdown table alignment, the one form of it that needs no style', () => {
    const out = sanitize(
      '<table><thead><tr><th align="center">a</th></tr></thead><tbody><tr><td align="right">1</td></tr></tbody></table>',
    );
    expect(out).toContain('<th align="center">a</th>');
    expect(out).toContain('<td align="right">1</td>');
  });

  it('keeps the value of an attribute that is not a URL', () => {
    // Only href and src are held to the URI allow-list; "de" and "10" are not URLs and need not be.
    expect(sanitize('<p lang="de" dir="rtl">p</p>')).toBe('<p lang="de" dir="rtl">p</p>');
    expect(sanitize('<td colspan="2">c</td>')).not.toContain('colspan=""');
  });
});

describe('the allow-list is the whole list (core#232)', () => {
  const { sanitize } = makeMockCtx();

  it('names no data-* or aria-* attribute, so none survives', () => {
    expect(FEED_HTML_POLICY.allowedAttrs.some((a) => a.startsWith('data-') || a.startsWith('aria-'))).toBe(false);
    // A plugin marking its own elements with data-* can tell them from an author's.
    expect(sanitize('<p data-x="1" aria-label="y">t</p><a data-wiki="evil" href="/x">f</a>')).toBe(
      '<p>t</p><a href="/x">f</a>',
    );
  });
});

describe('content of removed elements, as the host measurably handles it', () => {
  const { sanitize } = makeMockCtx();

  it('drops what is never prose, including MathML and SVG text', () => {
    for (const html of ['<math><mi>x</mi></math>', '<svg><desc>d</desc></svg>', '<mtext>m</mtext>', '<title>t</title>']) {
      expect(sanitize(html), html).toBe('');
    }
  });

  it('keeps the text the host keeps — a double stricter than the host is wrong too', () => {
    // DOMPurify unwraps these rather than dropping their content; the kit used to drop it, so a plugin test
    // could pass on output production does not produce.
    expect(sanitize('<object>fallback</object>')).toBe('fallback');
    expect(sanitize('<select><option>x</option></select>')).toBe('x');
    expect(sanitize('<textarea>t</textarea>')).toBe('t');
    expect(sanitize('<noscript><b>n</b></noscript>')).toBe('<b>n</b>');
  });

  it('trims attribute values, and keeps a data: image but never a data: link, as the host does', () => {
    expect(sanitize('<a href=" /local">l</a>')).toBe('<a href="/local">l</a>');
    expect(sanitize('<img src="data:image/png;base64,AA" alt="i">')).toBe('<img src="data:image/png;base64,AA" alt="i">');
    expect(sanitize('<a href="data:text/html,x">d</a>')).toBe('<a>d</a>');
  });

  it('keeps an allowed element whole even when its name is on the content-dropping list', () => {
    expect(sanitize('<table><thead><tr><th>h</th></tr></thead></table>')).toContain('<th>h</th>');
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
