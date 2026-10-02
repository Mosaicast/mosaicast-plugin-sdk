// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DisplaySnapshotTest {

    private static DisplaySnapshot placed(String feed, Integer season, Integer episodeNo) {
        return new DisplaySnapshot("t", "", null, null, null, null, null, null, null, "", feed, season, episodeNo);
    }

    @Test
    void anEpisodeKnowsTheSeasonScopeItBelongsTo() {
        // What the stats plugin could not build before 0.17.0: the id lived only in a display label.
        DisplaySnapshot snapshot = placed("the-sample-cast", 5, 22);

        assertEquals(new Scope(ScopeType.SEASON, "the-sample-cast:5"), snapshot.seasonScope());
        assertEquals(22, snapshot.episodeNo());
    }

    @Test
    void anUnnumberedEpisodeHasNoSeasonScopeButAPrologueInANumberedSeasonDoes() {
        assertNull(placed("the-sample-cast", null, null).seasonScope());
        // itunes:season without itunes:episode — the case a display label drops the season for.
        assertEquals(Scope.season("the-sample-cast", 5), placed("the-sample-cast", 5, null).seasonScope());
        assertNull(placed(null, 5, 1).seasonScope());
    }

    @Test
    void theShorterConstructorsLeaveThePlacementAbsent() {
        DisplaySnapshot snapshot = new DisplaySnapshot("t", "d", null, null, null, null, null, null, null, "d");

        assertNull(snapshot.feed());
        assertNull(snapshot.season());
        assertNull(snapshot.episodeNo());
    }

    @Test
    void aSeasonScopeIsBuiltFromItsTwoPartsAndRefusesNonsense() {
        assertEquals(Scope.season("feed:with:colons:3"), Scope.season("feed:with:colons", 3));
        assertThrows(IllegalArgumentException.class, () -> Scope.season(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> Scope.season("feed", -1));
    }

    @Test
    void aPlannedEpisodeCarriesItsPhaseAndAnnouncementWhileTheShorterShapesLeaveThemAbsent() {
        Instant at = Instant.parse("2026-10-09T18:00:00Z");
        DisplaySnapshot planned = new DisplaySnapshot("t", "", null, null, null, null, null, null, null, "",
                "the-sample-cast", 6, 1, EpisodePhase.PLANNED, at);

        assertEquals(EpisodePhase.PLANNED, planned.phase());
        assertEquals(at, planned.announceAt());

        DisplaySnapshot placedOnly = placed("the-sample-cast", 6, 1);
        assertNull(placedOnly.phase());
        assertNull(placedOnly.announceAt());
        assertNull(new DisplaySnapshot("t", "d", null, null, null, null, null, null, null, "d").phase());
    }

    @Test
    void theReleaseHookIsANoOpByDefaultSoExistingDoublesKeepCompiling() {
        // A context of a plugin's own that predates 0.18 implements nothing new and still compiles.
        List<String> heard = new ArrayList<>();
        PluginContext old = stubContext();

        assertDoesNotThrow(() -> old.onEpisodeReleased(heard::add));
        assertTrue(heard.isEmpty());
        assertThrows(NullPointerException.class, () -> old.onEpisodeReleased((Consumer<String>) null));
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public DocStore store() { return null; }
            @Override public SchemaStore schema() { return null; }
            @Override public PluginBlobs blobs() { return null; }
            @Override public Tags tags() { return null; }
            @Override public Users users() { return null; }
            @Override public Notifier notifier() { return null; }
            @Override public CrossUserStore allUsers() { return null; }
            @Override public Locales locales() { return null; }
            @Override public Translation translation() { return null; }
            @Override public PluginConfig config() { return null; }
            @Override public FeedAccess feeds() { return null; }
            @Override public org.slf4j.Logger logger() { return null; }
            @Override public void onSchedule(Supplier<java.time.Duration> every, Runnable task) { }
        };
    }
}
