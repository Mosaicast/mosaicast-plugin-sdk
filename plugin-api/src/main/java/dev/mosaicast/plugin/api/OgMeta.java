// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Locale;
import java.util.Objects;

/**
 * OpenGraph/Twitter share metadata a plugin supplies for one of its deep links (ARCHITECTURE §6.4).
 *
 * <p>Link scrapers (WhatsApp/Discord/Facebook) do not run JS, so the host injects these tags
 * server-side when serving a {@code /p/<pluginId>/…} URL, asking the plugin's
 * {@link ShareMetadataProvider}. No provider or no match falls back to site-level OG.
 *
 * <p><strong>{@code locale} is the language of <em>this</em> page, not of the install</strong> (since
 * 0.12.0). {@code og:locale} used to be an install-wide constant; §6.4 stopped describing it that way when
 * a URL gained the ability to name a language ({@code ?lang=<code>}, §12.7). The host resolves a locale for
 * every request and will use it, so a plugin whose pages are written in whatever language the shell is
 * showing says nothing and stays right. A plugin whose page is written in one fixed language — a German
 * article that stays German whoever asks for it — says so, and the tag stops depending on who scraped it.
 *
 * <p><strong>Naming a locale is a claim about the text in this record</strong>, the same rule the host
 * applies to its own pages: a page is advertised only in a language it is really written in (§6.6). If you
 * fall back to your default language because you have no translation for the request's locale, the honest
 * value is your default's code — not the code that was asked for.
 *
 * @param title       the share title; never {@code null}
 * @param description the share description; never {@code null}, may be empty
 * @param imageUrl    an absolute image URL, or {@code null} to let the host fall back to the podcast cover
 * @param locale      the language <em>this</em> title and description are written in ({@code de},
 *                    {@code pt-br}; trimmed and lower-cased), or {@code null} for "whatever the host
 *                    resolved for this request" — which is the right answer for most plugin pages
 */
public record OgMeta(String title, String description, String imageUrl, String locale) {

    /** Canonical constructor validating the required text fields and normalising the locale code. */
    public OgMeta {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        locale = normalizeLocale(locale);
    }

    /**
     * The pre-0.12.0 shape: share metadata in whatever language the host resolved for the request.
     *
     * <p>Kept as a real constructor rather than left to migration because it is the honest answer for most
     * plugin pages, and because it makes the 0.12.0 upgrade a manifest bump and a rebuild for everyone who
     * does not have a per-page language to declare.
     *
     * @param title       the share title; never {@code null}
     * @param description the share description; never {@code null}, may be empty
     * @param imageUrl    an absolute image URL, or {@code null} for the host's fallback
     * @since 0.12.0
     */
    public OgMeta(String title, String description, String imageUrl) {
        this(title, description, imageUrl, null);
    }

    /** Blank is the same statement as absent — "the host decides" — so it is stored as {@code null}. */
    private static String normalizeLocale(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toLowerCase(Locale.ROOT);
    }
}
