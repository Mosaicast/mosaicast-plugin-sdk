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
 * guarantees model it in their data design (§7.6).
 *
 * <p><strong>Per-user data belongs in the {@link ScopeType#USER} scope, never in the key.</strong> Keys
 * are client-supplied, so the convention this javadoc used to recommend — {@code mark:<userId>:cell}
 * under an episode scope — was an access-control decision the host had no way to enforce: any caller past
 * the plugin's read floor could address another user's key directly, and scope ids are public slugs, so
 * nothing had to be guessed. {@link Scope#user()} is addressed as {@code user/me} and resolved
 * server-side from the session, so the partition a caller reaches is the only one they can name. The
 * partition is flat, so the entity goes in the key instead: {@code mark:<episodeSlug>:cell}.
 *
 * <p><strong>A backend thread has no calling user</strong>, so every method below throws
 * {@link UnsupportedOperationException} when handed a {@code USER} scope — reads included, since
 * resolving "me" without a caller would have to pick someone, and any pick is wrong. To aggregate over
 * users, use {@link #queryAcrossUsers(String)}, which is explicit about having no single owner.
 *
 * <p><strong>A shared-scope document has no owner.</strong> Anything above the plugin's
 * {@code writableBy} floor can overwrite or delete any key in a {@link ScopeType#SITE},
 * {@link ScopeType#FEED}, {@link ScopeType#SEASON} or {@link ScopeType#EPISODE} scope — including a value
 * your backend computed, because authorization is per plugin, not per document: the host cannot tell your
 * scheduled write from a {@code curl}. If your backend authors a key, <strong>declare it</strong> in the
 * manifest and clients may read it and nothing else:
 *
 * <pre>{@code
 * "data": {
 *   "readableBy": "anonymous",
 *   "writableBy": "podcaster",
 *   "backendOwned": ["stats", "agg:*"]
 * }
 * }</pre>
 *
 * <p>Each entry is an exact key or a prefix ending in {@code *}, matching {@link #BACKEND_OWNED_PATTERN}.
 * A client {@code PUT} or {@code DELETE} to a matching key is refused (HTTP 403, distinct from the
 * role-floor refusal); this store — the backend's — is unaffected, which is the point. Reads are
 * untouched and still governed by {@code readableBy}.
 *
 * <p>Two consequences worth knowing. Declaring a key {@code backendOwned} does <strong>not</strong> clean
 * up a value a client wrote before the declaration existed: the row stays until your backend overwrites
 * it, so <strong>write your computed keys in {@link PluginBackend#register(PluginContext)} as well as on
 * a schedule</strong> — otherwise a forged value survives until the next tick. And the declaration is
 * ignored for {@link ScopeType#USER} scopes, where the backend cannot write at all; a partition stays
 * writable by its owner even under a bare {@code "*"}.
 *
 * <p><strong>This is the plugin's sole persistence path</strong> (unless the manifest declares a
 * {@link SchemaStore schema}), and it is shared with the plugin's frontend: the host exposes a fixed,
 * generic, per-plugin-namespaced HTTP surface over it, which the Web Component reaches via
 * {@code ctx.api}. That surface mirrors this interface one-to-one — get, put, list, delete, and no more
 * ({@link #queryAcrossUsers(String)} excepted: it is backend-only and has no endpoint). A document
 * written here with {@link #put(Scope, String, Object)} is read by the frontend at
 * {@code GET /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}} — see {@link PluginContext#store()} for
 * the full endpoint list and its access rules. Plugins do not define HTTP routes.
 *
 * <p><strong>Keys</strong> must match {@link #KEY_PATTERN}: they travel verbatim as the final path
 * segment of that HTTP surface, so a {@code /} is not addressable (and a percent-encoded one is rejected
 * by the servlet container). Structure keys with {@code :}, {@code .} or {@code -} instead — e.g.
 * {@code mark:s2e04:cell}. The host rejects a key outside that charset (HTTP 400 on its surface,
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
     * <p>200 characters is ample for a composite key carrying a slug — entity ids are slugs, not UUIDs.
     *
     * <p>Implementations validate against this pattern; the host and the test kit both reject a
     * non-matching key rather than storing a document the frontend could never address.
     */
    String KEY_PATTERN = "^[A-Za-z0-9._:-]{1,200}$";

    /**
     * The grammar of one {@code data.backendOwned} entry in the manifest: {@value}.
     *
     * <p>An exact key, a {@link #KEY_PATTERN}-legal prefix followed by a single trailing {@code *}, or the
     * bare {@code *} meaning every key. Nothing else: no {@code *} in the middle, no empty entry, and the
     * comparison is case-sensitive.
     *
     * <p>A pattern can never be mistaken for a key, because {@code *} is not in {@link #KEY_PATTERN} — and
     * capping the prefix at the same 200 characters keeps a pattern from out-ranging any key it could
     * match. The host rejects a malformed entry when the plugin loads; the test kit rejects it when you
     * declare it, so a typo in a security declaration fails in your tests.
     *
     * <p>A bare {@code *} is deliberately legal here, unlike in {@code consent.storage[]}: it means "the
     * whole doc store is authored by my backend, clients read only", which is a coherent thing for a
     * plugin whose data is entirely computed. Note what it does <em>not</em> cover — a
     * {@link ScopeType#USER} partition stays writable by its owner, since the backend cannot write one.
     *
     * @since 0.6.0
     */
    String BACKEND_OWNED_PATTERN = "^(\\*|[A-Za-z0-9._:-]{1,200}\\*?)$";

    /**
     * Reads a value and deserializes it to the requested type.
     *
     * @param scope the scope the entry lives in; never {@code null}
     * @param key   the entry key; never {@code null}
     * @param type  the target type to deserialize into; never {@code null}
     * @param <T>   the value type
     * @return the value, or {@link Optional#empty()} if no entry exists for {@code (scope, key)}
     * @throws UnsupportedOperationException if {@code scope} is a {@link ScopeType#USER} scope — a
     *         backend has no calling user; see {@link #queryAcrossUsers(String)}
     */
    <T> Optional<T> get(Scope scope, String key, Class<T> type);

    /**
     * Writes a value, serialized to JSON, replacing any existing entry (last-write-wins).
     *
     * @param scope the scope to write into; never {@code null}
     * @param key   the entry key; never {@code null}
     * @param value the value to store, serialized to JSON by the host; never {@code null}
     * @throws IllegalArgumentException      if {@code key} does not match {@link #KEY_PATTERN}
     * @throws UnsupportedOperationException if {@code scope} is a {@link ScopeType#USER} scope — a
     *         backend has no calling user, and cannot write into anyone's partition
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
     * @throws UnsupportedOperationException if {@code scope} is a {@link ScopeType#USER} scope — a
     *         backend has no calling user
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
     * @throws UnsupportedOperationException if {@code scope} is a {@link ScopeType#USER} scope — use
     *         {@link #queryAcrossUsers(String)}, which names the owner of each document it returns
     */
    List<DocEntry> query(Scope scope, String keyPrefix);

    /**
     * Every user's entries under {@code keyPrefix}, across all {@link ScopeType#USER} partitions of this
     * plugin.
     *
     * <p><strong>This reads other people's data.</strong> It exists for aggregates — a leaderboard, a
     * moderation view, a nightly rollup — and it is the wrong tool for showing one user their own state:
     * that is {@code ctx.api} against {@code data/user/me/…} from the frontend, where the host resolves
     * the caller.
     *
     * <p>Backend-only and read-only: there is no HTTP surface for it, so no visitor's request can reach
     * another visitor's data through it. Keeping the aggregate on the server is also what makes it
     * <em>true</em> — the alternative, having each browser report its own summary into a shared scope,
     * puts a forgeable number in the client's hands.
     *
     * <p>Each result carries the host-resolved {@link OwnedDocEntry#userId() owner}. The scope is implicit
     * (all user partitions), so unlike {@link #query(Scope, String)} there is nothing to pass but the
     * prefix.
     *
     * @param keyPrefix the key prefix to match; an empty string matches every key in every user partition
     * @return the matching documents with their owners, in no guaranteed order; never {@code null}, empty
     *         when nothing matches
     * @since 0.5.0
     */
    List<OwnedDocEntry> queryAcrossUsers(String keyPrefix);
}
