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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code data.backendPrivate} (0.20.0): no client reaches the keys, the backend reaches all of them. */
class BackendPrivateTest {

    private static final Scope EP = Scope.episode("s1e01");

    private static InMemoryDocStore store() {
        InMemoryDocStore store = new InMemoryDocStore().withBackendPrivate("game:*");
        store.put(EP, "game:1", "hidden hands");
        store.put(EP, "board", "public");
        return store;
    }

    @Test
    void noClientReadsWritesOrDeletesAPrivateKeyNotEvenAnAdmin() {
        InMemoryDocStore admin = store().asUser(UUID.randomUUID(), Role.ADMIN);

        assertThrows(IllegalStateException.class, () -> admin.get(EP, "game:1", String.class));
        assertThrows(IllegalStateException.class, () -> admin.put(EP, "game:1", "forged"));
        assertThrows(IllegalStateException.class, () -> admin.delete(EP, "game:1"));
        assertThrows(IllegalStateException.class, () -> store().asAnonymous().get(EP, "game:2", String.class));
    }

    @Test
    void aListingDropsThemAndTheBackendSeesEverything() {
        InMemoryDocStore store = store();

        assertEquals(List.of("board"), store.asUser(UUID.randomUUID(), Role.ADMIN).query(EP, "").stream()
                .map(DocEntry::key).toList());
        assertEquals(List.of("game:1", "board"), store.query(EP, "").stream().map(DocEntry::key).toList());
        assertEquals("hidden hands", store.get(EP, "game:1", String.class).orElseThrow());
    }

    @Test
    void theUserPartitionIsExempt() {
        InMemoryDocStore alice = store().asUser(UUID.randomUUID());

        alice.put(Scope.user(), "game:mine", 1);
        assertTrue(alice.get(Scope.user(), "game:mine", Integer.class).isPresent());
    }

    @Test
    void aMalformedSelectorFailsLikeTheManifestWould() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryDocStore().withBackendPrivate("a*b"));
    }
}
