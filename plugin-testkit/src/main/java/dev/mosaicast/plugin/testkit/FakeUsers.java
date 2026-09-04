// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.UserRef;
import dev.mosaicast.plugin.api.Users;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An in-memory {@link Users} for testing a plugin against a known set of people (ARCHITECTURE §13.5).
 *
 * <p><strong>It models absence the way the host models it.</strong> An id with no entry is simply
 * <em>missing</em> from the result — not a {@code null} element, not a placeholder {@link UserRef}. That
 * is the one behaviour a permissive double would hide, and hiding it means every plugin's leaderboard
 * passes its tests and then renders {@code undefined} the first time somebody deletes their account.
 * Seed the known users with {@link #withUser(UUID, String, Role)}, resolve a stranger, and the branch
 * that has to survive an erased author gets exercised.
 *
 * <p>{@link UserRef#avatarUrl()} is built the way the host builds it — {@code /api/users/{id}/avatar}
 * (§8.7) — so a test asserting on the rendered {@code src} pins the same string production produces.
 * There is no unset case: every user has an avatar.
 *
 * <pre>{@code
 * UUID ana = UUID.randomUUID();
 * FakeUsers users = new FakeUsers().withUser(ana, "Ana", Role.FAN);
 * FakePluginContext ctx = new FakePluginContext().withUsers(users);
 *
 * List<UserRef> found = ctx.users().resolve(List.of(ana, UUID.randomUUID()));
 * // found.size() == 1 — the stranger is absent, not null
 * }</pre>
 *
 * <p>Not thread-safe.
 *
 * @since 0.13.0
 */
public final class FakeUsers implements Users {

    /** The host's avatar path shape (§8.7) — always populated, never a provider URL. */
    private static final String AVATAR_PATH = "/api/users/%s/avatar";

    /** Seeded users, in insertion order so a resolve result is deterministic to assert on. */
    private final Map<UUID, UserRef> users = new LinkedHashMap<>();

    /** Every id passed to {@link #resolve(Collection)}, flattened in call order. */
    private final List<UUID> resolved = new ArrayList<>();

    /**
     * Seeds a user this directory knows about.
     *
     * <p>The avatar URL is derived rather than accepted: the host always answers
     * {@code /api/users/{id}/avatar}, so letting a test invent one would let it assert a shape production
     * never produces.
     *
     * @param id          the user's UUID; never {@code null}
     * @param displayName what a reader sees; never {@code null}
     * @param role        the user's role; never {@code null}
     * @return this instance, for chaining
     */
    public FakeUsers withUser(UUID id, String displayName, Role role) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(role, "role");
        users.put(id, new UserRef(id, displayName, AVATAR_PATH.formatted(id), role));
        return this;
    }

    /**
     * Seeds a user with a generated UUID, for the common case where the test does not care which.
     *
     * @param displayName what a reader sees; never {@code null}
     * @param role        the user's role; never {@code null}
     * @return the id that was generated, so the test can store it in a document
     */
    public UUID withUser(String displayName, Role role) {
        UUID id = UUID.randomUUID();
        withUser(id, displayName, role);
        return id;
    }

    /**
     * Removes a user from this directory, standing in for an account that was erased or pseudonymised
     * (§12.8) after the plugin stored its id.
     *
     * <p>The interesting case: a document written by somebody who is now gone. What a plugin does with
     * that row is a decision it has to make, and this is how a test makes it make it.
     *
     * @param id the user to forget; unknown ids are ignored
     * @return this instance, for chaining
     */
    public FakeUsers withoutUser(UUID id) {
        users.remove(id);
        return this;
    }

    /**
     * Every id this directory has been asked to resolve, in call order and including duplicates.
     *
     * <p>Useful for pinning the shape of a plugin's lookups — one batched call per render rather than one
     * per row.
     *
     * @return the recorded ids; never {@code null}
     */
    public List<UUID> resolvedIds() {
        return List.copyOf(resolved);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unknown ids are left out of the result, exactly as the host leaves them out. The result is
     * therefore not index-aligned with the input, and may be shorter than it; duplicates resolve once.
     */
    @Override
    public List<UserRef> resolve(Collection<UUID> ids) {
        Objects.requireNonNull(ids, "ids");
        for (UUID id : ids) {
            Objects.requireNonNull(id, "ids must not contain null");
        }
        resolved.addAll(ids);
        List<UserRef> found = new ArrayList<>();
        // A LinkedHashSet, not the raw collection: the host resolves a user once however often it is
        // named, and a leaderboard asking about the same author twice is the ordinary case.
        for (UUID id : new LinkedHashSet<>(ids)) {
            UserRef ref = users.get(id);
            if (ref != null) {
                found.add(ref);
            }
        }
        return List.copyOf(found);
    }
}
