// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.SearchHit;
import dev.mosaicast.plugin.api.SearchProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs a {@link SearchProvider} once per role, so "does this leak to a visitor" is one assertion
 * (ARCHITECTURE §13.5).
 *
 * <p>{@link SearchProvider} is the one place in this contract where <strong>access is the plugin's
 * job</strong>: the host has no model of a plugin's objects, so it cannot filter them, and a provider
 * returning a draft page to an anonymous visitor is a leak nothing else will catch. That makes the
 * per-role sweep the test worth writing, and writing it by hand means remembering that anonymous is
 * {@code null} rather than a fourth enum constant — the mistake this harness exists to remove.
 *
 * <pre>{@code
 * var results = new SearchProviderHarness(new WikiSearch(store)).search("kraken");
 *
 * assertThat(results.titles(null)).doesNotContain("Draft: The Kraken");   // anonymous
 * assertThat(results.titles(Role.PODCASTER)).contains("Draft: The Kraken");
 * assertThat(results.leakedToAnonymous("glossary/kraken")).isFalse();     // by subpath
 * }</pre>
 *
 * @since 0.9.0
 */
public final class SearchProviderHarness {

    /** The default {@code limit} passed to the provider — generous enough that filtering, not paging, decides. */
    private static final int DEFAULT_LIMIT = 50;

    private final SearchProvider provider;

    /**
     * Wraps a provider.
     *
     * @param provider the provider under test; never {@code null}
     */
    public SearchProviderHarness(SearchProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * Calls the provider once per role — <strong>anonymous included</strong>.
     *
     * @param query the query to run; never {@code null}
     * @return the hits each role got back
     */
    public SearchResults search(String query) {
        return search(query, DEFAULT_LIMIT);
    }

    /**
     * Calls the provider once per role at a given limit.
     *
     * @param query the query to run; never {@code null}
     * @param limit the limit to pass through
     * @return the hits each role got back
     */
    public SearchResults search(String query, int limit) {
        Objects.requireNonNull(query, "query");
        Map<Role, List<SearchHit>> byRole = new LinkedHashMap<>();
        List<SearchHit> anonymous = List.copyOf(provider.search(query, null, limit));
        for (Role role : Role.values()) {
            byRole.put(role, List.copyOf(provider.search(query, role, limit)));
        }
        return new SearchResults(anonymous, byRole);
    }

    /**
     * What each role saw for one query.
     *
     * @param anonymous hits an anonymous visitor got — the {@code null} role
     * @param byRole    hits each signed-in role got
     * @since 0.9.0
     */
    public record SearchResults(List<SearchHit> anonymous, Map<Role, List<SearchHit>> byRole) {

        /**
         * The hits one role got.
         *
         * @param role the role, or {@code null} for an anonymous visitor
         * @return that role's hits; never {@code null}, possibly empty
         */
        public List<SearchHit> forRole(Role role) {
            return role == null ? anonymous : byRole.getOrDefault(role, List.of());
        }

        /**
         * The titles one role got, for a readable assertion.
         *
         * @param role the role, or {@code null} for an anonymous visitor
         * @return the hit titles, in the provider's order
         */
        public List<String> titles(Role role) {
            List<String> titles = new ArrayList<>();
            forRole(role).forEach(hit -> titles.add(hit.title()));
            return List.copyOf(titles);
        }

        /**
         * Whether an anonymous visitor was shown a hit at the given subpath.
         *
         * <p>The single question this harness is for: name the subpath of something that should be
         * private and assert this is {@code false}.
         *
         * @param subpath the subpath below {@code /p/<pluginId>/}
         * @return whether an anonymous visitor received a hit for it
         */
        public boolean leakedToAnonymous(String subpath) {
            return anonymous.stream().anyMatch(hit -> hit.subpath().equals(subpath));
        }
    }
}
