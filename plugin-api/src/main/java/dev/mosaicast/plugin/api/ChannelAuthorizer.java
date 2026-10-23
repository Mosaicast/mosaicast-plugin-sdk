// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * Decides whether a caller may subscribe to a channel — where membership lives, since the host cannot know
 * who is in a game (ARCHITECTURE §7.9). Registered with {@link Channels#authorize(ChannelAuthorizer)}.
 *
 * <p>Runs after the host's own checks — signed in, {@code subscribableBy}, the scope visible to the caller,
 * the per-user subscription cap — and only then. A {@code false} ends the subscribe as {@code refused} in
 * the browser, terminally. It may run concurrently with handlers and timers, so read shared state safely,
 * and keep it quick: it sits on the path of every subscribe.
 *
 * @since 0.20.0
 */
@FunctionalInterface
public interface ChannelAuthorizer {

    /**
     * Whether {@code caller} may subscribe to {@code channel}.
     *
     * @param caller  the host-resolved subscriber; never {@code null}
     * @param channel the channel asked for; never {@code null}
     * @return {@code true} to admit the subscription
     */
    boolean allows(Caller caller, ChannelRef channel);
}
