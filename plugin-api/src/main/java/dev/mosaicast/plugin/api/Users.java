// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Turns user UUIDs a plugin already holds into something it can render (ARCHITECTURE §8.8).
 *
 * <p>{@link DocStore#queryAcrossUsers(String)} hands a backend {@link OwnedDocEntry} — a
 * {@link OwnedDocEntry#userId() userId} and a document, and nothing else. A plugin that aggregates
 * across users (a bingo leaderboard, the case this was written for) therefore held UUIDs with no way to
 * show a person. This is the gap being filled, and it is filled with a <strong>lookup rather than a
 * wider {@code ctx.user}</strong>: §10 still holds, the host still resolves access, and what a plugin
 * learns about somebody else stays exactly {@link UserRef}.
 *
 * <p>Reachable through {@link PluginContext#users()}, which is <strong>{@code null}</strong> unless the
 * manifest declares an {@code identity} block — the same shape and the same reasoning as
 * {@link PluginBlobs} and {@link Tags}: what a plugin may touch is decided in the manifest and nowhere
 * else. Declared, never derived, even though a plugin already <em>has</em> the ids: the capability being
 * granted is not access to the UUIDs but the turning of them into people, and that is the part an
 * operator should be able to read off a manifest before installing.
 *
 * <pre>{@code
 * "identity": { "resolvesUsers": true }
 * }</pre>
 *
 * <h2>It resolves, it does not enumerate</h2>
 *
 * <p>There is no list call and there will not be one. A plugin may ask only about ids it already holds,
 * and the only way it comes by them is its own scope — which is what keeps a lookup from being a user
 * directory dump.
 *
 * <h2>Store UUIDs, resolve at render</h2>
 *
 * <p><strong>Never persist a display name.</strong> A copied name survives the rename meant to shed it
 * and the erasure meant to end it, and core cannot reach inside a plugin's own storage to fix either.
 * Keep the {@link UserRef#id()} in your documents and call this at the point you draw the row.
 *
 * @since 0.13.0
 */
public interface Users {

    /**
     * Resolves user ids to who they are.
     *
     * <p><strong>Absent, not redacted.</strong> An id that is unknown, erased or pseudonymised (§12.8) is
     * simply <em>missing</em> from the returned list — there is no null element and no tombstone entry.
     * The result is therefore <strong>not index-aligned</strong> with the input and may be shorter than
     * it; match on {@link UserRef#id()}, never on position. That is the shape {@link FeedAccess} already
     * uses, and it is what lets a leaderboard row outlive its author as §13 requires: the aggregate
     * stays, the person becomes whatever placeholder the plugin chooses to render.
     *
     * <p>Duplicate ids resolve once. An empty input resolves to an empty list rather than failing.
     *
     * @param ids the user ids to resolve; never {@code null}, and must not contain {@code null}
     * @return the users that could be resolved, in no guaranteed order and with unresolvable ids left
     *         out; never {@code null}
     */
    List<UserRef> resolve(Collection<UUID> ids);
}
