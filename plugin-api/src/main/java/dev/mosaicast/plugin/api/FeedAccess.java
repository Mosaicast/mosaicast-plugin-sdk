// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.List;

/**
 * Host-resolved access to episodes and their display snapshots (ARCHITECTURE §6.1).
 *
 * <p>Resolving "which episodes belong to this scope" is the <strong>host's job</strong>, not the
 * plugin's — a plugin never figures out on its own how a season is defined or which episodes a user may
 * see. The host also applies access/tier gating (§10) before handing the list over, so a plugin only
 * ever receives {@code EpisodeRef} IDs the current user is allowed to see.
 */
public interface FeedAccess {

    /**
     * Returns the identity-layer episode IDs that belong to the given scope, already filtered to what
     * the current user may see.
     *
     * @param scope the scope to resolve; never {@code null}
     * @return the {@code EpisodeRef} IDs in scope, in the host's canonical order; never {@code null},
     *         possibly empty
     */
    List<String> episodesIn(Scope scope);

    /**
     * Returns the presentation-layer display snapshot for an episode.
     *
     * <p>The snapshot (title, description, runtime, …) comes from the feed and is non-authoritative —
     * it is the same data the core UI shows. Plugin metrics must never be mixed into it (§4.2).
     *
     * @param refId the {@code EpisodeRef} ID; never {@code null}
     * @return the current display snapshot for the episode; never {@code null}
     */
    DisplaySnapshot display(String refId);
}
