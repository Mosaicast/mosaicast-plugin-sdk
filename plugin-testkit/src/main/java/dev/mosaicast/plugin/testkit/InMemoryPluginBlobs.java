// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.BlobInfo;
import dev.mosaicast.plugin.api.BlobQuota;
import dev.mosaicast.plugin.api.PluginBlobs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An in-memory {@link PluginBlobs} for testing a plugin backend without a host (ARCHITECTURE §13.5).
 *
 * <p><strong>It refuses what the host refuses, for the same reasons.</strong> The per-file ceiling, the
 * quota and the MIME allow-list are all enforced here, because a plugin that only ever meets an accepting
 * fake will discover its 20 MB upload path on a real install. The defaults are deliberately small — 1 MB per
 * file, 8 MB total, common raster images — so a test that pushes real quantities of data has to say so:
 *
 * <pre>{@code
 * var blobs = new InMemoryPluginBlobs()
 *         .withLimits(5 * 1024 * 1024, 64 * 1024 * 1024)
 *         .withMimeTypes(Set.of("image/png", "audio/mpeg"));
 * var ctx = new FakePluginContext(new InMemoryDocStore(), new MapPluginConfig(),
 *         new FakeFeedAccess(Map.of()), null, blobs);
 * }</pre>
 *
 * <p>What it does <em>not</em> do is sniff content: the host reads the leading bytes and refuses a file whose
 * content contradicts its declared type, and reimplementing that here would be a second, diverging copy of a
 * security rule. So a test can store two bytes as {@code image/png} — the check it is exercising is the
 * plugin's handling of a refusal, and {@link #rejectContent(String)} makes the host's answer reproducible
 * without pretending to be a file-type parser.
 *
 * <p>Refs are sequential ({@code blob-1}, {@code blob-2}, …) so an assertion can name one, which is the one
 * way this fake is deliberately unlike the host — real refs are opaque and a plugin must not build them.
 * Not thread-safe.
 */
public final class InMemoryPluginBlobs implements PluginBlobs {

    /** Small enough that a test storing anything substantial has to raise it on purpose. */
    private static final long DEFAULT_MAX_FILE_BYTES = 1024L * 1024L;

    private static final long DEFAULT_QUOTA_BYTES = 8L * 1024L * 1024L;

    private static final Set<String> DEFAULT_MIME_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    /** Insertion-ordered so {@link #list} can reverse it into newest-first without a stored clock. */
    private final Map<String, Stored> blobs = new LinkedHashMap<>();

    private long maxFileBytes = DEFAULT_MAX_FILE_BYTES;
    private long quotaBytes = DEFAULT_QUOTA_BYTES;
    private Set<String> mimeTypes = DEFAULT_MIME_TYPES;
    private Set<String> rejectedFilenames = Set.of();
    private int nextRef = 1;

    private record Stored(BlobInfo info, byte[] data) {
    }

    /**
     * Sets the ceilings this fake enforces, mirroring a manifest's {@code blobs} block.
     *
     * @param maxFileBytes the per-file ceiling
     * @param quotaBytes   the total ceiling
     * @return this, for chaining
     */
    public InMemoryPluginBlobs withLimits(long maxFileBytes, long quotaBytes) {
        this.maxFileBytes = maxFileBytes;
        this.quotaBytes = quotaBytes;
        return this;
    }

    /**
     * Sets the accepted content types, mirroring the effective allow-list an install would grant.
     *
     * @param mimeTypes the accepted types
     * @return this, for chaining
     */
    public InMemoryPluginBlobs withMimeTypes(Set<String> mimeTypes) {
        this.mimeTypes = Set.copyOf(mimeTypes);
        return this;
    }

