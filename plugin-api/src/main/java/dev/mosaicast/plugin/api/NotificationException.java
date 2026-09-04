// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * A notification the host would not send (ARCHITECTURE §17.1).
 *
 * <p>Checked, for the reason {@link TranslationException} is: this is a routine, expected outcome rather
 * than a programming error. A bingo resolving for two hundred participants against an operator's cap is
 * the ordinary case, not a bug, and a plugin that has not decided what to do about it has one. The
 * compiler is the cheapest place to find that out.
 *
 * <p><strong>Being refused is not the same as being ignored.</strong> A recipient this plugin may not
 * notify at all is not an error — {@link Notifier#send(java.util.Collection, NotifyMessage)} leaves them
 * out of its result and says so. This exception is for the call as a whole failing.
 *
 * @since 0.14.0
 */
public class NotificationException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why the notification was not sent. */
    public enum Reason {

        /**
         * The plugin is over its send cap — per recipient per day, or the ceiling across all recipients.
         *
         * <p>The operator sets both, capping whatever the manifest's {@code notifications.perUserPerDay}
         * asked for. Retryable: a scheduled sender should hold the work and try on a later tick rather
         * than dropping it.
         */
        RATE_LIMITED,

        /**
         * {@link NotifyMessage#link()} is not a target the host will point a notification at.
         *
         * <p>Off-site, or outside this plugin's own {@code /p/<pluginId>/} subtree. Not retryable — the
         * link is wrong, and will be just as wrong next tick.
         */
        INVALID_LINK,

        /**
         * The message itself was refused — a key or a parameter over the host's length caps.
         *
         * <p>Not retryable, for the same reason.
         */
        INVALID_MESSAGE
    }

    private final Reason reason;

    /**
     * @param reason  why it was not sent
     * @param message a message safe to log
     */
    public NotificationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * @param reason  why it was not sent
     * @param message a message safe to log
     * @param cause   the underlying failure
     */
    public NotificationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /**
     * Why it failed — so a plugin can hold a {@link Reason#RATE_LIMITED} batch for the next tick and fix
     * an {@link Reason#INVALID_LINK} in its code, without matching on English.
     *
     * @return the reason; never {@code null}
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Whether sending the same notification later could plausibly work.
     *
     * @return {@code true} only for {@link Reason#RATE_LIMITED}
     */
    public boolean retryable() {
        return reason == Reason.RATE_LIMITED;
    }
}
