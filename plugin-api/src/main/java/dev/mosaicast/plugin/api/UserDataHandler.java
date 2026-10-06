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
     * Everything this plugin holds about a user, for a data export, as one JSON document.
     *
     * <p>Read-only, and called independently of {@link #eraseUser(String)} — an export is a request in its
     * own right, and an export missing the plugin half is an incomplete answer to a legal one.
     *
     * <p>Defaults to nothing, for plugins whose erasure is a hard delete of rows they would rather not
     * describe. The map is serialised by the host, so use plain JSON-shaped values.
     *
     * <p>Since 0.19.0 this is the short form of {@link #exportFiles(String)}: a plugin that implements only
     * this has its map exported as {@code data.json}. Implement {@code exportFiles} instead when your data has
     * a format of its own.
     *
     * @param userId the user's id; never {@code null}
     * @return this plugin's data for that user, or {@link Optional#empty()} if it exports none
     */
    default Optional<Map<String, Object>> exportUser(String userId) {
        return Optional.empty();
    }

    /**
     * This plugin's part of a person's data export, as files in the plugin's own format (ARCHITECTURE §12.8,
     * GDPR Art. 15 and 20).
     *
     * <p>The host bundles every plugin's part into one ZIP for the person, each under
     * {@code plugins/<pluginId>/}, beside what core holds. A {@code Map} serialised to JSON was the only shape
     * {@link #exportUser(String)} could take; portability is better served by a file another tool can read back
     * — a bingo card in the format its own import reads, a CSV, the photo the person uploaded.
     *
     * <pre>{@code
     * @Override
     * public Optional<UserExport> exportFiles(String userId) {
     *     List<Card> cards = cardsOf(userId);
     *     if (cards.isEmpty()) return Optional.empty();
     *     return Optional.of(UserExport.of(
     *             ExportFile.text("cards.json", "application/json", toBingoV1(cards))));
     * }
     * }</pre>
     *
     * <h4>What the host expects</h4>
     * <ul>
     *   <li><strong>Only this person's data.</strong> Never another user's rows, even ones that mention them
     *       — a leaderboard the person appears on is not theirs to receive. This is the property the host
     *       cannot check.</li>
     *   <li><strong>Read-only.</strong> The export may be retried, and a person may ask again tomorrow; a call
     *       must change nothing it reads.</li>
     *   <li><strong>Bounded.</strong> At most {@link UserExport#MAX_BYTES} over all files, answered within
     *       {@link UserExport#TIMEOUT}. A larger part or a slower answer is recorded as failed for this plugin
     *       — never truncated — and the person is told their export is incomplete.</li>
     *   <li><strong>Throwing is not silent</strong>, as with erasure: the host records an outcome per plugin,
     *       so throw when you could not finish rather than returning a partial answer.</li>
     * </ul>
     *
     * <p><strong>The default, and the order the host asks in.</strong> The host calls this first. A value is
     * the plugin's part. {@link Optional#empty()} — what the default returns — makes it ask
     * {@link #exportUser(String)} and write a non-empty map as {@code data.json}; empty there too means the
     * plugin holds nothing on this person. So a plugin written against the {@code Map} form keeps exporting
     * unchanged, and a plugin that implements this one leaves {@code exportUser} at its default. The host
     * does the {@code data.json} wrapping rather than this method, so the contract never serialises JSON
     * itself.
     *
     * @param userId the user's id; never {@code null}
     * @return the files for that person, or {@link Optional#empty()} for nothing (or, by default, for "use
     *         {@link #exportUser(String)}")
     * @since 0.19.0
     */
    default Optional<UserExport> exportFiles(String userId) {
        return Optional.empty();
    }
}