    /**
     * Makes {@link #put} refuse a specific filename, standing in for the host's content sniffing.
     *
     * <p>The host refuses a file whose bytes contradict its declared type; this fake cannot tell, so it
     * lets a test name the file that should be refused and assert what the plugin does about it.
     *
     * @param filename the filename to refuse
     * @return this, for chaining
     */
    public InMemoryPluginBlobs rejectContent(String filename) {
        var next = new java.util.HashSet<>(rejectedFilenames);
        next.add(filename);
        this.rejectedFilenames = Set.copyOf(next);
        return this;
    }

    @Override
    public BlobInfo put(String filename, String mime, InputStream data) {
        Objects.requireNonNull(mime, "mime");
        Objects.requireNonNull(data, "data");
        if (!mimeTypes.contains(mime)) {
            throw new IllegalArgumentException(
                    "content type not allowed for this plugin: " + mime + " (allowed: " + mimeTypes + ")");
        }
        if (rejectedFilenames.contains(filename)) {
            throw new IllegalArgumentException("content does not match the declared type: " + mime);
        }
        byte[] bytes = readAll(data);
        if (bytes.length > maxFileBytes) {
            throw new IllegalArgumentException(
                    "file is larger than this plugin may store: " + bytes.length + " > " + maxFileBytes);
        }
        if (usedBytes() + bytes.length > quotaBytes) {
            throw new IllegalArgumentException(
                    "storing this file would exceed the plugin's quota of " + quotaBytes + " bytes");
        }
        String ref = "blob-" + nextRef++;
        BlobInfo info = new BlobInfo(ref, filename, mime, bytes.length, Instant.now());
        blobs.put(ref, new Stored(info, bytes));
        return info;
    }

    @Override
    public Optional<BlobInfo> stat(String ref) {
        Stored stored = blobs.get(ref);
        return stored == null ? Optional.empty() : Optional.of(stored.info());
    }

    @Override
    public InputStream open(String ref) {
        Stored stored = blobs.get(ref);
        if (stored == null) {
            throw new IllegalArgumentException("no such blob: " + ref);
        }
        return new ByteArrayInputStream(stored.data());
    }

    @Override
    public boolean delete(String ref) {
        return blobs.remove(ref) != null;
    }

    @Override
    public List<BlobInfo> list(int page, int size) {
        if (page < 0 || size <= 0) {
            throw new IllegalArgumentException("page must be >= 0 and size > 0");
        }
        // Insertion order reversed, not the ref sorted: `blob-10` sorts before `blob-9` as text, and a test
        // that stored ten files would silently get them in an order the host would never produce.
        List<BlobInfo> newestFirst = new ArrayList<>(blobs.values().stream().map(Stored::info).toList());
        java.util.Collections.reverse(newestFirst);
        int from = Math.min(page * size, newestFirst.size());
        return List.copyOf(newestFirst.subList(from, Math.min(from + size, newestFirst.size())));
    }

    @Override
    public String urlFor(String ref) {
        return "/api/plugins/test/blob/" + ref;
    }

    @Override
    public BlobQuota quota() {
        return new BlobQuota(usedBytes(), quotaBytes, maxFileBytes);
    }

    /**
     * The bytes stored so far — the same number {@link #quota()} reports, exposed for direct assertions.
     *
     * @return the total size of stored files
     */
    public long usedBytes() {
        return blobs.values().stream().mapToLong(stored -> stored.data().length).sum();
    }

    /**
     * The number of files stored.
     *
     * @return the count
     */
    public int size() {
        return blobs.size();
    }

    /**
     * The bytes stored under a ref, for asserting that a plugin wrote what it meant to.
     *
     * @param ref the identifier returned by {@link #put}
     * @return the stored bytes, or empty when there is no such file
     */
    public Optional<byte[]> bytesOf(String ref) {
        Stored stored = blobs.get(ref);
        return stored == null ? Optional.empty() : Optional.of(stored.data().clone());
    }

    private static byte[] readAll(InputStream data) {
        try {
            return data.readAllBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("could not read the upload: " + e.getMessage(), e);
        }
    }
}
