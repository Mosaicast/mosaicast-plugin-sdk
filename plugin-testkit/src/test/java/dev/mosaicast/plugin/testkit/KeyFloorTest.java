// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.DocEntry;
import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.Scope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code data.keyFloors} (0.19.0), as the stats plugin's manifest uses it. */
class KeyFloorTest {

    private static final Scope SITE = Scope.site();

    /** Public stats beside private import bookkeeping, and admin-only bundles. */
    private static InMemoryDocStore statsStore() {
        InMemoryDocStore store = new InMemoryDocStore()
                .withBackendOwned("stats", "import:*", "staged:*")
                .withKeyFloor("import:*", "podcaster", null)
                .withKeyFloor("staged:*", "podcaster", null)
                .withKeyFloor("bundles", null, "admin");
        store.put(SITE, "stats", 42);
        store.put(SITE, "import:1", "episode-slug-of-a-quiet-planned-episode");
        store.put(SITE, "bundles", "site-wide");
        return store;
    }

    private static List<String> keys(List<DocEntry> entries) {
        return entries.stream().map(DocEntry::key).toList();
    }

    @Test
    void aListingLeavesOutWhatTheReaderMayNotSee() {
        InMemoryDocStore store = statsStore();

        assertEquals(List.of("stats", "bundles"), keys(store.asAnonymous().query(SITE, "")));
        assertEquals(List.of("stats", "bundles"), keys(store.asUser(UUID.randomUUID()).query(SITE, "")));
        assertEquals(List.of("stats", "import:1", "bundles"),
                keys(store.asUser(UUID.randomUUID(), Role.PODCASTER).query(SITE, "")));
    }

    @Test
    void aSingleReadBelowTheFloorIsRefusedNotAnsweredEmpty() {
        InMemoryDocStore store = statsStore();

        // The host answers 403 — an empty answer would read as "nothing imported".
        assertThrows(IllegalStateException.class, () -> store.asAnonymous().get(SITE, "import:1", String.class));
        assertEquals(Optional.of(42), store.asAnonymous().get(SITE, "stats", Integer.class));
    }

    @Test
    void aWriteFloorRaisesTheWriteOnly() {
        InMemoryDocStore store = statsStore();
        InMemoryDocStore podcaster = store.asUser(UUID.randomUUID(), Role.PODCASTER);
        InMemoryDocStore admin = store.asUser(UUID.randomUUID(), Role.ADMIN);

        assertThrows(IllegalStateException.class, () -> podcaster.put(SITE, "bundles", "mine"));
        assertThrows(IllegalStateException.class, () -> podcaster.delete(SITE, "bundles"));
        assertEquals(Optional.of("site-wide"), podcaster.get(SITE, "bundles", String.class));

        admin.put(SITE, "bundles", "admin's");
        assertEquals(Optional.of("admin's"), store.get(SITE, "bundles", String.class));
    }

    @Test
    void theStrictestMatchingFloorWins() {
        InMemoryDocStore store = new InMemoryDocStore()
                .withKeyFloor("notes:*", "fan", null)
                .withKeyFloor("notes:private", "admin", null);
        store.put(SITE, "notes:public", "a");
        store.put(SITE, "notes:private", "b");

        InMemoryDocStore podcaster = store.asUser(UUID.randomUUID(), Role.PODCASTER);
        assertEquals(List.of("notes:public"), keys(podcaster.query(SITE, "notes:")));
        assertThrows(IllegalStateException.class, () -> podcaster.get(SITE, "notes:private", String.class));
    }

    @Test
    void theBackendAndUserPartitionsAreNeverBound() {
        InMemoryDocStore store = statsStore().withKeyFloor("*", "admin", "admin");

        // The backend reads and writes every key it owns.
        store.put(SITE, "import:2", "x");
        assertEquals(3 + 1, store.query(SITE, "").size());

        // A user's own partition is theirs whatever the floors say.
        InMemoryDocStore fan = store.asUser(UUID.randomUUID());
        fan.put(Scope.user(), "mark:ep-7:b3", Boolean.TRUE);
        assertEquals(Optional.of(Boolean.TRUE), fan.get(Scope.user(), "mark:ep-7:b3", Boolean.class));
    }

    @Test
    void anAnonymousViewHasNoUserPartition() {
        InMemoryDocStore store = new InMemoryDocStore();

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> store.asAnonymous().get(Scope.user(), "k", String.class));
        assertTrue(refused.getMessage().contains("401"));
    }

    @Test
    void declarationsTheHostRejectsAtLoadAreRejectedHere() {
        InMemoryDocStore store = new InMemoryDocStore();

        assertThrows(IllegalArgumentException.class, () -> store.withKeyFloor("import:*:x", "fan", null));
        assertThrows(IllegalArgumentException.class, () -> store.withKeyFloor("import:*", null, null));
        assertThrows(IllegalArgumentException.class, () -> store.withKeyFloor("import:*", "owner", null));
        assertThrows(IllegalArgumentException.class, () -> store.withKeyFloor("import:*", null, "anonymous"));
        assertThrows(IllegalArgumentException.class, () -> store.withKeyFloor("import:*", "Fan", null));
    }
}
