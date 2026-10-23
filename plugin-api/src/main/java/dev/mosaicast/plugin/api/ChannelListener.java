// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * Called when a user joins or leaves a channel — {@link Channels#onJoin(ChannelListener)},
 * {@link Channels#onLeave(ChannelListener)} (ARCHITECTURE §7.9).
 *
 * <p>Per <em>user</em>, not per tab: join fires on a user's first subscription to the channel, leave when
 * their last one ends — closed by the browser, refused, kicked, or ended by the host because access changed.
 * May run concurrently with handlers and timers.
 *
 * @since 0.20.0
 */
@FunctionalInterface
public interface ChannelListener {

    /**
     * Handles one join or leave.
     *
     * @param channel the channel; never {@code null}
     * @param caller  the user who joined or left; never {@code null}
     */
    void on(ChannelRef channel, Caller caller);
}
