// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.SitemapProvider;
import dev.mosaicast.plugin.api.SitemapUrl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Collects a {@link SitemapProvider}'s entries and reports what the host would <em>reject</em> in them
 * (ARCHITECTURE §6.6, §13.5).
 *
 * <p>The failures worth catching here are silent ones. The host drops a {@code loc} — and, since 0.12.0,
 * an {@link SitemapUrl#alternates()} path — that is not under this plugin's {@code /p/<pluginId>/}
 * namespace, because an alternate is a URL a plugin names and namespace confinement is what stops that
 * from reaching a core page. Dropping is the right posture for a host and a terrible one for an author:
 * the plugin still builds, the provider still returns, and the pages simply never appear in
 * {@code sitemap.xml}. Nothing in the type system is going to mention it.
 *
 * <p>{@link SitemapUrl} already rejects the mistakes it can see from one entry — a group that never names
 * the language {@code loc} is written in, a locale listed twice. What it cannot see is the set: two entries
 * that belong to <strong>one</strong> translation group but declare <strong>different</strong> groups, which
 * is what a partly-updated slug map looks like and what leaves a crawler with two contradictory claims about
 * the same pages.
 *
 * <pre>{@code
 * var sitemap = new SitemapProviderHarness("wiki", new WikiSitemap(store)).collect();
 *
 * assertThat(sitemap.problems()).isEmpty();               // nothing the host would drop or contradict
 * assertThat(sitemap.locations()).contains("/p/wiki/article");
 * assertThat(sitemap.locales("/p/wiki/article")).containsExactly("de", "en");
 * }</pre>
 *
 * @since 0.12.0
 */
public final class SitemapProviderHarness {

    private final String namespace;
    private final SitemapProvider provider;

    /**
     * Wraps a provider, together with the plugin id whose namespace its URLs must stay inside.
     *
     * @param pluginId the plugin's id — the {@code <pluginId>} in {@code /p/<pluginId>/}; never {@code null}
     * @param provider the provider under test; never {@code null}
     */
    public SitemapProviderHarness(String pluginId, SitemapProvider provider) {
        this.namespace = "/p/" + Objects.requireNonNull(pluginId, "pluginId") + "/";
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * Calls {@link SitemapProvider#urls()} once and checks the result as the host would.
     *
     * <p>A provider that throws is <em>not</em> caught: the host's answer to that is "this plugin has no
     * sitemap entries", which is loud enough on its own and better read as the original stack trace.
     *
     * @return the entries and everything wrong with them
     */
    public SitemapEntries collect() {
        List<SitemapUrl> urls = List.copyOf(provider.urls());
        List<String> problems = new ArrayList<>();
        Set<String> seenLocations = new LinkedHashSet<>();

        for (SitemapUrl url : urls) {
            if (!inNamespace(url.loc())) {
                problems.add("loc '" + url.loc() + "' is outside " + namespace + "; the host drops it");
            }
            if (!seenLocations.add(url.loc())) {
                problems.add("loc '" + url.loc() + "' is listed twice");
            }
            url.alternates().forEach((code, path) -> {
                if (!inNamespace(path)) {
                    problems.add("alternate '" + code + "' of '" + url.loc() + "' points at '" + path
                            + "', outside " + namespace + "; the host drops it");
                }
                if (path.contains("lang=")) {
                    problems.add("alternate '" + code + "' of '" + url.loc() + "' writes its own '?lang='; "
                            + "alternates are paths and the host adds the parameter");
                }
            });
        }
        problems.addAll(inconsistentGroups(urls));
        return new SitemapEntries(urls, problems);
    }

    /**
     * Two entries whose translation groups overlap must declare the same group — they <em>are</em> the same
     * group, and a crawler handed both gets two answers to one question.
     */
    private static List<String> inconsistentGroups(List<SitemapUrl> urls) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            SitemapUrl a = urls.get(i);
            if (a.alternates().isEmpty()) {
                continue;
            }
            for (int j = i + 1; j < urls.size(); j++) {
                SitemapUrl b = urls.get(j);
                if (b.alternates().isEmpty() || !overlaps(a, b) || a.alternates().equals(b.alternates())) {
                    continue;
                }
                problems.add("'" + a.loc() + "' and '" + b.loc() + "' are in one translation group but declare "
                        + "different alternates (" + a.alternates() + " vs " + b.alternates() + ")");
            }
        }
        return problems;
    }

    private static boolean overlaps(SitemapUrl a, SitemapUrl b) {
        Set<String> paths = new LinkedHashSet<>(a.alternates().values());
        return b.alternates().values().stream().anyMatch(paths::contains);
    }

    private boolean inNamespace(String path) {
        String root = namespace.substring(0, namespace.length() - 1);
        return path.equals(root) || path.startsWith(namespace);
    }

    /**
     * What a provider returned, and what is wrong with it.
     *
     * @param urls     the entries, in the provider's order
     * @param problems one line per finding, each naming what the host would do about it; empty is the
     *                 assertion worth writing
     * @since 0.12.0
     */
    public record SitemapEntries(List<SitemapUrl> urls, List<String> problems) {

        /** Copies both lists. */
        public SitemapEntries {
            urls = List.copyOf(urls);
            problems = List.copyOf(problems);
        }

        /**
         * The {@code loc} of every entry, for a readable assertion.
         *
         * @return the locations, in the provider's order
         */
        public List<String> locations() {
            List<String> locations = new ArrayList<>();
            urls.forEach(url -> locations.add(url.loc()));
            return List.copyOf(locations);
        }

        /**
         * The languages one entry is advertised in.
         *
         * @param loc the location to look up
         * @return its locale codes, sorted; empty for an entry with no translation group
         * @throws IllegalArgumentException if no entry has that {@code loc} — a typo in a test that would
         *                                  otherwise read as a passing assertion
         */
        public List<String> locales(String loc) {
            return entry(loc).alternates().keySet().stream().sorted().toList();
        }

        /**
         * The translation group one entry declares.
         *
         * @param loc the location to look up
         * @return locale code → path; empty for an entry with no translation group
         * @throws IllegalArgumentException if no entry has that {@code loc}
         */
        public Map<String, String> alternates(String loc) {
            return entry(loc).alternates();
        }

        private SitemapUrl entry(String loc) {
            Objects.requireNonNull(loc, "loc");
            Map<String, SitemapUrl> byLoc = new LinkedHashMap<>();
            urls.forEach(url -> byLoc.putIfAbsent(url.loc(), url));
            SitemapUrl url = byLoc.get(loc);
            if (url == null) {
                throw new IllegalArgumentException("no sitemap entry for '" + loc + "'. Got: " + byLoc.keySet());
            }
            return url;
        }
    }
}
