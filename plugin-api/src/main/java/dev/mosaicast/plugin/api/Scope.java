// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Objects;

/**
 * Addresses a level of the site plus the concrete entity at that level (ARCHITECTURE §7.4).
 *
 * <p>A scope is a {@link ScopeType} and the id of the entity at that level. It is the addressing unit
 * for both {@link DocStore} entries and {@link FeedAccess} lookups.
 *
 * @param type the level; never {@code null}
 * @param id   the id of the entity at that level (e.g. the {@code EpisodeRef} ID for
 *             {@link ScopeType#EPISODE}); never {@code null}. For {@link ScopeType#SITE} the host uses a
 *             stable singleton id.
 */
public record Scope(ScopeType type, String id) {

    /**
     * Canonical constructor validating that neither component is {@code null}.
     */
    public Scope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }

    /** Convenience factory for a {@link ScopeType#SITE} scope. */
    public static Scope site(String id) {
        return new Scope(ScopeType.SITE, id);
    }

    /** Convenience factory for a {@link ScopeType#FEED} scope. */
    public static Scope feed(String id) {
        return new Scope(ScopeType.FEED, id);
    }

    /** Convenience factory for a {@link ScopeType#SEASON} scope. */
    public static Scope season(String id) {
        return new Scope(ScopeType.SEASON, id);
    }

    /** Convenience factory for a {@link ScopeType#EPISODE} scope. */
    public static Scope episode(String id) {
        return new Scope(ScopeType.EPISODE, id);
    }
}
