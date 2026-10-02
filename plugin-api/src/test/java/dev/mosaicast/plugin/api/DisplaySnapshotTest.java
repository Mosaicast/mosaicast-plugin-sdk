// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
