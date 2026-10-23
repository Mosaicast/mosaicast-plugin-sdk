// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Set;
import java.util.UUID;

/**
 * One channel of this plugin, to publish events to whoever is subscribed right now (ARCHITECTURE §7.9).
 * Obtained from {@link Channels#channel(Scope, String)}; cheap, so ask for it where you need it.
 *
 * <p><strong>Events are never persisted.</strong> A publish reaches the subscriptions open at that moment,
 * in order per subscription; with nobody subscribed it is a no-op, and a later subscriber receives nothing
 * that went before. An event is a <em>notification of change</em>, not a source of truth: a client
 * subscribes, then fetches state through an action, then applies events, discarding any its own version
 * numbers say it has seen. When the host cannot resume a dropped connection, the client is told to reset
 * and fetches again.
 *
 * <p>The contract promises nothing about where subscribers are: {@link #publish(String, Object)} reaches them
 * wherever they are connected.
 *
 * @since 0.20.0
 */
public interface Channel {

    /**
     * The largest event payload, serialized as JSON, in bytes: {@value}.
     */
    int MAX_EVENT_BYTES = 8 * 1024;

    /**
     * The channel this handle publishes to.
     *
     * @return the reference; never {@code null}
     */
    ChannelRef ref();

    /**
     * Sends an event to every current subscriber.
     *
     * @param event the event name, matching {@link Actions#NAME_PATTERN}; never {@code null}
     * @param data  the payload, serialized to JSON; may be {@code null}
     * @throws IllegalArgumentException if the name does not match the grammar or the serialized data exceeds
     *                                  {@link #MAX_EVENT_BYTES} — a broken event is the backend's bug, found in
     *                                  its tests
     */
    void publish(String event, Object data);

    /**
     * Sends an event to one user's subscriptions on this channel only — per-user views (your hand, your turn)
     * on a channel others share. A no-op if that user is not subscribed: nothing reaches a browser that did
     * not ask and was not admitted.
     *
     * @param userId the recipient; never {@code null}
     * @param event  the event name, matching {@link Actions#NAME_PATTERN}; never {@code null}
     * @param data   the payload, serialized to JSON; may be {@code null}
     * @throws IllegalArgumentException as {@link #publish(String, Object)}
     */
    void publishTo(UUID userId, String event, Object data);

    /**
     * The distinct users subscribed right now — one entry per user however many tabs they have open.
     *
     * @return an immutable snapshot; never {@code null}
     */
    Set<UUID> subscribers();

    /**
     * Ends every subscription a user holds on this channel. Their browser sees {@code refused}, terminally;
     * it does not reconnect. Whether they may subscribe again is the {@link ChannelAuthorizer}'s answer.
     *
     * @param userId the user to remove; never {@code null}
     */
    void kick(UUID userId);
}
