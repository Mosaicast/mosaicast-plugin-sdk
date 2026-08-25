// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * A translation the host would not or could not perform (ARCHITECTURE §12.7).
 *
 * <p>Checked, deliberately. Every other failure in this API is either a programming error or a value a plugin
 * can ignore; this one is a routine, expected outcome of asking somebody else's service for something — the
 * provider is down, the site is over its rate limit, the admin removed it an hour ago. A plugin that has not
 * decided what to do about that has a bug, and the compiler is the cheapest place to find out.
 *
 * <p>The message is safe to log and never carries the provider's response body — an upstream error body can
 * contain the credential that was sent with the request.
 *
 * @since 0.10.0
 */
public class TranslationException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why the call did not happen, or did not work. */
    public enum Reason {
        /** The site admin has selected no translation provider. Check {@link Translation#available()} first. */
        NO_PROVIDER,
        /** A provider is selected but is missing a setting it needs — an operator has to finish configuring it. */
        MISCONFIGURED,
        /** The site is over the provider's rate limit. Retrying later may work. */
        RATE_LIMITED,
        /** The host is at its concurrency limit right now. Retrying shortly may work. */
        BUSY,
        /** The provider did not answer in time. */
        TIMEOUT,
        /** The provider answered with a failure, or with something unusable. */
        PROVIDER_FAILED
    }

    private final Reason reason;

    public TranslationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public TranslationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /**
     * Why it failed — so a plugin can retry a {@link Reason#BUSY} and give up on a {@link Reason#NO_PROVIDER}
     * without matching on English.
     *
     * @return the reason; never {@code null}
     */
    public Reason reason() {
        return reason;
    }

    /** Whether trying the same call again later could plausibly succeed. */
    public boolean retryable() {
        return reason == Reason.RATE_LIMITED || reason == Reason.BUSY || reason == Reason.TIMEOUT;
    }
}
