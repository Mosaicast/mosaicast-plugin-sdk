// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * Where an episode stands in its release, as the host derives it at read time (ARCHITECTURE §4.3).
 *
 * <p>The stored lifecycle is the {@code EpisodeRef} status, {@code PLANNED | PUBLISHED | WITHDRAWN}, and it
 * does not change. A {@code PLANNED} episode may carry an announcement instant
 * ({@link DisplaySnapshot#announceAt()}); the phase combines the two with the current time, so it is never
 * stored and can move on its own while nothing is written: a planned episode becomes {@link #UPCOMING} the
 * moment its announcement passes.
 *
 * <p>A <em>write</em> that changes the phase — announcing, an {@code announceAt} edit, a release, a withdrawal,
 * a cancellation — is reported to {@link PluginContext#onEpisodePhaseChanged(java.util.function.BiConsumer)}
 * (since 0.19.0). The clock moving a phase is not.
 *
 * <p>Branch on the phase, not on the status, for anything a visitor sees: a {@code PLANNED} episode is either
 * hidden or announced, and only the phase says which.
 *
 * <p>The TypeScript half spells the same four values in lower case ({@code 'planned' | 'upcoming' |
 * 'released' | 'withdrawn'}).
 *
 * @since 0.18.0
 */
public enum EpisodePhase {

    /**
     * {@code PLANNED} and not announced yet: no announcement instant, or one still in the future. Only
     * podcasters and admins see it in the site; a plugin backend sees it through {@link FeedAccess}, because
     * that is when a plugin prepares content for it.
     */
    PLANNED,

    /**
     * {@code PLANNED} and announced: the announcement instant has passed. Everyone sees it, as an
     * "Upcoming" card with no audio.
     */
    UPCOMING,

    /**
     * {@code PUBLISHED}: the feed item has arrived and bound to the episode. This can happen before the
     * announcement instant — the RSS wins — so a planned episode may go straight from {@link #PLANNED} to
     * here. Everyone sees it.
     */
    RELEASED,

    /** {@code WITHDRAWN}: gone from the feed; visible as withdrawn episodes always were. */
    WITHDRAWN
}
