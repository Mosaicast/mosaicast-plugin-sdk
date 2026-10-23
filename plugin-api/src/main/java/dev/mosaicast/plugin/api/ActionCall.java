// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * One call of an action, as an {@link ActionHandler} receives it (ARCHITECTURE §7.9).
 *
 * <p>Everything here except the payload is <strong>host-resolved</strong>: the caller comes from the
 * session and the scope has been checked to exist and be visible to that caller. Only {@link #payload()}
 * is client input, and is to be validated like any.
 *
 * @since 0.20.0
 */
public interface ActionCall {

    /**
     * Who called — never read from the payload. Never anonymous: the host refuses an anonymous call (401)
     * before a handler runs.
     *
     * @return the caller; never {@code null}
     */
    Caller caller();

    /**
     * The scope the call was made in — the frontend's {@code ctx.scope} unless it passed another. Checked by
     * the host to exist and be visible to the caller (a scope they may not see is a 404, as everywhere).
     * Never {@link ScopeType#USER}; a site-wide action is called in {@link Scope#site()}.
     *
     * @return the scope; never {@code null}
     */
    Scope scope();

    /**
     * The request's payload as a JSON tree. Client input: validate it.
     *
     * @return the payload; a JSON {@code null} node when the client sent none, never Java {@code null}
     */
    JsonNode payload();

    /**
     * The payload, converted to a type.
     *
     * @param type the target type; never {@code null}
     * @param <T>  the target type
     * @return the converted payload, or {@code null} when the client sent none
     * @throws IllegalArgumentException if the payload cannot be converted. Escaping the handler it is a 500
     *                                  like any other throw; catch it and throw an
     *                                  {@link ActionRejectedException} if malformed input deserves a 422
     */
    <T> T payload(Class<T> type);

    /**
     * When the request reached the host, stamped <strong>before</strong> it waited for a free executor
     * thread. Two calls are ordered by this, not by when their handlers happened to run: it is what decides
     * who buzzed first.
     *
     * @return the arrival instant; never {@code null}
     */
    Instant receivedAt();

    /**
     * The plugin's doc store, as {@link PluginContext#store()} — with one difference: a
     * {@link ScopeType#USER} scope resolves to the <strong>caller's</strong> partition instead of throwing.
     *
     * <p>That partition stays the user's. They can overwrite it over HTTP, and {@code backendOwned},
     * {@code keyFloors} and {@code backendPrivate} are all ignored there (§7.6), so what an action writes to
     * {@code USER} is a convenience for that user and <strong>never authoritative</strong>. State the backend
     * vouches for — an accepted move, a hidden hand — goes in a shared scope under {@code data.backendPrivate}.
     *
     * @return the store; never {@code null}
     */
    DocStore store();
}
