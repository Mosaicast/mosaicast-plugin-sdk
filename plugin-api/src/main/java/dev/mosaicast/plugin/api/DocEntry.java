// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * One document in the {@link DocStore}: its key plus its raw JSON value (ARCHITECTURE §7.4/§7.6).
 *
 * <p>Returned by {@link DocStore#query(Scope, String)}. The key is carried because a caller that only
 * receives values cannot tell the documents apart — nor address one afterwards. This mirrors the entries
 * the host's HTTP list endpoint returns to the frontend
 * ({@code GET /api/plugins/{id}/data/{scopeType}/{scopeId}?prefix=…} → {@code items: [{ key, value }]}),
 * so backend and frontend read the same shape.
 *
 * <p>The value stays a raw {@link JsonNode}: a prefix query can span heterogeneous documents, so the
 * caller decides how — and whether — to convert each one.
 *
 * @param key   the document's key within its scope; never {@code null}, and matching
 *              {@link DocStore#KEY_PATTERN}
 * @param value the document's JSON value; never {@code null}
 */
public record DocEntry(String key, JsonNode value) {

    /**
     * Canonical constructor validating that neither component is {@code null}.
     */
    public DocEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }
}
