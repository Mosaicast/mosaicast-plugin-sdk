// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a notification says, in every language the plugin can say it (ARCHITECTURE §17.1).
 *
 * <p><strong>Every locale up front, because the reader's language is not known yet.</strong> A
 * notification is usually written on a backend timer and read days later, by someone whose shell may be
 * in a different language than it was when the plugin sent it. So a single rendered sentence is wrong:
 * it freezes the language at send time, which is how a site that carefully translates everything ends up
 * with an inbox that is English — §12.7 broken on the surface where it is most obviously wrong. Hand over
 * all of them and let the shell choose when it draws the bell.
 *
 * <pre>{@code
 * new NotifyMessage(Map.of(
 *         "en", "Bingo resolved for S02E04",
 *         "de", "Bingo für S02E04 aufgelöst"))
 *     .withLink("board/42");
 * }</pre>
 *
 * <h2>Why not a translation key</h2>
 *
 * <p>Because nothing can resolve one. A plugin's catalogs ship inside its <em>frontend bundle</em> (§12.7)
 * and are loaded when its Web Component mounts; the bell is shell chrome and renders on pages where that
 * never happens. There is no plugin-scoped catalog endpoint and no manifest field naming one, so a key
 * would render to a reader as the literal string {@code bingo.resolved}. A key is the better design and
 * may yet arrive — it would need a plugin catalog surface first, which is a larger piece of work than the
 * notification it would serve.
 *
 * <p><strong>Interpolate before you send.</strong> There are no parameters here: the plugin holds the
 * values and its own catalogs, so it produces finished sentences. That also keeps the host out of the
 * business of substituting into somebody else's strings.
 *
 * <h2>The honest limitation</h2>
 *
 * <p>The set of languages is fixed when the notification is <em>sent</em>. A language the operator adds
 * next month cannot appear in a message already written, and those readers see the English. Retention
 * (§17.2) keeps that window short, and {@code en} is always present to fall back to — but it is a real
 * edge, and it is the price of not having plugin catalogs yet.
 *
 * <p><strong>Rendered as text, never HTML.</strong> The host escapes what it draws, so markup arrives as
 * the characters that were typed. There is no formatting to be had this way, and that is deliberate.
 * Keep personal data out: a notification is stored until it is read and then until retention expires.
 *
 * @param text locale code to the finished sentence in that language — never {@code null} or empty, keys
 *             trimmed and lower-cased, and <strong>always containing {@code en}</strong>. English is
 *             required because §12.7 makes it the one language a site can never switch off, which makes
 *             it the only safe terminal fallback; requiring the site's <em>default</em> instead would
 *             refuse a perfectly good plugin that does not happen to ship that language
 * @param link where the notification points when clicked, or {@code null} for one that goes nowhere.
 *             <strong>Internal targets only</strong>, host-validated: one of core's own page paths
 *             ({@code /episodes/kraken}), or a subpath of this plugin's own {@code /p/<pluginId>/}
 *             subtree — given either bare ({@code article/kraken}) or fully qualified. An off-site link
 *             is refused, because a notification is chrome the site is speaking through, and a plugin
 *             that can aim it anywhere can phish the site's own users in the site's own voice
 * @since 0.14.0
 */
public record NotifyMessage(Map<String, String> text, String link) {

    /** The language every message must carry: the source language, and the one a site cannot turn off. */
    public static final String FALLBACK_LOCALE = "en";

    /**
     * Canonical constructor: normalises the locale codes and insists on {@link #FALLBACK_LOCALE}.
     *
     * @throws IllegalArgumentException if {@code text} is empty, carries a blank code or sentence, or has
     *                                  no {@code en} entry
     */
    public NotifyMessage {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("a notification must say something in at least one language");
        }
        // Normalised the way every other locale code in this contract is (trimmed, lower-cased), so a
        // catalog keyed "EN" or "de " reaches the same entry the shell will look for.
        Map<String, String> normalised = new LinkedHashMap<>();
        text.forEach((code, sentence) -> {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("locale code must not be blank");
            }
            if (sentence == null || sentence.isBlank()) {
                throw new IllegalArgumentException("notification text for '" + code + "' must not be blank");
            }
            normalised.put(code.trim().toLowerCase(java.util.Locale.ROOT), sentence);
        });
        if (!normalised.containsKey(FALLBACK_LOCALE)) {
            throw new IllegalArgumentException(
                    "a notification must carry '" + FALLBACK_LOCALE + "' text: it is the one language a site "
                            + "cannot switch off, and the only fallback a reader is guaranteed to understand");
        }
        // Copied rather than referenced: a message may sit in a host queue after send() returns, and a
        // caller reusing its map for the next recipient must not rewrite one already handed over.
        text = Map.copyOf(normalised);
    }

    /**
     * A notification in every language the plugin has, with no link.
     *
     * @param text locale code to the finished sentence; must contain {@code en}
     */
    public NotifyMessage(Map<String, String> text) {
        this(text, null);
    }

    /**
     * An English-only notification — the honest shape for a plugin that ships no other language.
     *
     * <p>Not a shortcut to reach for otherwise: a reader with the shell in German gets the English, which
     * is exactly the outcome the per-locale map exists to avoid.
     *
     * @param english the sentence, in English
     */
    public NotifyMessage(String english) {
        this(Map.of(FALLBACK_LOCALE, english == null ? "" : english), null);
    }

    /**
     * The sentence a reader in this language would see, falling back to English.
     *
     * <p>What the shell does when it draws the bell, exposed so a plugin's tests can assert on it without
     * re-implementing (and slightly mis-implementing) the rule.
     *
     * @param locale the reader's locale code; any spelling, normalised as the constructor normalises
     * @return the text in that language, or the English text when there is none; never {@code null}
     */
    public String textFor(String locale) {
        if (locale == null || locale.isBlank()) {
            return text.get(FALLBACK_LOCALE);
        }
        return text.getOrDefault(locale.trim().toLowerCase(java.util.Locale.ROOT), text.get(FALLBACK_LOCALE));
    }

    /**
     * This message with a link attached, for building one in steps.
     *
     * @param target the internal path to point at; {@code null} clears it
     * @return a new message; this record is immutable
     */
    public NotifyMessage withLink(String target) {
        return new NotifyMessage(text, target);
    }
}
