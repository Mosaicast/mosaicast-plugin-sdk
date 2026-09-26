// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.List;

/**
 * Reads every user's {@link ScopeType#USER} partition of this plugin at once — the one read that crosses
 * an ownership boundary (ARCHITECTURE §7.4).
 *
 * <p>Reached through {@link PluginContext#allUsers()}, which is {@code null} unless the manifest declares
 * it:
 *
 * <pre>{@code
 * "data": { "writableBy": "fan", "readsAllUsers": true }
 * }</pre>
 *
 * <p><strong>Why it is declared.</strong> Everything else in the doc store is a plugin reading its own
 * shared scopes or the current caller's own partition. This returns <em>every</em> account's documents,
 * each with the owner's UUID — "can enumerate everyone who ever used me, in one call" — which is a
 * different thing for an operator to be told before installing than "holds the ids of the people who
 * used me", the most the {@code USER} scope alone implies. Until 0.16.0 it hung off
 * {@link PluginContext#store()} as {@code DocStore.queryAcrossUsers}, reachable by every plugin by merely
 * existing; it is a handle of its own now for the same reason {@link PluginContext#blobs()},
 * {@link PluginContext#users()} and {@link PluginContext#notifier()} are.
 *
 * <p><strong>What it is for.</strong> Aggregates — a leaderboard, a moderation view, a nightly rollup.
 * It is the wrong tool for showing one user their own state: that is {@code ctx.docs.get('self', …)}
 * from the frontend, where the host resolves the caller.
 *
 * <p>Backend-only and read-only: there is no HTTP surface for it, so no visitor's request can reach
 * another visitor's data through it. Keeping the aggregate on the server is also what makes it
 * <em>true</em> — the alternative, having each browser report its own summary into a shared scope, puts a
 * forgeable number in the client's hands.
 *
 * @since 0.16.0
 */
public interface CrossUserStore {

    /**
     * Every user's entries under {@code keyPrefix}, across all {@link ScopeType#USER} partitions of this
     * plugin.
     *
     * <p>Each result carries the host-resolved {@link OwnedDocEntry#userId() owner}. The scope is implicit
     * (all user partitions), so unlike {@link DocStore#query(Scope, String)} there is nothing to pass but
     * the prefix. To show an owner as a person, resolve the id through {@link PluginContext#users()}.
     *
     * @param keyPrefix the key prefix to match; an empty string matches every key in every user partition
     * @return the matching documents with their owners, in no guaranteed order; never {@code null}, empty
     *         when nothing matches
     */
    List<OwnedDocEntry> query(String keyPrefix);
}
