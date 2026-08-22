// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import org.pf4j.ExtensionPoint;
import java.util.Map;
import java.util.Optional;

/**
 * Optional extension point: what this plugin does with a person's data when their account goes away
 * (ARCHITECTURE §12).
 *
 * <p>The spec already says what must happen — "on account deletion <strong>pseudonymize</strong> public
 * bingo contributions (cut the identity link, aggregates/leaderboard stay correct), don't hard delete".
 * Bingo is a plugin. Core cannot find its contributions, cannot pseudonymise them, and cannot know that
 * pseudonymising is the right answer rather than deleting: that judgement belongs to whoever designed the
 * data. This is the hook that asks.
 *
 * <p>A separate {@link ExtensionPoint} rather than methods on {@link PluginBackend}, for the reason
 * {@link SitemapProvider} is one: most plugins hold no personal data, and {@code PluginBackend} is a
 * one-method interface worth keeping that way.
 *
 * <h2>What the host can already do without you</h2>
 *
 * <p>The {@link ScopeType#USER} scope is host-owned, so core drops {@code data/user/<id>/…} on its own.
 * That is the easy half, and it hides the hard one: <strong>schema tables and blobs</strong>. Identity
 * there lives in ordinary columns a plugin chose — a wiki's {@code page.updatedBy}, a
 * {@code revision.author} — and the host provisioned those tables without ever learning which column is a
 * person. Same for a blob whose bytes are someone's uploaded photo. Implement this if you hold either.
 *
 * <h2>Semantics, because half-erased is the failure mode</h2>
 *
 * <ul>
 *   <li><strong>Ordering:</strong> handlers run <em>before</em> core drops the account row. A plugin
 *       resolving a user id against a user that no longer exists cannot pseudonymise sensibly.</li>
 *   <li><strong>Idempotency is required.</strong> A failed deletion is retried, and your handler may be
 *       called again on data you already erased. Erasing nothing must succeed.</li>
 *   <li><strong>Throwing is not silent.</strong> The host records an outcome per plugin and surfaces an
 *       unfinished erasure rather than logging and forgetting it — so throw when you could not finish,
 *       instead of swallowing and reporting success.</li>
 * </ul>
 *
 * <p>Both methods live on one interface deliberately: they need the same "find this user's rows" query,
 * and shipping erasure alone means every plugin writes that query twice.
 *
 * @since 0.9.0
 */
public interface UserDataHandler extends ExtensionPoint {

    /**
     * Erases or pseudonymises everything this plugin holds about a user.
     *
     * <p><strong>Which of the two is your call</strong> — the host cannot make it. Cut the identity link
     * and keep the contribution where an aggregate must stay correct (a leaderboard, a vote count); hard
     * delete where the content itself is the person's. Do not leave a dangling user id either way.
     *
     * <p>Must be idempotent: it may be retried after a partial failure.
     *
     * @param userId the user's id, as it appears in {@link OwnedDocEntry#userId()} and in whatever column
     *               your plugin stores it in; never {@code null}
     */
    void eraseUser(String userId);

    /**
     * Everything this plugin holds about a user, for a data export.
     *
     * <p>Read-only, and called independently of {@link #eraseUser(String)} — an export is a request in its
     * own right, and an export missing the plugin half is an incomplete answer to a legal one.
     *
     * <p>Defaults to nothing, for plugins whose erasure is a hard delete of rows they would rather not
     * describe. The map is serialised by the host, so use plain JSON-shaped values.
     *
     * @param userId the user's id; never {@code null}
     * @return this plugin's data for that user, or {@link Optional#empty()} if it exports none
     */
    default Optional<Map<String, Object>> exportUser(String userId) {
        return Optional.empty();
    }
}
