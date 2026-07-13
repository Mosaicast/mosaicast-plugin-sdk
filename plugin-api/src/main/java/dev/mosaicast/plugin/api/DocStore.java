// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

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
 * {@code ctx.api}. That surface mirrors this interface one-to-one — get, put, list, delete, and no more.
 * A document written here with {@link #put(Scope, String, Object)} is read by the frontend at
 * {@code GET /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}} — see {@link PluginContext#store()} for
 * the full endpoint list and its access rules. Plugins do not define HTTP routes.
 *
 * <p><strong>Keys</strong> must match {@link #KEY_PATTERN}: they travel verbatim as the final path
 * segment of that HTTP surface, so a {@code /} is not addressable (and a percent-encoded one is rejected
 * by the servlet container). Structure keys with {@code :}, {@code .} or {@code -} instead — e.g.
 * {@code mark:userId:cell}. The host rejects a key outside that charset (HTTP 400 on its surface,
 * {@link IllegalArgumentException} on this one).
 */
public interface DocStore {

    /**
     * The charset a document key must match: {@value}.
     *
     * <p>Keys are the final segment of the host's data path
     * ({@code …/data/{scopeType}/{scopeId}/{key}}) and are carried verbatim, so {@code /} is excluded —
     * percent-encoding it does not help, as servlet containers reject {@code %2F} in a path segment by
     * default. Use {@code :}, {@code .} or {@code -} as separators instead.
     *
     * <p>Implementations validate against this pattern; the host and the test kit both reject a
     * non-matching key rather than storing a document the frontend could never address.
     */
    String KEY_PATTERN = "^[A-Za-z0-9._:-]{1,200}$";

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
     * @throws IllegalArgumentException if {@code key} does not match {@link #KEY_PATTERN}
     */
    void put(Scope scope, String key, Object value);

    /**
     * Removes the entry at {@code (scope, key)}, if one exists.
     *
     * <p>The frontend counterpart is {@code DELETE /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}}.
     * Deleting an absent entry is not an error — the call is idempotent, and the return value says whether
     * anything was actually removed.
     *
     * @param scope the scope the entry lives in; never {@code null}
     * @param key   the entry key; never {@code null}
     * @return {@code true} if an entry existed and was removed, {@code false} if there was nothing to
     *         remove
     */
    boolean delete(Scope scope, String key);

    /**
     * Returns every document in the scope whose key starts with the given prefix.
     *
     * <p>Entries are keyed ({@link DocEntry}), matching what the host's HTTP list endpoint returns to the
     * frontend: a caller that receives values alone cannot tell the documents apart, nor address one
     * afterwards.
     *
     * @param scope     the scope to query; never {@code null}
     * @param keyPrefix the key prefix to match; an empty string matches all keys in the scope
     * @return the matching documents, in no guaranteed order; never {@code null}, empty when nothing
     *         matches
     */
    List<DocEntry> query(Scope scope, String keyPrefix);
}
