// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.PageRouteProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Asks a {@link PageRouteProvider} about a handful of subpaths at once, so "which of these 404s" is one
 * assertion (ARCHITECTURE §13.5).
 *
 * <p>The mistakes worth catching are both at the edges of the list, not in the middle of it: a provider
 * that says {@code false} for the <strong>root</strong> 404s the plugin's own landing page, and a provider
 * that <strong>throws</strong> is logged and skipped by the host, so the route quietly answers {@code 200}
 * again and the soft-404 this interface exists to remove comes back with nothing in the response to show
 * for it. So this harness always probes the root whether you list it or not — the same way
 * {@link SearchProviderHarness} always calls once as anonymous — and it records a throw rather than letting
 * it end the run, because the other subpaths' answers are still worth seeing.
 *
 * <pre>{@code
 * var routes = new PageRouteProviderHarness(new WikiRoutes(store))
 *         .check("glossary/kraken", "glossary/tpyo", "_search/kraken", "_admin");
 *
 * assertThat(routes.notFound()).containsExactly("glossary/tpyo");
 * assertThat(routes.servesRoot()).isTrue();          // the plugin's own landing page
 * assertThat(routes.failures()).isEmpty();           // a throw means the host serves 200 anyway
 * }</pre>
 *
 * @since 0.9.1
 */
public final class PageRouteProviderHarness {

    /** The plugin root, {@code /p/<pluginId>/} itself — always probed. */
    private static final String ROOT = "";

    private final PageRouteProvider provider;

    /**
     * Wraps a provider.
     *
     * @param provider the provider under test; never {@code null}
     */
    public PageRouteProviderHarness(PageRouteProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * Asks the provider about each subpath — <strong>plus the root</strong>, always.
     *
     * <p>Pass subpaths as the host does: below {@code /p/<pluginId>/}, with no leading slash. A provider
     * that throws is recorded in {@link RouteAnswers#failures()} and reported as serving {@code 200}, which
     * is what the host would do with it.
     *
     * @param subpaths the subpaths to ask about; none may be {@code null}, the root ({@code ""}) is added
     *                 if absent
     * @return what the host would answer for each
     */
    public RouteAnswers check(String... subpaths) {
        Objects.requireNonNull(subpaths, "subpaths");
        Map<String, Boolean> bySubpath = new LinkedHashMap<>();
        Map<String, RuntimeException> failures = new LinkedHashMap<>();
        ask(ROOT, bySubpath, failures);
        for (String subpath : subpaths) {
            ask(Objects.requireNonNull(subpath, "subpath"), bySubpath, failures);
        }
        return new RouteAnswers(bySubpath, failures);
    }

    private void ask(String subpath, Map<String, Boolean> bySubpath, Map<String, RuntimeException> failures) {
        try {
            bySubpath.put(subpath, provider.hasRoute(subpath));
        } catch (RuntimeException e) {
            // The host's posture: log, skip, serve 200. A broken plugin must not turn a page into a 404.
            failures.put(subpath, e);
            bySubpath.put(subpath, true);
        }
    }

    /**
     * What the host would answer for each subpath asked about.
     *
     * @param bySubpath whether the route is served ({@code true} → {@code 200}, {@code false} → {@code 404}),
     *                  in the order asked, the root first
     * @param failures  the subpaths whose provider call threw, with the exception — served as {@code 200}
     *                  above, since that is what the host does with a broken provider
     * @since 0.9.1
     */
    public record RouteAnswers(Map<String, Boolean> bySubpath, Map<String, RuntimeException> failures) {

        /** Copies both maps, keeping the order the subpaths were asked in. */
        public RouteAnswers {
            bySubpath = Collections.unmodifiableMap(new LinkedHashMap<>(bySubpath));
            failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
        }

        /**
         * Whether the host would serve this subpath.
         *
         * @param subpath a subpath passed to {@link PageRouteProviderHarness#check(String...)}
         * @return {@code true} for a {@code 200}, {@code false} for a real {@code 404}
         * @throws IllegalArgumentException if that subpath was never asked about — a typo in a test that
         *                                  would otherwise read as a passing assertion
         */
        public boolean serves(String subpath) {
            Boolean answer = bySubpath.get(Objects.requireNonNull(subpath, "subpath"));
            if (answer == null) {
                throw new IllegalArgumentException(
                        "'" + subpath + "' was not checked; pass it to check(...). Asked about: " + bySubpath.keySet());
            }
            return answer;
        }

        /**
         * Whether the plugin's own landing page, {@code /p/<pluginId>/}, is served.
         *
         * <p>Always available: the harness probes the root whether it was listed or not. A provider written
         * as a lookup over its own slugs answers {@code false} here, because the empty string is not one of
         * them.
         *
         * @return whether the root is served
         */
        public boolean servesRoot() {
            return serves(ROOT);
        }

        /**
         * The subpaths the host would 404, in the order asked.
         *
         * @return the subpaths answered {@code false}; never {@code null}, possibly empty
         */
        public List<String> notFound() {
            return filter(false);
        }

        /**
         * The subpaths the host would serve, in the order asked.
         *
         * @return the subpaths answered {@code true}, a thrown provider call included; never {@code null}
         */
        public List<String> served() {
            return filter(true);
        }

        /**
         * Whether the provider threw for a subpath.
         *
         * @param subpath a subpath passed to {@link PageRouteProviderHarness#check(String...)}
         * @return whether that call threw — in which case {@link #serves(String)} is {@code true} because
         *         the host swallows it, not because the plugin said so
         */
        public boolean threw(String subpath) {
            return failures.containsKey(Objects.requireNonNull(subpath, "subpath"));
        }

        private List<String> filter(boolean wanted) {
            List<String> matches = new ArrayList<>();
            bySubpath.forEach((subpath, answer) -> {
                if (answer == wanted) {
                    matches.add(subpath);
                }
            });
            return List.copyOf(matches);
        }
    }
}
