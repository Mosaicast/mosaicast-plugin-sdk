// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Map;
import java.util.Objects;

/**
 * What a notification says, as a translation key rather than a sentence (ARCHITECTURE §17.1).
 *
 * <p><strong>A key and parameters, never a rendered string.</strong> The plugin does not know which
 * language the recipient reads in: a notification is written on a backend timer, and the person who
 * receives it may open the site three days later with the shell in German. A rendered string would fix
 * the language at send time, which is how a site that carefully translates everything ends up with an
 * inbox that is English — §12.7 broken on the surface where it is most obviously wrong. So the plugin
 * sends {@code bingo.resolved} and the shell resolves it against the plugin's own locale bundle when the
 * inbox is drawn.
 *
 * <p>The consequence worth stating plainly: <strong>a key with no entry in your bundle has nothing to
 * fall back to.</strong> Ship the key in every locale you ship a UI for.
 *
 * <p>{@link #params()} are substituted into the resolved string. They are <em>values</em>, not
 * sentences — a slug, a count, an episode title — and they are not translated, because the host has no
 * way to know which of them are prose. Keep personal data out of them: a notification is stored until it
 * is read and then until retention expires (§17.2).
 *
 * <p><strong>Rendered as text, never HTML.</strong> The host escapes what it draws, so markup in a
 * parameter arrives as the characters that were typed. There is no formatting to be had this way, and
 * that is deliberate.
 *
 * @param key    the translation key, resolved against this plugin's locale bundle; never {@code null} or
 *               blank
 * @param params values substituted into the resolved string; never {@code null} (empty when there are
 *               none), defensively copied, and never translated
 * @param link   where the notification points when clicked, or {@code null} for one that goes nowhere.
 *               <strong>Internal targets only</strong>, host-validated: one of core's own page paths
 *               ({@code /episodes/kraken}), or a subpath of this plugin's own {@code /p/<pluginId>/}
 *               subtree — given either bare ({@code article/kraken}) or fully qualified. An off-site
 *               link is refused, because a
 *               notification is chrome the site is speaking through, and a plugin that can aim it
 *               anywhere can phish the site's own users in the site's own voice
 * @since 0.14.0
 */
public record NotifyMessage(String key, Map<String, String> params, String link) {

    /**
     * Canonical constructor: validates the key and copies the parameters.
     *
     * @throws IllegalArgumentException if {@code key} is blank
     */
    public NotifyMessage {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("notification key must not be blank");
        }
        // Copied rather than referenced: a message may sit in a host queue after send() returns, and a
        // caller reusing its map for the next recipient must not rewrite one already handed over.
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /**
     * A notification with no parameters and no link — the whole message is the key.
     *
     * @param key the translation key
     */
    public NotifyMessage(String key) {
        this(key, Map.of(), null);
    }

    /**
     * A notification with parameters but no link.
     *
     * @param key    the translation key
     * @param params values substituted into the resolved string
     */
    public NotifyMessage(String key, Map<String, String> params) {
        this(key, params, null);
    }

    /**
     * This message with a link attached, for building one in steps.
     *
     * @param target the internal path to point at; {@code null} clears it
     * @return a new message; this record is immutable
     */
    public NotifyMessage withLink(String target) {
        return new NotifyMessage(key, params, target);
    }
}
