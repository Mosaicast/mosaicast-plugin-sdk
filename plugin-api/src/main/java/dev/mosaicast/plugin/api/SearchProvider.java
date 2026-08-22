// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import org.pf4j.ExtensionPoint;
import java.util.List;

/**
 * Optional extension point: a plugin contributes its own content to the site's search.
 *
 * <p>Core searches episodes. Plugin content was invisible to it and there was no contract by which a
 * plugin could contribute a result, so every plugin with searchable content grew a second, private search
 * box on the same site — right for the plugin's own data, wrong for a visitor, who then has two places to
 * type the same query and no way to learn the answer is in the other one.
 *
 * <p>Same shape as {@link SitemapProvider} and {@link ShareMetadataProvider}: a plugin MAY implement it in
 * addition to {@link PluginBackend}, the host owns the URL shape and the presentation, and hits are keyed
 * by a subpath under the plugin's own {@code /p/<id>/} subtree. <strong>Empty is normal</strong> — most
 * plugins have nothing to search, and not implementing this is how they say so. There is no manifest
 * declaration.
 *
 * <h2>Results are grouped, not merged</h2>
 *
 * <p>The host renders a section per source ("Episodes", then each plugin's) rather than interleaving into
 * one list. Ranking across sources is not solvable: {@link SearchHit#score()} and Postgres
 * {@code ts_rank} are not on one scale. Grouping stays honest, and stays correct when a plugin changes
 * how it scores.
 *
 * <h2>Access is your job here, unusually</h2>
 *
 * <p>Everywhere else in this contract the host resolves access and the plugin consumes the result
 * (ARCHITECTURE §7, §10). It cannot do that for objects it has no model of: core does not know that your
 * row has a {@code published} flag, or that a revision is visible to its author only. So the rule is
 * plain — <strong>a provider that returns a draft page to an anonymous visitor is a leak the host will
 * not catch.</strong> Filter on {@code role} yourself, and test it: the test kit's
 * {@code SearchProviderHarness} calls a provider once per role, anonymous included, for exactly this.
 *
 * <h2>Being slow is a failure too</h2>
 *
 * <p>The host applies a per-provider timeout and renders partial results — a plugin whose search is down
 * costs its own section, not the visitor's query. Keep this cheap and bounded; it runs on a request.
 *
 * @since 0.9.0
 */
public interface SearchProvider extends ExtensionPoint {

    /**
     * Hits from this plugin's own content, best first.
     *
     * <p>Return only what {@code role} may read. An empty {@code query} should match nothing rather than
     * everything, the same rule {@link SchemaStore#search(String, String, String, Criteria, Class)}
     * follows.
     *
     * @param query what the visitor typed, verbatim — take it as a person writes it; a stray quote or
     *              operator must not throw. Never {@code null}, may be empty
     * @param role  the caller's role, or <strong>{@code null} for an anonymous visitor</strong> — the
     *              same absence {@code ctx.user === null} expresses on the frontend
     * @param limit the most hits to return; returning more is the host's to truncate, but do the work of
     *              bounding the query yourself
     * @return this plugin's hits, best first, at most {@code limit}; never {@code null}, possibly empty
     */
    List<SearchHit> search(String query, Role role, int limit);
}
