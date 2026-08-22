// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.TagInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests {@link FakeTags} — in particular the refusals, which are the reason it is not a permissive stub. */
class FakeTagsTest {

    @Test
    void canonicalisesTheWayTheHostDoes() {
        FakeTags tags = new FakeTags();

        tags.tagSubject("page:kraken", "  Maritime   Lore ");

        assertEquals(List.of("maritime lore"), tags.tagsOnSubject("page:kraken"));
        // Any spelling reaches the same tag — the point of a shared vocabulary.
        assertEquals(List.of("page:kraken"), tags.subjectsWith("MARITIME LORE"));
    }

    @Test
    void keepsTheDisplayLabelFromFirstUse() {
        FakeTags tags = new FakeTags();

        tags.tagSubject("page:a", "Maritime");
        tags.tagSubject("page:b", "maritime");

        TagInfo info = tags.all().get(0);
        assertEquals("maritime", info.tag());
        assertEquals("Maritime", info.label());
        assertEquals(2, info.subjects());
    }

    @Test
    void refusesEpisodeWritesUnlessTheManifestDeclaredThem() {
        FakeTags tags = new FakeTags();

        assertThrows(UnsupportedOperationException.class, () -> tags.tagEpisode("kraken", "lore"));
        assertThrows(UnsupportedOperationException.class, () -> tags.untagEpisode("kraken", "lore"));

        FakeTags allowed = new FakeTags().withEpisodeWrites();
        allowed.tagEpisode("kraken", "lore");
        assertEquals(List.of("lore"), allowed.tagsOn("kraken"));
    }

    @Test
    void cannotRemoveAnotherWritersAssignment() {
        FakeTags tags = new FakeTags().withEpisodeWrites().withFeedTag("kraken", "lore");
        tags.tagEpisode("kraken", "lore");

        tags.untagEpisode("kraken", "lore");

        // This plugin's row is gone; the feed's stays, so the episode still carries the tag.
        assertEquals(List.of("lore"), tags.tagsOn("kraken"));
        assertEquals(List.of("kraken"), tags.episodesWith("lore"));
    }

    @Test
    void aTagStopsExistingWhenNothingCarriesIt() {
        FakeTags tags = new FakeTags();
        tags.tagSubject("page:a", "lore");
        assertEquals(1, tags.all().size());

        tags.untagSubject("page:a", "lore");

        // A plugin never deletes from the vocabulary — the tag goes because nothing carries it.
        assertTrue(tags.all().isEmpty());
    }

    @Test
    void countsEpisodesSiteWideAndSubjectsPerPlugin() {
        FakeTags tags = new FakeTags()
                .withFeedTag("kraken", "lore")
                .withFeedTag("lighthouse", "lore");
        tags.tagSubject("page:kraken", "lore");

        assertEquals(List.of(new TagInfo("lore", "lore", 2, 1)), tags.all());
    }

    @Test
    void similarToRanksCoOccurrenceAndNeverReturnsTheTagItself() {
        FakeTags tags = new FakeTags()
                .withFeedTag("kraken", "lore")
                .withFeedTag("kraken", "maritime")
                .withFeedTag("lighthouse", "lore")
                .withFeedTag("lighthouse", "maritime")
                .withFeedTag("lighthouse", "ghosts");

        List<String> similar = tags.similarTo("lore", 5).stream().map(TagInfo::tag).toList();

        assertEquals(List.of("maritime", "ghosts"), similar);
    }

    @Test
    void writesAreIdempotentAndRemovalsForgiving() {
        FakeTags tags = new FakeTags().withEpisodeWrites();

        tags.tagSubject("page:a", "lore");
        tags.tagSubject("page:a", "LORE");
        tags.tagEpisode("kraken", "lore");
        tags.tagEpisode("kraken", "lore");

        assertEquals(List.of("lore"), tags.tagsOnSubject("page:a"));
        assertEquals(List.of("kraken"), tags.episodesWith("lore"));

        tags.untagSubject("page:unknown", "lore");     // no such subject — resolves rather than throwing
        tags.untagEpisode("unknown", "lore");
    }

    @Test
    void refusesABlankTag() {
        FakeTags tags = new FakeTags();
        assertThrows(IllegalArgumentException.class, () -> tags.tagSubject("page:a", "   "));
        assertThrows(IllegalArgumentException.class, () -> tags.tagSubject("", "lore"));
    }
}
