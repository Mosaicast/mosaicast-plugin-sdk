// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.time.Duration;
import java.time.Instant;

/**
 * The presentation layer of an episode — a read-through snapshot derived from the feed (ARCHITECTURE
 * §4.2).
 *
 * <p>This is <strong>not authoritative</strong>: it is overwritten on every fetch from the raw feed, so
 * a description change in the RSS propagates automatically and never lives in the DB as truth. It is the
 * same data the core UI shows (feed cards, detail header, player).
 *
 * <p><strong>Runtime/date here always come from the feed.</strong> Plugin-provided metrics (e.g. MAT
 * runtime, speaking shares) are non-authoritative, may be absent, and belong only inside that plugin's
 * own UI — never mixed into this snapshot (§4.2).
 *
 * <p><strong>{@code description} is untrusted third-party HTML.</strong> It is the show-notes block exactly
 * as the podcast host published it, and anyone who can edit that feed controls it. The host does not
 * sanitize it for you. Anything that writes description text into output — an {@link OgMeta} description,
 * a {@link SearchHit} excerpt, a notification, HTML of your own — should use {@link #descriptionText()},
 * which the host has already reduced to plain text. The frontend runs HTML through {@code ctx.sanitize}.
 *
 * <p><strong>{@code feed}, {@code season} and {@code episodeNo} are the exception to "not authoritative"</strong>
 * (since 0.17.0). They are not feed presentation but the episode's place in the site — the identity layer
 * ({@code EpisodeRef}, ARCHITECTURE §4.4) — which the host resolves when it hands the snapshot over and never
 * stores in it, so a feed refetch cannot move an episode to another season by overwriting a snapshot. They
 * ride on this record because a plugin reading episodes already holds one; build a season scope from them
 * with {@link #seasonScope()}, never by parsing {@code ctx.episodeLabels}.
 *
 * <p><strong>{@code phase} and {@code announceAt} are identity too</strong> (since 0.18.0), under the same
 * rule: the host adds them on read and never stores them here. The phase is derived from the
 * {@code EpisodeRef} status, the announcement instant and the clock, so the same episode can read
 * {@link EpisodePhase#PLANNED} on one call and {@link EpisodePhase#UPCOMING} on the next without anything
 * being written. A plugin backend sees every phase through {@link FeedAccess}; a visitor below podcaster
 * never sees a {@code PLANNED} one.
 *
 * @param title           the episode title from the feed; never {@code null}
 * @param description     the episode show notes as the feed published them — <strong>untrusted
 *                        HTML</strong>; never {@code null}, may be empty
 * @param audioUrl     the enclosure audio URL; {@code null} for a {@code PLANNED} episode with no audio yet
 * @param publishedAt  the publication timestamp; {@code null} for a {@code PLANNED} episode
 * @param duration     the declared runtime ({@code itunes:duration}/enclosure); {@code null} when the feed
 *                     declares none
 * @param imageUrl     the episode's own artwork ({@code itunes:image} on the item); {@code null} if the
 *                     episode declares none
 * @param feedImageUrl the feed/show cover ({@code itunes:image} on the channel); {@code null} if the feed
 *                     declares none
 * @param author       the episode author ({@code itunes:author}); {@code null} if the feed declares none
 * @param subtitle     a short episode subtitle ({@code itunes:subtitle}); {@code null} if the feed declares
 *                     none
 * @param descriptionText the same show notes as plain text — tags removed, entities decoded, whitespace
 *                        collapsed — computed by the host; never {@code null} (a {@code null} argument
 *                        becomes {@code ""}), empty when the feed has no description
 *                        (since 0.16.0)
 * @param feed         the public slug of the feed the episode belongs to — the id of its
 *                     {@link Scope#feed(String) feed scope}; {@code null} only on a snapshot built without
 *                     it (a host older than 0.17.0, or a test fixture on the shorter constructor) (since 0.17.0)
 * @param season       the season number ({@code itunes:season}, as the host recorded it on the episode);
 *                     {@code null} when the episode has none (since 0.17.0)
 * @param episodeNo    the episode number within its season ({@code itunes:episode}); {@code null} when the
 *                     episode has none — a numbered season may still hold an unnumbered prologue
 *                     (since 0.17.0)
 * @param phase        where the episode stands in its release, derived by the host on read; {@code null}
 *                     only on a snapshot built without it (a test fixture on a shorter constructor — a host
 *                     that loads a 0.18 plugin always sends it) (since 0.18.0)
 * @param announceAt   when a {@code PLANNED} episode is (or was) announced to everyone; {@code null} when it
 *                     has no scheduled announcement, and once it is {@code PUBLISHED} or {@code WITHDRAWN}
 *                     (since 0.18.0)
 */
