// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * The generic, hard-scoped JSON document store — the default plugin storage (ARCHITECTURE §7.4/§7.6).
 *
 * <p>Every entry is addressed by a {@link Scope} plus a string key and holds an arbitrary JSON value.
 * The store is <strong>hard-scoped</strong>: the host binds each call to the plugin, so one plugin can
 * never read another plugin's data, and reads/writes are confined to the given scope.
 *
 * <p><strong>Concurrency:</strong> writes are <em>last-write-wins</em>. Plugins that need stronger
 * guarantees model it in their key design (e.g. per-user keys), as bingo does (§7.6).
 *
 * <p><strong>This is the plugin's sole persistence path</strong> (unless the manifest declares a
 * {@link SchemaStore schema}), and it is shared with the plugin's frontend: the host exposes a fixed,
 * generic, per-plugin-namespaced HTTP surface over it, which the Web Component reaches via
 * {@code ctx.api}. A document written here with {@link #put(Scope, String, Object)} is read by the
 * frontend at {@code GET /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}} — see
 * {@link PluginContext#store()} for the full endpoint list and its access rules. Plugins do not define
 * HTTP routes in v1.
 */
public interface DocStore {

    /**
     * Reads a value and deserializes it to the requested type.
     *
     * @param scope the scope the entry lives in; never {@code null}
     * @param key   the entry key; never {@code null}
     * @param type  the target type to deserialize into; never {@code null}
     * @param <T>   the value type
     * @return the value, or {@link Optional#empty()} if no entry exists for {@code (scope, key)}
     */
    <T> Optional<T> get(Scope scope, String key, Class<T> type);

    /**
     * Writes a value, serialized to JSON, replacing any existing entry (last-write-wins).
     *
     * @param scope the scope to write into; never {@code null}
     * @param key   the entry key; never {@code null}
     * @param value the value to store, serialized to JSON by the host; never {@code null}
     */
    void put(Scope scope, String key, Object value);

    /**
     * Returns every stored value in the scope whose key starts with the given prefix.
     *
     * @param scope     the scope to query; never {@code null}
     * @param keyPrefix the key prefix to match; an empty string matches all keys in the scope
     * @return the matching values as raw JSON nodes, in no guaranteed order; never {@code null}, empty
     *         when nothing matches
     */
    List<JsonNode> query(Scope scope, String keyPrefix);
}
