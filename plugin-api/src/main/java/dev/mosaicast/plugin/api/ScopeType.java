// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * The levels at which a plugin can be scoped (ARCHITECTURE §6.1).
 *
 * <p>Every storage entry is bound to exactly one of these levels. The first four also address a level of
 * the site and can carry a plugin <em>slot</em>; {@link #USER} cannot — it is a storage partition only
 * (there is no user page to mount a slot on).
 */
public enum ScopeType {

    /** The whole site (single podcast in v1). */
    SITE,

    /** One RSS/source feed. */
    FEED,

    /** All episodes of a feed with a given {@code itunes:season} (§4.4). */
    SEASON,

    /** A single episode, identified by its {@code EpisodeRef} ID. */
    EPISODE,

    /**
     * The calling user's own private partition — <strong>host-owned</strong>.
     *
     * <p>Its id is always the sentinel {@link Scope#SELF_ID}; the host substitutes the authenticated
     * caller. A plugin cannot name another user's partition, and an anonymous caller has none. This is
     * where per-user data belongs — see {@link Scope#user()} and {@link DocStore}.
     *
     * <p>Backend-only code has no calling user, so a {@link DocStore} call with this scope throws
     * {@link UnsupportedOperationException}; aggregate across users with
     * {@link DocStore#queryAcrossUsers(String)} instead.
     *
     * @since 0.5.0
     */
    USER
}
