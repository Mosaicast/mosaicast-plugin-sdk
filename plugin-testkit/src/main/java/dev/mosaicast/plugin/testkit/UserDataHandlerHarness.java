// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.UserDataHandler;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exercises a {@link UserDataHandler} the way the host will (ARCHITECTURE §13.5).
 *
 * <p>Erasure is retried after a partial failure, so a handler is called again on data it already erased.
 * <strong>Calling it twice is therefore the test</strong>, and it is the one a plugin author skips: the
 * first call passes, the second throws on a row that is no longer there, and the failure only appears in
 * production during a retry — the worst possible moment, since the alternative to a retry is a deletion
 * left half-done.
 *
 * <pre>{@code
 * var handler = new WikiUserData(schema);
 * new UserDataHandlerHarness(handler).eraseTwice(userId);        // fails loudly if not idempotent
 *
 * assertThat(schema.rows("revision")).noneMatch(r -> userId.equals(r.get("author")));
 * }</pre>
 *
 * @since 0.9.0
 */
public final class UserDataHandlerHarness {

    private final UserDataHandler handler;

    /**
     * Wraps a handler.
     *
     * @param handler the handler under test; never {@code null}
     */
    public UserDataHandlerHarness(UserDataHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Calls {@link UserDataHandler#eraseUser(String)} twice, as a retried deletion would.
     *
     * <p>The second call runs against data the first one already removed. Anything the handler throws
     * propagates — that is the point.
     *
     * @param userId the user to erase; never {@code null}
     * @throws AssertionError if the second call throws where the first succeeded, with the cause attached
     */
    public void eraseTwice(String userId) {
        Objects.requireNonNull(userId, "userId");
        handler.eraseUser(userId);
        try {
            handler.eraseUser(userId);
        } catch (RuntimeException e) {
            throw new AssertionError(
                    "eraseUser is not idempotent: the second call failed where the first succeeded. "
                            + "The host retries a failed deletion, so this throws during a retry — when the "
                            + "alternative is leaving the deletion half-done.", e);
        }
    }

    /**
     * Calls {@link UserDataHandler#exportUser(String)}.
     *
     * <p>Call it <em>before</em> {@link #eraseTwice(String)}: an export after erasure describes nothing,
     * and an export is a request in its own right rather than a step of a deletion.
     *
     * @param userId the user to export; never {@code null}
     * @return whatever the handler exports — {@link Optional#empty()} for the default implementation
     */
    public Optional<Map<String, Object>> export(String userId) {
        return handler.exportUser(Objects.requireNonNull(userId, "userId"));
    }
}
