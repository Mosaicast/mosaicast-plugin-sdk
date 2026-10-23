// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import java.util.Objects;

/**
 * Thrown by {@link FakeChannels#subscribe(dev.mosaicast.plugin.api.Caller, dev.mosaicast.plugin.api.ChannelRef)}
 * where the host would end a subscribe as {@code refused}.
 *
 * <p>A test-kit type with no counterpart in the contract: the host refuses asynchronously, in the browser's
 * status callback. Here a refusal is something to {@code assertThrows}, and {@link #reason()} says which rule
 * refused.
 *
 * @since 0.20.0
 */
public final class SubscriptionRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Which rule refused a subscribe. */
    public enum Reason {
        /** The caller's role is below {@code channels.subscribableBy}. */
        BELOW_FLOOR,
        /** The plugin registered no {@link dev.mosaicast.plugin.api.ChannelAuthorizer}. */
        NO_AUTHORIZER,
        /** The plugin's authorizer answered {@code false}. */
        AUTHORIZER
    }

    /** Which rule refused. */
    private final Reason reason;

    SubscriptionRefusedException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Which rule refused the subscribe.
     *
     * @return the reason; never {@code null}
     */
    public Reason reason() {
        return reason;
    }
}
