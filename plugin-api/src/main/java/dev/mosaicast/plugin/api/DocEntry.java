// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

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
 * <p><strong>This is the only place a third-party type appears in the contract</strong>, and since 0.4.0
 * it is <em>Jackson 3</em> ({@code tools.jackson.databind}, not {@code com.fasterxml.jackson.databind}) —
 * the host runs Spring Boot 4 and plugins load parent-first under PF4J, so the contract has to name the
 * databind the host actually loads. If you would rather not see Jackson at all, none of
 * {@link DocStore#get(Scope, String, Class)}, {@link PluginConfig#get(String, Class)} or
 * {@link SchemaStore} exposes it: they deserialize into your own types. Only
 * {@link DocStore#query(Scope, String)} hands you a node.
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
