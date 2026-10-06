// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.ExportFile;
import dev.mosaicast.plugin.api.UserDataHandler;
import dev.mosaicast.plugin.api.UserExport;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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

    /** Serialises an {@code exportUser} map the way the host does before writing it as {@code data.json}. */
    private static final ObjectMapper JSON = JsonMapper.builder().build();

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

    /**
     * Asks for the handler's part of a data export the way the host does, and checks it against the limits
     * the host enforces.
     *
     * <p>The host's order: {@link UserDataHandler#exportFiles(String)} first; when that is empty,
     * {@link UserDataHandler#exportUser(String)}, whose non-empty map becomes {@code data.json}. So a
     * handler written against the {@code Map} form comes back here as one {@code data.json} file too — the
     * same JSON the host writes, though not necessarily byte for byte (formatting is the host's).
     *
     * <pre>{@code
     * UserExport part = new UserDataHandlerHarness(handler).exportFiles(alice).orElseThrow();
     * assertEquals(List.of("cards.json"), part.files().stream().map(ExportFile::path).toList());
     * }</pre>
     *
     * <p>Call it <em>before</em> {@link #eraseTwice(String)}, as with {@link #export(String)}.
     *
     * @param userId the user to export; never {@code null}
     * @return the part the host would pack under {@code plugins/<pluginId>/}, or {@link Optional#empty()}
     *         when the handler exports nothing for this user
     * @throws AssertionError if the part is larger than {@link UserExport#MAX_BYTES} or took longer than
     *                        {@link UserExport#TIMEOUT} — both of which the host records as a failed part
     * @since 0.19.0
     */
    public Optional<UserExport> exportFiles(String userId) {
        Objects.requireNonNull(userId, "userId");
        long started = System.nanoTime();
        Optional<UserExport> part = handler.exportFiles(userId);
        if (part.isEmpty()) {
            part = handler.exportUser(userId)
                    .filter(map -> !map.isEmpty())
                    .map(map -> UserExport.of(
                            new ExportFile("data.json", "application/json", JSON.writeValueAsBytes(map))));
        }
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        if (took.compareTo(UserExport.TIMEOUT) > 0) {
            throw new AssertionError("the export took " + took.toMillis() + " ms; the host gives up after "
                    + UserExport.TIMEOUT.toSeconds() + " s and records this plugin's part as failed");
        }
        part.ifPresent(export -> {
            if (export.totalBytes() > UserExport.MAX_BYTES) {
                throw new AssertionError("the export holds " + export.totalBytes() + " bytes; the host refuses "
                        + "more than " + UserExport.MAX_BYTES + " per plugin and records the part as failed");
            }
        });
        return part;
    }
}
