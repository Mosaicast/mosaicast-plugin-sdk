// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.BlobInfo;
import dev.mosaicast.plugin.api.BlobQuota;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryPluginBlobsTest {

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ByteArrayInputStream zeros(int count) {
        return new ByteArrayInputStream(new byte[count]);
    }

    private static List<String> filenames(List<BlobInfo> blobs) {
        return blobs.stream().map(BlobInfo::filename).toList();
    }

    @Test
    void storesReadsAndDeletes() throws IOException {
        InMemoryPluginBlobs blobs = new InMemoryPluginBlobs();

        BlobInfo stored = blobs.put("diagram.png", "image/png", bytes("pretend-png"));

        assertEquals("diagram.png", stored.filename());
        assertEquals("image/png", stored.mime());
        assertEquals(11, stored.size());
        assertEquals(Optional.of(stored), blobs.stat(stored.ref()));
        try (InputStream in = blobs.open(stored.ref())) {
            assertEquals("pretend-png", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertTrue(blobs.delete(stored.ref()));
        // Idempotent: deleting what is gone is not an error, so cleanup code needs no existence check.
        assertFalse(blobs.delete(stored.ref()));
        assertTrue(blobs.stat(stored.ref()).isEmpty());
    }

    @Test
    void refusesATypeThePluginDidNotDeclare() {
        InMemoryPluginBlobs blobs = new InMemoryPluginBlobs().withMimeTypes(Set.of("image/png"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> blobs.put("notes.pdf", "application/pdf", bytes("%PDF")));

        assertTrue(refused.getMessage().contains("application/pdf"), refused.getMessage());
        assertEquals(0, blobs.size());
    }

    @Test
    void refusesAFileOverThePerFileCeiling() {
        InMemoryPluginBlobs blobs = new InMemoryPluginBlobs().withLimits(64, 1024);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> blobs.put("big.png", "image/png", zeros(65)));

        assertTrue(refused.getMessage().contains("larger than"), refused.getMessage());
    }

    @Test
    void refusesAWriteThatWouldExceedTheQuota() {
        // The failure a plugin actually meets in production: each file is fine, the collection is not.
        InMemoryPluginBlobs blobs = new InMemoryPluginBlobs().withLimits(64, 100);
        blobs.put("a.png", "image/png", zeros(60));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> blobs.put("b.png", "image/png", zeros(60)));

        assertTrue(refused.getMessage().contains("quota"), refused.getMessage());
        assertEquals(40, blobs.quota().remainingBytes());
    }

    @Test
    void standsInForTheHostsContentCheck() {
        // The fake cannot read file formats; naming the file that should be refused is how a test exercises
        // the plugin's handling of the host's answer without a second copy of the sniffing rules.
        InMemoryPluginBlobs blobs = new InMemoryPluginBlobs().rejectContent("actually-a-script.png");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> blobs.put("actually-a-script.png", "image/png", bytes("<svg onload=...>")));

        assertTrue(refused.getMessage().contains("does not match"), refused.getMessage());
    }

    @Test
    void listsNewestFirstAndPages() {
        InMemoryPluginBlobs blobs = new InMemoryPluginBlobs();
        for (int i = 1; i <= 12; i++) {
            blobs.put("f" + i + ".png", "image/png", zeros(1));
        }

        // Twelve, so the page boundary lands where a ref sorted as text would have gone wrong.
        assertEquals(List.of("f12.png", "f11.png", "f10.png", "f9.png", "f8.png"), filenames(blobs.list(0, 5)));
        assertEquals(List.of("f2.png", "f1.png"), filenames(blobs.list(2, 5)));
        assertTrue(blobs.list(9, 5).isEmpty());
    }

    @Test
    void reportsUsageAgainstTheEffectiveCeilings() {
        InMemoryPluginBlobs blobs = new InMemoryPluginBlobs().withLimits(500, 2000);
        blobs.put("a.png", "image/png", zeros(300));

        assertEquals(new BlobQuota(300, 2000, 500), blobs.quota());
    }

    @Test
    void isNullOnAContextThatDeclaresNoBlobs() {
        // The branch every plugin author has to write, and the default the test kit pushes them into.
        assertNull(new FakePluginContext().blobs());
        assertNull(new FakePluginContext(new InMemoryDocStore(), new MapPluginConfig(),
                new FakeFeedAccess(Map.of()), null).blobs());

        FakePluginContext wired = new FakePluginContext(new InMemoryDocStore(), new MapPluginConfig(),
                new FakeFeedAccess(Map.of()), null, new InMemoryPluginBlobs());
        assertNotNull(wired.blobs());
    }
}