public record DisplaySnapshot(
        String title,
        String description,
        String audioUrl,
        Instant publishedAt,
        Duration duration,
        String imageUrl,
        String feedImageUrl,
        String author,
        String subtitle,
        String descriptionText,
        String feed,
        Integer season,
        Integer episodeNo,
        EpisodePhase phase,
        Instant announceAt) {

    /** Normalises an absent plain-text description to {@code ""}, so it is never {@code null}. */
    public DisplaySnapshot {
        descriptionText = descriptionText == null ? "" : descriptionText;
    }

    /**
     * The 0.17 shape, without the release phase: {@link #phase()} and {@link #announceAt()} are {@code null}.
     *
     * <p>Kept, and not deprecated, for test fixtures that never look at the phase. The host always uses the
     * canonical constructor; a fixture for code that branches on the phase must too.
     *
     * @param title           see the canonical constructor
     * @param description     see the canonical constructor
     * @param audioUrl        see the canonical constructor
     * @param publishedAt     see the canonical constructor
     * @param duration        see the canonical constructor
     * @param imageUrl        see the canonical constructor
     * @param feedImageUrl    see the canonical constructor
     * @param author          see the canonical constructor
     * @param subtitle        see the canonical constructor
     * @param descriptionText see the canonical constructor
     * @param feed            see the canonical constructor
     * @param season          see the canonical constructor
     * @param episodeNo       see the canonical constructor
     * @since 0.18.0
     */
    public DisplaySnapshot(String title, String description, String audioUrl, Instant publishedAt,
                           Duration duration, String imageUrl, String feedImageUrl, String author,
                           String subtitle, String descriptionText, String feed, Integer season,
                           Integer episodeNo) {
        this(title, description, audioUrl, publishedAt, duration, imageUrl, feedImageUrl, author, subtitle,
                descriptionText, feed, season, episodeNo, null, null);
    }

    /**
     * The 0.16 shape, without the episode's place in the site: {@link #feed()}, {@link #season()},
     * {@link #episodeNo()}, {@link #phase()} and {@link #announceAt()} are {@code null}.
     *
     * <p>Kept, and not deprecated, for test fixtures that never look at seasons. The host always uses the
     * canonical constructor; a fixture for code that aggregates per season or feed must too.
     *
     * @param title           see the canonical constructor
     * @param description     see the canonical constructor
     * @param audioUrl        see the canonical constructor
     * @param publishedAt     see the canonical constructor
     * @param duration        see the canonical constructor
     * @param imageUrl        see the canonical constructor
     * @param feedImageUrl    see the canonical constructor
     * @param author          see the canonical constructor
     * @param subtitle        see the canonical constructor
     * @param descriptionText see the canonical constructor
     * @since 0.17.0
     */
    public DisplaySnapshot(String title, String description, String audioUrl, Instant publishedAt,
                           Duration duration, String imageUrl, String feedImageUrl, String author,
                           String subtitle, String descriptionText) {
        this(title, description, audioUrl, publishedAt, duration, imageUrl, feedImageUrl, author, subtitle,
                descriptionText, null, null, null, null, null);
    }

    /**
     * The pre-0.16.0 shape, without {@link #descriptionText()} — which is left <strong>empty</strong>.
     *
     * <p>Kept so test fixtures written against 0.15 still compile. The host always uses the canonical
     * constructor; a fixture that renders description text should too, or it tests against an empty string.
     *
     * @param title        see the canonical constructor
     * @param description  see the canonical constructor
     * @param audioUrl     see the canonical constructor
     * @param publishedAt  see the canonical constructor
     * @param duration     see the canonical constructor
     * @param imageUrl     see the canonical constructor
     * @param feedImageUrl see the canonical constructor
     * @param author       see the canonical constructor
     * @param subtitle     see the canonical constructor
     * @deprecated since 0.16.0 — pass {@code descriptionText} as the tenth argument
     */
    @Deprecated(since = "0.16.0", forRemoval = true)
    public DisplaySnapshot(String title, String description, String audioUrl, Instant publishedAt,
                           Duration duration, String imageUrl, String feedImageUrl, String author,
                           String subtitle) {
        this(title, description, audioUrl, publishedAt, duration, imageUrl, feedImageUrl, author, subtitle,
                "", null, null, null, null, null);
    }

    /**
     * The season scope this episode belongs to — {@code Scope.season(feed, season)} — or {@code null} when the
     * episode has no season number or the snapshot carries no feed.
     *
     * <p>What a plugin aggregating per season needs, e.g. to pass to {@link FeedAccess#episodesIn(Scope)} or to
     * key a per-season document. A derived convenience over {@link #feed()} and {@link #season()}.
     *
     * @return the season scope, or {@code null}
     * @since 0.17.0
     */
    public Scope seasonScope() {
        return feed == null || feed.isBlank() || season == null ? null : Scope.season(feed, season);
    }

    /**
     * The artwork to display for this episode: the episode's own {@link #imageUrl()} if present, otherwise
     * the {@link #feedImageUrl() feed cover}, otherwise {@code null}.
     *
     * <p>A derived convenience over the two stored fields — it holds no state of its own and, like the rest
     * of this snapshot, is non-authoritative (overwritten on every fetch). Use {@link #imageUrl()} or
     * {@link #feedImageUrl()} directly when you specifically need the episode- or feed-level value.
     *
     * @return the resolved artwork URL, or {@code null} when neither the episode nor the feed declares one
     */
    public String artwork() {
        return imageUrl != null ? imageUrl : feedImageUrl;
    }
}
