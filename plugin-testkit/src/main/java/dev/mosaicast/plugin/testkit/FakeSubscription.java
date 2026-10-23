// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.Caller;
import dev.mosaicast.plugin.api.ChannelRef;
import java.util.ArrayList;
import java.util.List;

/**
 * One subscription a test opened through {@link FakeChannels#subscribe(Caller, ChannelRef)} — one browser tab
 * of one user on one channel. Collects the events delivered to it while open.
 *
 * @since 0.20.0
 */
public final class FakeSubscription {

    private final FakeChannels channels;
    private final Caller caller;
    private final ChannelRef channel;
    private final List<ChannelEvent> events = new ArrayList<>();
    private boolean open = true;
    private boolean refused;

    FakeSubscription(FakeChannels channels, Caller caller, ChannelRef channel) {
        this.channels = channels;
        this.caller = caller;
        this.channel = channel;
    }

    /**
     * Who subscribed.
     *
     * @return the caller
     */
    public Caller caller() {
        return caller;
    }

    /**
     * The channel subscribed to.
     *
     * @return the channel
     */
    public ChannelRef channel() {
        return channel;
    }

    /**
     * What this subscription received, oldest first: every {@code publish} on its channel and every
     * {@code publishTo} its user, while it was open.
     *
     * @return the events; never {@code null}
     */
    public List<ChannelEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Whether it is still open.
     *
     * @return {@code false} once closed, kicked or ended by {@link FakeChannels#endAccess(java.util.UUID)}
     */
    public boolean isOpen() {
        return open;
    }

    /**
     * Whether it ended as {@code refused} — the plugin {@link dev.mosaicast.plugin.api.Channel#kick kicked} the
     * user — rather than closed.
     *
     * @return {@code true} after a kick
     */
    public boolean wasRefused() {
        return refused;
    }

    /** Closes it, as the browser does on {@code ChannelSubscription.close()} or a closed tab. Idempotent. */
    public void close() {
        if (open) {
            open = false;
            channels.end(this);
        }
    }

    void refuse() {
        if (open) {
            refused = true;
            close();
        }
    }

    void receive(ChannelEvent event) {
        if (open) {
            events.add(event);
        }
    }
}
