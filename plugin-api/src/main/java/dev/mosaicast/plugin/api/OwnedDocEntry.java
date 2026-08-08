// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One user's document from {@link DocStore#queryAcrossUsers(String)}: the owner, the key and the raw JSON
 * value.
 *
 * <p>A {@link DocEntry} with an owner. The owner is here because a cross-user aggregate is meaningless
 * without one — and it is the <strong>host's</strong> id for that user, resolved from the partition the
 * document actually lives in, never a value a client supplied. That is the whole point of the
 * {@link ScopeType#USER} scope: an id a plugin reads here cannot have been forged by the browser that
 * wrote the document.
 *
 * <p>{@code userId} is an identifier, not a display name, and this contract offers no way to turn it into
 * one — resolving names is the host's business. Show it only where an opaque id is acceptable, or key
 * your own display data by it.
 *
 * @param userId the host's id for the user whose partition the document lives in; never {@code null}
 * @param key    the document's key within that partition; never {@code null}, and matching
 *               {@link DocStore#KEY_PATTERN}
 * @param value  the document's JSON value; never {@code null}
 * @since 0.5.0
 */
public record OwnedDocEntry(UUID userId, String key, JsonNode value) {

    /**
     * Canonical constructor validating that no component is {@code null}.
     */
    public OwnedDocEntry {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }
}
