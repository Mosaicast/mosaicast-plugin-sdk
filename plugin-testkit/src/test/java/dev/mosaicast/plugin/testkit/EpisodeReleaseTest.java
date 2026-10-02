// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/** The release hook and the phase, as a plugin's own tests drive them. */
class EpisodeReleaseTest {

    private static final Instant FRIDAY = Instant.parse("2026-10-09T18:00:00Z");

    private static DisplaySnapshot planned(Instant announceAt) {
        return new DisplaySnapshot("Episode 7", "", null, null, null, null, null, null, null, "",
                "the-sample-cast", 1, 7, EpisodePhase.PLANNED, announceAt);
    }

    /** Opens the bingo of a released episode, from the event or from the scheduled reconciliation. */
    private static final PluginBackend BINGO = ctx -> {
        ctx.onEpisodeReleased(slug -> ctx.store().put(Scope.episode(slug), "open", Boolean.TRUE));
        ctx.onSchedule(Duration.ofMinutes(15), () -> ctx.feeds().episodesIn(Scope.site()).stream()
                .filter(slug -> ctx.feeds().display(slug).phase() == EpisodePhase.RELEASED)
                .forEach(slug -> ctx.store().put(Scope.episode(slug), "open", Boolean.TRUE)));
    };

    @Test
    void aPlannedEpisodeIsVisibleToTheBackendWithItsPhase() {
        FakeFeedAccess feeds = new FakeFeedAccess(Map.of(Scope.site(), List.of("ep-7")))
                .withDisplay("ep-7", planned(FRIDAY));

        // No filtering by phase: a backend sees planned episodes, because it prepares content for them.
        assertEquals(List.of("ep-7"), feeds.episodesIn(Scope.site()));
        assertEquals(EpisodePhase.PLANNED, feeds.display("ep-7").phase());
        assertEquals(FRIDAY, feeds.display("ep-7").announceAt());
    }

    @Test
    void firingTheReleaseCallsTheListenerWithTheSlug() {
        FakeFeedAccess feeds = new FakeFeedAccess(Map.of(Scope.site(), List.of("ep-7")))
                .withDisplay("ep-7", planned(FRIDAY));
        FakePluginContext ctx = new FakePluginContext(new InMemoryDocStore(), new MapPluginConfig(), feeds, null);

        BINGO.register(ctx);
        assertEquals(1, ctx.episodeReleasedListenerCount());
        // The first scheduled tick ran at registration and found nothing released.
        assertEquals(Optional.empty(), ctx.store().get(Scope.episode("ep-7"), "open", Boolean.class));

        feeds.withPhase("ep-7", EpisodePhase.RELEASED);
        ctx.fireEpisodeReleased("ep-7");

        assertEquals(Optional.of(Boolean.TRUE), ctx.store().get(Scope.episode("ep-7"), "open", Boolean.class));
    }

    @Test
    void aMissedReleaseIsCaughtByTheReconciliation() {
        FakeFeedAccess feeds = new FakeFeedAccess(Map.of(Scope.site(), List.of("ep-7")))
                .withDisplay("ep-7", planned(null));
        FakePluginContext ctx = new FakePluginContext(new InMemoryDocStore(), new MapPluginConfig(), feeds, null);
        BINGO.register(ctx);

        // The plugin was restarting when the episode bound: no event, only the phase.
        feeds.withPhase("ep-7", EpisodePhase.RELEASED);
        ctx.runScheduled();

        assertEquals(Optional.of(Boolean.TRUE), ctx.store().get(Scope.episode("ep-7"), "open", Boolean.class));
    }

    @Test
    void aThrowingListenerIsLoggedAndTheNextOneStillRuns() {
        FakePluginContext ctx = new FakePluginContext();
        List<String> heard = new ArrayList<>();
        ctx.onEpisodeReleased(slug -> {
            throw new IllegalStateException("boom");
        });
        ctx.onEpisodeReleased(heard::add);

        ctx.fireEpisodeReleased("ep-7");

        assertEquals(List.of("ep-7"), heard);
        assertEquals(1, ctx.logger().events(Level.ERROR).size());
        assertTrue(ctx.logger().events(Level.ERROR).get(0).error() instanceof IllegalStateException);
    }

    @Test
    void leavingThePlannedPhasesDropsTheAnnouncement() {
        FakeFeedAccess feeds = new FakeFeedAccess(Map.of()).withDisplay("ep-7", planned(FRIDAY));

        feeds.withPhase("ep-7", EpisodePhase.UPCOMING);
        assertEquals(FRIDAY, feeds.display("ep-7").announceAt());

        feeds.withPhase("ep-7", EpisodePhase.RELEASED);
        assertEquals(EpisodePhase.RELEASED, feeds.display("ep-7").phase());
        assertNull(feeds.display("ep-7").announceAt());
        assertEquals("Episode 7", feeds.display("ep-7").title());
    }

    @Test
    void aPhaseNeedsARegisteredSnapshot() {
        FakeFeedAccess feeds = new FakeFeedAccess(Map.of());

        assertThrows(IllegalArgumentException.class, () -> feeds.withPhase("nope", EpisodePhase.RELEASED));
        assertThrows(NullPointerException.class, () -> new FakePluginContext().onEpisodeReleased(null));
    }
}
