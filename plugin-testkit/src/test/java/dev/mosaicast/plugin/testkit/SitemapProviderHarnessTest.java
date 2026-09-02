// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.SitemapProvider;
import dev.mosaicast.plugin.api.SitemapUrl;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests {@link SitemapProviderHarness} — the checks the host performs silently. */
class SitemapProviderHarnessTest {

    private static final Map<String, String> ARTICLE_GROUP = group("en", "/p/wiki/article", "de", "/p/wiki/artikel");

    @Test
    void aWellFormedSitemapHasNoProblems() {
        var entries = harness(
                new SitemapUrl("/p/wiki/article", Instant.EPOCH, ARTICLE_GROUP),
                new SitemapUrl("/p/wiki/artikel", Instant.EPOCH, ARTICLE_GROUP),
                new SitemapUrl("/p/wiki/changelog", Instant.EPOCH)).collect();

        assertTrue(entries.problems().isEmpty(), () -> entries.problems().toString());
        assertEquals(List.of("/p/wiki/article", "/p/wiki/artikel", "/p/wiki/changelog"), entries.locations());
        assertEquals(List.of("de", "en"), entries.locales("/p/wiki/article"));
        assertEquals(List.of(), entries.locales("/p/wiki/changelog"));
    }

    @Test
    void thePluginRootItselfIsInsideTheNamespace() {
        var entries = harness(new SitemapUrl("/p/wiki", null)).collect();

        assertTrue(entries.problems().isEmpty(), () -> entries.problems().toString());
    }

    @Test
    void aLocOutsideTheNamespaceIsReportedBecauseTheHostDropsIt() {
        var entries = harness(new SitemapUrl("/episodes/kraken", null)).collect();

        assertEquals(1, entries.problems().size());
        assertTrue(entries.problems().get(0).contains("/episodes/kraken"));
    }

    @Test
    void anAlternateOutsideTheNamespaceIsTheOneWorthCatching() {
        // Naming paths is what makes alternates expressive; it is also the only way a plugin could aim a
        // translation claim at a core URL, so the host confines them exactly as it confines loc.
        var entries = harness(new SitemapUrl("/p/wiki/article", null,
                group("en", "/p/wiki/article", "de", "/impressum"))).collect();

        assertEquals(1, entries.problems().size());
        assertTrue(entries.problems().get(0).contains("/impressum"));
    }

    @Test
    void writingYourOwnLangParameterIsReported() {
        var entries = harness(new SitemapUrl("/p/wiki/article", null,
                group("en", "/p/wiki/article", "de", "/p/wiki/article?lang=de"))).collect();

        assertEquals(1, entries.problems().size());
        assertTrue(entries.problems().get(0).contains("lang="));
    }

    @Test
    void oneGroupDeclaredTwoWaysIsReported() {
        // What a half-updated slug map looks like: /artikel still points the group at the old English path.
        var entries = harness(
                new SitemapUrl("/p/wiki/article", null, ARTICLE_GROUP),
                new SitemapUrl("/p/wiki/artikel", null,
                        group("en", "/p/wiki/old-article", "de", "/p/wiki/artikel"))).collect();

        assertEquals(1, entries.problems().size());
        assertTrue(entries.problems().get(0).contains("different alternates"));
    }

    @Test
    void unrelatedGroupsAreNotConfusedWithEachOther() {
        var entries = harness(
                new SitemapUrl("/p/wiki/article", null, ARTICLE_GROUP),
                new SitemapUrl("/p/wiki/kraken", null, group("en", "/p/wiki/kraken", "de", "/p/wiki/krake")))
                .collect();

        assertTrue(entries.problems().isEmpty(), () -> entries.problems().toString());
    }

    @Test
    void aDuplicatedLocationIsReported() {
        var entries = harness(
                new SitemapUrl("/p/wiki/changelog", Instant.EPOCH),
                new SitemapUrl("/p/wiki/changelog", null)).collect();

        assertEquals(1, entries.problems().size());
        assertTrue(entries.problems().get(0).contains("twice"));
    }

    @Test
    void askingAboutALocationThatWasNeverReturnedFailsLoudly() {
        var entries = harness(new SitemapUrl("/p/wiki/changelog", null)).collect();

        assertThrows(IllegalArgumentException.class, () -> entries.locales("/p/wiki/typo"));
    }

    private static SitemapProviderHarness harness(SitemapUrl... urls) {
        SitemapProvider provider = () -> List.of(urls);
        return new SitemapProviderHarness("wiki", provider);
    }

    private static Map<String, String> group(String firstCode, String firstPath, String secondCode,
            String secondPath) {
        Map<String, String> group = new LinkedHashMap<>();
        group.put(firstCode, firstPath);
        group.put(secondCode, secondPath);
        return group;
    }
}
