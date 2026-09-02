// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests the per-page language added in 0.12.0: {@link OgMeta#locale()} and
 * {@link SitemapUrl#alternates()}.
 */
class ShareMetadataTest {

    @Test
    void ogMetaWithoutALocaleLeavesTheLanguageToTheHost() {
        OgMeta meta = new OgMeta("Kraken", "A cephalopod", null);

        // The pre-0.12.0 shape still compiles, and still means "whatever the host resolved".
        assertNull(meta.locale());
    }

    @Test
    void ogMetaNormalisesTheLocaleAndTreatsBlankAsAbsent() {
        assertEquals("pt-br", new OgMeta("t", "d", null, " PT-BR ").locale());
        assertNull(new OgMeta("t", "d", null, "   ").locale());
    }

    @Test
    void ogMetaStillRequiresItsText() {
        assertThrows(NullPointerException.class, () -> new OgMeta(null, "d", null, "de"));
        assertThrows(NullPointerException.class, () -> new OgMeta("t", null, null, "de"));
    }

    @Test
    void sitemapUrlWithoutAlternatesIsWhatTheHostAlreadyAssumed() {
        SitemapUrl url = new SitemapUrl("/p/wiki/changelog", Instant.EPOCH);

        assertTrue(url.alternates().isEmpty());
        assertTrue(new SitemapUrl("/p/wiki/changelog", null, null).alternates().isEmpty());
    }

    @Test
    void alternatesKeepTheirOrderAndAreUnmodifiable() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("en", "/p/wiki/article");
        declared.put("de", "/p/wiki/artikel");
        SitemapUrl url = new SitemapUrl("/p/wiki/article", null, declared);

        assertEquals(List.of("en", "de"), List.copyOf(url.alternates().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> url.alternates().put("fr", "/p/wiki/x"));

        // A defensive copy: mutating what was passed in cannot change the record.
        declared.put("fr", "/p/wiki/fr");
        assertEquals(2, url.alternates().size());
    }

    @Test
    void alternatesMustNameTheLanguageLocItselfIsWrittenIn() {
        // Without an entry pointing at loc, nothing says what /p/wiki/article is written in — and the
        // host will not guess (§6.6).
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SitemapUrl("/p/wiki/article", null, Map.of("de", "/p/wiki/artikel")));
        assertTrue(e.getMessage().contains("/p/wiki/article"));
    }

    @Test
    void alternatesRejectEmptyCodesEmptyPathsAndRepeatedLanguages() {
        assertThrows(IllegalArgumentException.class,
                () -> new SitemapUrl("/p/wiki/a", null, Map.of(" ", "/p/wiki/a")));
        assertThrows(IllegalArgumentException.class,
                () -> new SitemapUrl("/p/wiki/a", null, Map.of("en", "  ")));

        Map<String, String> twice = new LinkedHashMap<>();
        twice.put("en", "/p/wiki/a");
        twice.put("EN", "/p/wiki/b");
        assertThrows(IllegalArgumentException.class, () -> new SitemapUrl("/p/wiki/a", null, twice));
    }

    @Test
    void localeCodesAreNormalisedLikeEverywhereElse() {
        SitemapUrl url = new SitemapUrl("/p/wiki/article", null, Map.of(" EN ", " /p/wiki/article "));

        assertEquals(Map.of("en", "/p/wiki/article"), url.alternates());
    }

    @Test
    void onePathPerLanguageIsAMapWithOneDistinctValue() {
        Map<String, String> group = new LinkedHashMap<>();
        group.put("en", "/p/wiki/glossary/kraken");
        group.put("de", "/p/wiki/glossary/kraken");
        SitemapUrl url = new SitemapUrl("/p/wiki/glossary/kraken", null, group);

        assertEquals(2, url.alternates().size());
    }
}
