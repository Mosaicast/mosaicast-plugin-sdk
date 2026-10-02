// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.DisplaySnapshot;
import dev.mosaicast.plugin.api.EpisodePhase;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link FeedAccess} backed by fixed maps, for testing plugins against a known episode layout
 * (ARCHITECTURE §13.5).
 *
 * <p>The scope→episodes map is supplied at construction; per-episode {@link DisplaySnapshot}s are
 * registered with {@link #withDisplay(String, DisplaySnapshot)}. Not thread-safe.
 *
 * <p>Nothing is filtered by phase, because the host does not filter a backend's view either: a
 * {@link EpisodePhase#PLANNED} episode in the map is handed over like any other.
 */
public final class FakeFeedAccess implements FeedAccess {

    private final Map<Scope, List<String>> episodes;
    private final Map<String, DisplaySnapshot> displays = new HashMap<>();

    /**
     * Creates a feed access over the given scope→episode-ids mapping.
     *
     * @param episodes which episode ids each scope resolves to; copied defensively, never {@code null}
     */
    public FakeFeedAccess(Map<Scope, List<String>> episodes) {
        Objects.requireNonNull(episodes, "episodes");
        this.episodes = new HashMap<>(episodes);
    }

    /**
     * Registers the display snapshot returned by {@link #display(String)} for an episode id.
     *
     * @param refId    the episode id; never {@code null}
     * @param snapshot the snapshot to return; never {@code null}
     * @return this instance, for chaining
     */
    public FakeFeedAccess withDisplay(String refId, DisplaySnapshot snapshot) {
        displays.put(Objects.requireNonNull(refId, "refId"), Objects.requireNonNull(snapshot, "snapshot"));
        return this;
    }

    /**
     * Moves a registered episode to another release phase, keeping the rest of its snapshot — the state change
     * a test needs around {@link FakePluginContext#fireEpisodeReleased(String)}.
     *
     * <p>Leaving {@link EpisodePhase#PLANNED} or {@link EpisodePhase#UPCOMING} clears
     * {@link DisplaySnapshot#announceAt()}, as the host does: only a planned episode carries one. Set an
     * announcement by registering a snapshot with {@link #withDisplay(String, DisplaySnapshot)}.
     *
     * @param refId the episode id; must already have a snapshot registered
     * @param phase the phase it is in now; never {@code null}
     * @return this instance, for chaining
     * @throws IllegalArgumentException if no snapshot is registered for {@code refId}
     * @since 0.18.0
     */
    public FakeFeedAccess withPhase(String refId, EpisodePhase phase) {
        Objects.requireNonNull(phase, "phase");
        DisplaySnapshot s = display(refId);
        boolean planned = phase == EpisodePhase.PLANNED || phase == EpisodePhase.UPCOMING;
        displays.put(refId, new DisplaySnapshot(s.title(), s.description(), s.audioUrl(), s.publishedAt(),
                s.duration(), s.imageUrl(), s.feedImageUrl(), s.author(), s.subtitle(), s.descriptionText(),
                s.feed(), s.season(), s.episodeNo(), phase, planned ? s.announceAt() : null));
        return this;
    }

    @Override
    public List<String> episodesIn(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        if (scope.type() == ScopeType.USER) {
            return List.of();   // no episode belongs to a person — the host answers the same way
        }
        return episodes.getOrDefault(scope, List.of());
    }

    @Override
    public DisplaySnapshot display(String refId) {
        Objects.requireNonNull(refId, "refId");
        DisplaySnapshot snapshot = displays.get(refId);
        if (snapshot == null) {
            throw new IllegalArgumentException("No display snapshot registered for episode: " + refId);
        }
        return snapshot;
    }
}
