// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * The role of a user, used for slot visibility and permission checks (ARCHITECTURE §8.5).
 *
 * <p>Anonymous visitors have no role ({@code null} user); everyone else carries exactly one of these.
 * Ordering is from most to least privileged.
 */
public enum Role {

    /** Site configuration, users, plugin activation. */
    ADMIN,

    /** Bingos, wiki, episodes, feeds/sources, planned episodes. */
    PODCASTER,

    /** Fills in and views plugin content. */
    FAN
}
