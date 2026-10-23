// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Who is calling an action or subscribing to a channel, as the host resolved it from the session
 * (ARCHITECTURE §7.9).
 *
 * <p>Never read from a payload: a client cannot name itself. The role is the caller's at the moment the
 * request arrived; a role change ends every subscription the user holds, so a {@code Caller} a channel
 * listener receives stays true for as long as that subscription lives. An anonymous visitor never becomes a
 * {@code Caller} — the host refuses the request (401) before any plugin code runs.
 *
 * <p>To show the caller as a person, resolve {@link #userId()} through {@link PluginContext#users()}; store
 * the id, never a name.
 *
 * @param userId the caller's user id; never {@code null}
 * @param role   the caller's role; never {@code null}
 * @since 0.20.0
 */
public record Caller(UUID userId, Role role) {

    /**
     * Validates both components.
     *
     * @throws NullPointerException if either is {@code null}
     */
    public Caller {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
    }
}
