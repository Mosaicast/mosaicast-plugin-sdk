// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One entry a plugin contributes to the site's {@code sitemap.xml} (ARCHITECTURE §6.6).
 *
 * <p><strong>{@code alternates} is how a plugin joins the site's {@code hreflang} groups</strong> (since
 * 0.12.0). A page can be requested as {@code ?lang=<code>} (§12.7), and {@code sitemap.xml} emits
 * reciprocal {@code xhtml:link} alternates with {@code x-default} on the bare URL. Until this component
 * existed a plugin could not participate at all: the host <strong>deliberately emits no alternates</strong>
 * for a plugin's entries rather than assuming the site's UI languages apply to content it cannot read
 * (§6.6, last sub-bullet). An empty map is still exactly that — today's behaviour, and the right one for a
 * plugin with nothing translated.
 *
 * <h2>Why a map of paths, and not a list of locale codes</h2>
 *
 * <p>The obvious cheaper shape is a list — "this page exists in {@code de} and {@code en}" — with the host
 * appending {@code ?lang=de} to {@link #loc()}. It was rejected for two reasons, and only the second is
 * about expressiveness:
 *
 * <ol>
 *   <li><strong>It cannot say what language {@code loc} itself is in.</strong> The host would have to assume
 *       the site default, which is the exact assumption §6.6 refuses to make on a plugin's behalf. Here the
 *       map <strong>must</strong> contain an entry pointing at {@code loc} — that entry is not redundant,
 *       it is the plugin naming the language its own page is written in, and the canonical constructor
 *       enforces it.</li>
 *   <li><strong>Translations do not always live at one path.</strong> A wiki whose German article is
 *       {@code /p/wiki/artikel} and whose English one is {@code /p/wiki/article} has two paths in one
 *       translation group, and a list of codes has no way to link them — the two entries would sit in the
 *       sitemap as unrelated pages. A plugin that <em>does</em> render one path per language simply maps
 *       every locale to the same {@code loc}, which is a map with one distinct value and costs it nothing.
 *   </li>
 * </ol>
 *
 * <p><strong>The host still owns URL shape.</strong> Values here are <em>paths</em>, the same shape as
 * {@link #loc()} — never full URLs, and never carrying {@code ?lang=} yourself. The host adds the parameter,
 * leaves the site default on the <strong>bare</strong> URL (a canonical URL never carries the default,
 * §6.6), points {@code x-default} there, and makes the group reciprocal and self-referential. It also
 * confines every value to your own {@code /p/<pluginId>/} namespace exactly as it does {@code loc}, so an
 * alternate cannot be aimed at a core URL — naming paths buys expressiveness, not reach.
 *
 * <p><strong>An alternate is a claim about content.</strong> List a language only if the page at that path
 * is really written in it. A default-language fallback served to a reader who asked for German is a
 * kindness to a visitor and a lie to a crawler, which is told a translation exists and handed the original.
 *
 * <pre>{@code
 * // One path rendered per language — the common case.
 * new SitemapUrl("/p/wiki/glossary/kraken", updatedAt,
 *         Map.of("en", "/p/wiki/glossary/kraken", "de", "/p/wiki/glossary/kraken"));
 *
 * // Translated slugs — one group, two paths.
 * new SitemapUrl("/p/wiki/article", updatedAt,
 *         Map.of("en", "/p/wiki/article", "de", "/p/wiki/artikel"));
 *
 * // Nothing translated: no alternates, which is what the host assumed anyway.
 * new SitemapUrl("/p/wiki/changelog", updatedAt);
 * }</pre>
 *
 * @param loc          an absolute-path location under {@code /p/<pluginId>/…}; never {@code null}
 * @param lastModified the last-modified timestamp for the {@code <lastmod>} hint, or {@code null} to omit it
 * @param alternates   locale code → the path that page is written in that language, including an entry for
 *                     {@code loc} itself; {@code null} or empty means "no translation group", the
 *                     pre-0.12.0 behaviour. Keys are trimmed and lower-cased; iteration order is preserved
 */
public record SitemapUrl(String loc, Instant lastModified, Map<String, String> alternates) {

    /** Canonical constructor validating the location and the translation group. */
    public SitemapUrl {
        Objects.requireNonNull(loc, "loc");
        alternates = normalizeAlternates(loc, alternates);
    }

    /**
     * An entry that is not part of a translation group — the pre-0.12.0 shape.
     *
     * <p>Kept as a real constructor because "this page has no translations" is the honest answer for most
     * plugin pages and is what the host already assumed, so the 0.12.0 upgrade stays a manifest bump and a
     * rebuild for a plugin with nothing to declare.
     *
     * @param loc          an absolute-path location under {@code /p/<pluginId>/…}; never {@code null}
     * @param lastModified the last-modified timestamp, or {@code null} to omit it
     * @since 0.12.0
     */
    public SitemapUrl(String loc, Instant lastModified) {
        this(loc, lastModified, Map.of());
    }

    private static Map<String, String> normalizeAlternates(String loc, Map<String, String> alternates) {
        if (alternates == null || alternates.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        alternates.forEach((code, path) -> {
            String key = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                throw new IllegalArgumentException("alternates has a null or blank locale code for '" + path + "'");
            }
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("alternates['" + key + "'] is null or blank; a locale with no "
                        + "path is not an alternate. Drop the entry instead.");
            }
            String previous = normalized.put(key, path.trim());
            if (previous != null) {
                throw new IllegalArgumentException("alternates lists '" + key + "' twice ('" + previous + "' and '"
                        + path.trim() + "'); a language can only be at one URL");
            }
        });
        if (!normalized.containsValue(loc)) {
            // Without this entry nothing states what language `loc` is in, and the host will not guess (§6.6).
            throw new IllegalArgumentException("alternates must contain an entry for loc itself ('" + loc
                    + "'), naming the language that page is written in; got " + normalized);
        }
        return Collections.unmodifiableMap(normalized);
    }
}
