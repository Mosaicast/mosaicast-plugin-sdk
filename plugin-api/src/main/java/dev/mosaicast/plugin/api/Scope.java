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
 *             {@link ScopeType#EPISODE}); never {@code null}. For {@link ScopeType#SITE} it is always
 *             {@link #SITE_ID} — see {@link #site()}.
 */
public record Scope(ScopeType type, String id) {

    /**
     * The id of the one and only {@link ScopeType#SITE} scope: {@value}.
     *
     * <p>There is a single site, so its scope is a singleton. The host normalizes every {@code SITE}
     * scope to this id, and it is what appears in the frontend's data path — {@code …/data/site/main/{key}}
     * addresses exactly the document a backend writes with {@code store().put(Scope.site(), key, value)}.
     */
    public static final String SITE_ID = "main";

    /**
     * Canonical constructor: validates that neither component is {@code null} and normalizes the
     * {@link ScopeType#SITE} scope to its singleton {@link #SITE_ID}, exactly as the host does — so
     * {@code new Scope(SITE, anything).id()} is {@code "main"} and every site scope is {@code equals} to
     * every other.
     */
    public Scope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (type == ScopeType.SITE) {
            id = SITE_ID;
        }
    }

    /**
     * The {@link ScopeType#SITE} scope — the singleton, with id {@link #SITE_ID}.
     *
     * @return the site scope
     */
    public static Scope site() {
        return new Scope(ScopeType.SITE, SITE_ID);
    }

    /**
     * Convenience factory for a {@link ScopeType#SITE} scope.
     *
     * @param id ignored — the site scope is a singleton, so the result is always {@link #site()}
     * @return the site scope
     * @deprecated the site scope takes no id; use {@link #site()}.
     */
    @Deprecated(since = "0.3.0", forRemoval = true)
    public static Scope site(String id) {
        return site();
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
