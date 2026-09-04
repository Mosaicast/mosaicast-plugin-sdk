// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.UserRef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link FakeUsers} — above all the absent case, which is the behaviour a permissive double would
 * hide and every leaderboard would then get wrong.
 */
class FakeUsersTest {

    @Test
    void resolvesASeededUser() {
        UUID ana = UUID.randomUUID();
        FakeUsers users = new FakeUsers().withUser(ana, "Ana", Role.FAN);

        List<UserRef> found = users.resolve(List.of(ana));

        assertEquals(1, found.size());
        UserRef ref = found.get(0);
        assertEquals(ana, ref.id());
        assertEquals("Ana", ref.displayName());
        assertEquals(Role.FAN, ref.role());
        // The host's own path, never a provider URL, and never absent (§8.7).
        assertEquals("/api/users/" + ana + "/avatar", ref.avatarUrl());
    }

    @Test
    void anUnknownIdIsAbsentRatherThanNull() {
        UUID ana = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        FakeUsers users = new FakeUsers().withUser(ana, "Ana", Role.FAN);

        List<UserRef> found = users.resolve(List.of(ana, stranger));

        // Shorter than the input, and no null element: the result is not index-aligned, so a plugin that
        // reads found.get(1) for the second id it asked about is already wrong.
        assertEquals(1, found.size());
        assertEquals(ana, found.get(0).id());
        assertTrue(found.stream().noneMatch(java.util.Objects::isNull));
    }

    @Test
    void anErasedUserBecomesAbsent() {
        UUID gone = UUID.randomUUID();
        FakeUsers users = new FakeUsers().withUser(gone, "Ana", Role.FAN);
        assertEquals(1, users.resolve(List.of(gone)).size());

        // The §12.8 case: the document survives, its author does not.
        users.withoutUser(gone);

        assertEquals(List.of(), users.resolve(List.of(gone)));
    }

    @Test
    void resolvesEveryIdAtMostOnce() {
        UUID ana = UUID.randomUUID();
        FakeUsers users = new FakeUsers().withUser(ana, "Ana", Role.FAN);

        List<UserRef> found = users.resolve(List.of(ana, ana, ana));

        assertEquals(1, found.size());
        // The raw ask is still recorded, so a test can assert a plugin batched its lookups.
        assertEquals(List.of(ana, ana, ana), users.resolvedIds());
    }

    @Test
    void anEmptyAskResolvesToAnEmptyListRatherThanFailing() {
        assertEquals(List.of(), new FakeUsers().resolve(List.of()));
    }

    @Test
    void rejectsANullId() {
        FakeUsers users = new FakeUsers();
        List<UUID> withNull = new ArrayList<>();
        withNull.add(null);

        assertThrows(NullPointerException.class, () -> users.resolve(withNull));
        assertThrows(NullPointerException.class, () -> users.resolve(null));
    }

    @Test
    void contextHasNoDirectoryUntilOneIsWired() {
        FakePluginContext ctx = new FakePluginContext();

        // The undeclared-manifest case, which is most plugins.
        assertNull(ctx.users());

        FakeUsers users = new FakeUsers();
        UUID ana = users.withUser("Ana", Role.FAN);

        assertSame(users, ctx.withUsers(users).users());
        assertEquals(List.of(ana), ctx.users().resolve(List.of(ana)).stream().map(UserRef::id).toList());
        assertNull(ctx.withUsers(null).users());
    }
}
