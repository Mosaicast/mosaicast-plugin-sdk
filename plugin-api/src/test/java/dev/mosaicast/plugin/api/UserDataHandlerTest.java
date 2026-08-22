// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests the one piece of behaviour {@link UserDataHandler} carries: the {@code exportUser} default, which
 * is what lets a plugin implement erasure without describing rows it would rather not describe.
 */
class UserDataHandlerTest {

    /** A handler that only erases — the common case, and the one the default exists for. */
    private static final class EraseOnly implements UserDataHandler {
        private final List<String> erased = new java.util.ArrayList<>();

        @Override
        public void eraseUser(String userId) {
            erased.add(userId);
        }
    }

    @Test
    void exportDefaultsToNothingSoErasureCanShipAlone() {
        EraseOnly handler = new EraseOnly();

        assertEquals(Optional.empty(), handler.exportUser("u-1"));

        handler.eraseUser("u-1");
        assertEquals(List.of("u-1"), handler.erased);
    }

    @Test
    void aHandlerMayOverrideExportWithoutChangingErasure() {
        UserDataHandler handler = new UserDataHandler() {
            @Override
            public void eraseUser(String userId) {
                // no-op
            }

            @Override
            public Optional<Map<String, Object>> exportUser(String userId) {
                return Optional.of(Map.of("pages", List.of("kraken")));
            }
        };

        Optional<Map<String, Object>> exported = handler.exportUser("u-1");

        assertTrue(exported.isPresent());
        assertEquals(List.of("kraken"), exported.get().get("pages"));
    }

    @Test
    void bothMethodsAreOnOneInterfaceSoTheLookupIsWrittenOnce() {
        // Not a behavioural assertion so much as a pin on the shape: erasure and export need the same
        // "find this user's rows" query, and splitting them means every plugin writes it twice.
        assertTrue(UserDataHandler.class.isAssignableFrom(EraseOnly.class));
        assertFalse(PluginBackend.class.isAssignableFrom(EraseOnly.class));
    }
}
