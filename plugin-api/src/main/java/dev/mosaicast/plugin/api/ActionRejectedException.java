// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Refuses an action call for a reason of the plugin's own: not your turn, game already started, name taken
 * (ARCHITECTURE §7.9).
 *
 * <p>Throw it from an {@link ActionHandler}. The host answers <strong>422</strong>
 * {@code https://mosaicast.dev/problems/action-rejected}, with {@link #code()} as a top-level {@code code}
 * member of the problem body, so the plugin's UI can say why in the reader's language rather than show
 * {@link #detail()}, which is for logs and developers.
 *
 * <p>Any other exception from a handler is a 500 and is logged to the plugin's log: a rejection is an
 * answer, a crash is a bug.
 *
 * @since 0.20.0
 */
public class ActionRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final Pattern CODE = Pattern.compile(Actions.NAME_PATTERN);

    /** The machine-readable reason. */
    private final String code;

    /** The human-readable explanation, or {@code null}. */
    private final String detail;

    /**
     * A rejection with a code and no detail.
     *
     * @param code the reason, matching {@link Actions#NAME_PATTERN} (e.g. {@code not-your-turn}); never
     *             {@code null}
     * @throws IllegalArgumentException if {@code code} does not match the grammar
     */
    public ActionRejectedException(String code) {
        this(code, null);
    }

    /**
     * A rejection with a code and a developer-facing detail.
     *
     * @param code   the reason, matching {@link Actions#NAME_PATTERN}; never {@code null}
     * @param detail an explanation for logs and the problem body's {@code detail}, or {@code null}
     * @throws IllegalArgumentException if {@code code} does not match the grammar
     */
    public ActionRejectedException(String code, String detail) {
        super(message(code, detail));
        this.code = code;
        this.detail = detail;
    }

    private static String message(String code, String detail) {
        Objects.requireNonNull(code, "code");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "rejection code '" + code + "' does not match " + Actions.NAME_PATTERN);
        }
        return detail == null ? code : code + ": " + detail;
    }

    /**
     * The machine-readable reason, sent as the problem body's {@code code}.
     *
     * @return the code; never {@code null}
     */
    public String code() {
        return code;
    }

    /**
     * The developer-facing explanation, sent as the problem body's {@code detail}.
     *
     * @return the detail, or {@code null} when none was given
     */
    public String detail() {
        return detail;
    }
}
