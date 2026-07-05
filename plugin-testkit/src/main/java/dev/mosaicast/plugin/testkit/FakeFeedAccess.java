// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.DisplaySnapshot;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.Scope;
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

    @Override
    public List<String> episodesIn(Scope scope) {
        Objects.requireNonNull(scope, "scope");
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
