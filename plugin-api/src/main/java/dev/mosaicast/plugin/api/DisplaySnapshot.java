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
 * @param title        the episode title from the feed; never {@code null}
 * @param description  the episode description/show notes; never {@code null}, may be empty
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
        String subtitle) {

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
