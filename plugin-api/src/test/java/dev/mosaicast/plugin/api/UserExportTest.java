// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The file-based export (0.19.0): what a plugin may hand over, and what it may not. */
class UserExportTest {

    @Test
    void aPathStaysInsideThePluginsFolder() {
        new ExportFile("cards.json", "application/json", new byte[0]);
        new ExportFile("uploads/2026/photo.jpg", "image/jpeg", new byte[0]);

        for (String bad : List.of("", "/abs.json", "../core/account.json", "a/../b", "./a", "a//b", "a\\b",
                "a/", "x?.json", "plugins/other/../../x")) {
            assertThrows(IllegalArgumentException.class, () -> new ExportFile(bad, "text/plain", new byte[0]),
                    bad);
        }
    }

    @Test
    void aMediaTypeIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new ExportFile("a.bin", " ", new byte[0]));
        assertThrows(NullPointerException.class, () -> new ExportFile("a.bin", null, new byte[0]));
    }

    @Test
    void theBytesAreCopiedBothWays() {
        byte[] original = "hello".getBytes(StandardCharsets.UTF_8);
        ExportFile file = new ExportFile("a.txt", "text/plain", original);

        original[0] = 'J';
        file.bytes()[1] = 'A';

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), file.bytes());
        assertEquals(5, file.size());
    }

    @Test
    void equalityComparesContentSoTestsCanAssertOnIt() {
        assertEquals(ExportFile.text("a.csv", "text/csv", "x,y"), ExportFile.text("a.csv", "text/csv", "x,y"));
        assertEquals(ExportFile.text("a.csv", "text/csv", "x,y").hashCode(),
                ExportFile.text("a.csv", "text/csv", "x,y").hashCode());
    }

    @Test
    void anExportSaysSomethingAtDistinctPaths() {
        // Nothing to export is Optional.empty(), not an empty list.
        assertThrows(IllegalArgumentException.class, () -> new UserExport(List.of()));
        assertThrows(IllegalArgumentException.class, () -> UserExport.of(
                ExportFile.text("a.json", "application/json", "{}"),
                ExportFile.text("a.json", "application/json", "[]")));

        UserExport export = UserExport.of(
                ExportFile.text("a.json", "application/json", "{}"),
                ExportFile.text("b.csv", "text/csv", "x"));
        assertEquals(3, export.totalBytes());
    }

    @Test
    void theDefaultLeavesTheHostToAskTheMapForm() {
        UserDataHandler eraseOnly = userId -> { };

        // Empty by default: the host then asks exportUser and wraps a non-empty map as data.json.
        assertEquals(Optional.empty(), eraseOnly.exportFiles("u-1"));
    }

    @Test
    void theOldPatternNameIsAnAliasOfTheShared() {
        assertEquals(DocStore.KEY_SELECTOR_PATTERN, DocStore.BACKEND_OWNED_PATTERN);
    }
}
