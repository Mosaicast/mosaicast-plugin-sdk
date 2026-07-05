// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * The four levels at which a plugin can be scoped (ARCHITECTURE §6.1).
 *
 * <p>Every plugin slot and every storage entry is bound to exactly one of these levels.
 */
public enum ScopeType {

    /** The whole site (single podcast in v1). */
    SITE,

    /** One RSS/source feed. */
    FEED,

    /** All episodes of a feed with a given {@code itunes:season} (§4.4). */
    SEASON,

    /** A single episode, identified by its {@code EpisodeRef} ID. */
    EPISODE
}
