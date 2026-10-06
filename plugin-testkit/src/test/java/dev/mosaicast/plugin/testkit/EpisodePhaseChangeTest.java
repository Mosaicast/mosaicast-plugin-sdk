// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.DisplaySnapshot;
import dev.mosaicast.plugin.api.EpisodePhase;
import dev.mosaicast.plugin.api.PluginBackend;
import dev.mosaicast.plugin.api.Scope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/** The phase-change hook (0.19.0): the direction the release hook does not cover. */
class EpisodePhaseChangeTest {

    private static final Instant FRIDAY = Instant.parse("2026-10-09T18:00:00Z");

    private static DisplaySnapshot upcoming() {
        return new DisplaySnapshot("Episode 7", "", null, null, null, null, null, null, null, "",
                "the-sample-cast", 1, 7, EpisodePhase.UPCOMING, FRIDAY);
    }

    /** Publishes an anonymous-readable index of every visible episode, and republishes it when one hides. */
    private static PluginBackend indexer(FakeFeedAccess feeds) {
        return ctx -> {
            Runnable publish = () -> ctx.store().put(Scope.site(), "index", feeds.episodesIn(Scope.site()).stream()
                    .filter(slug -> feeds.display(slug).phase() == EpisodePhase.UPCOMING
                            || feeds.display(slug).phase() == EpisodePhase.RELEASED)
                    .toList());
            ctx.onEpisodePhaseChanged((slug, phase) -> publish.run());
            ctx.onSchedule(Duration.ofMinutes(30), publish);
        };
    }

    @Test
    void anEpisodeGoingQuietIsDroppedFromWhatThePluginPublished() {
        FakeFeedAccess feeds = new FakeFeedAccess(Map.of(Scope.site(), List.of("ep-7"))).withDisplay("ep-7", upcoming());
        FakePluginContext ctx = new FakePluginContext(new InMemoryDocStore(), new MapPluginConfig(), feeds, null);
        indexer(feeds).register(ctx);
        assertEquals(List.of("ep-7"), ctx.store().get(Scope.site(), "index", List.class).orElseThrow());

        // A podcaster moved announceAt into the future: PLANNED again, and the host says so at once.
        feeds.withPhase("ep-7", EpisodePhase.PLANNED);
        ctx.fireEpisodePhaseChanged("ep-7", EpisodePhase.PLANNED);

        assertEquals(List.of(), ctx.store().get(Scope.site(), "index", List.class).orElseThrow());
    }

    @Test
    void aReleaseCallsReleaseListenersFirstThenPhaseListenersWithReleased() {
        FakePluginContext ctx = new FakePluginContext();
        List<String> heard = new ArrayList<>();
        ctx.onEpisodePhaseChanged((slug, phase) -> heard.add("phase:" + slug + ":" + phase));
        ctx.onEpisodeReleased(slug -> heard.add("released:" + slug));

        ctx.fireEpisodeReleased("ep-7");

        assertEquals(List.of("released:ep-7", "phase:ep-7:RELEASED"), heard);
    }

    @Test
    void aPhaseChangeAloneDoesNotCallReleaseListeners() {
        // RELEASED from WITHDRAWN is an episode coming back, not a planned one being released.
        FakePluginContext ctx = new FakePluginContext();
        List<String> released = new ArrayList<>();
        ctx.onEpisodeReleased(released::add);

        ctx.fireEpisodePhaseChanged("ep-7", EpisodePhase.RELEASED);

        assertEquals(List.of(), released);
    }

    @Test
    void aCancelledEpisodeArrivesWithANullPhase() {
        FakePluginContext ctx = new FakePluginContext();
        List<EpisodePhase> phases = new ArrayList<>();
        ctx.onEpisodePhaseChanged((slug, phase) -> phases.add(phase));

        ctx.fireEpisodePhaseChanged("ep-7", null);

        assertEquals(1, ctx.episodePhaseListenerCount());
        assertEquals(java.util.Arrays.asList((EpisodePhase) null), phases);
    }

    @Test
    void aThrowingPhaseListenerIsLoggedAndTheNextOneStillRuns() {
        FakePluginContext ctx = new FakePluginContext();
        List<String> heard = new ArrayList<>();
        ctx.onEpisodePhaseChanged((slug, phase) -> {
            throw new IllegalStateException("boom");
        });
        ctx.onEpisodePhaseChanged((slug, phase) -> heard.add(slug));

        ctx.fireEpisodePhaseChanged("ep-7", EpisodePhase.WITHDRAWN);

        assertEquals(List.of("ep-7"), heard);
        assertEquals(1, ctx.logger().events(Level.ERROR).size());
        assertTrue(ctx.logger().events(Level.ERROR).get(0).error() instanceof IllegalStateException);
    }

    @Test
    void nullsAreRefusedExceptForTheCancelledPhase() {
        assertThrows(NullPointerException.class, () -> new FakePluginContext().onEpisodePhaseChanged(null));
        assertThrows(NullPointerException.class, () -> new FakePluginContext().fireEpisodePhaseChanged(null, null));
    }
}
