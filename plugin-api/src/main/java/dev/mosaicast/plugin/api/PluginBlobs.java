// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * File storage for plugins that declare a {@code blobs} block in their manifest (ARCHITECTURE §11).
 *
 * <p>Until this existed a plugin could declare relational tables, publish documents and serve a deep-linked
 * page, but could not accept a <em>file</em>. It could display an image from any host on the web — the
 * host's CSP allows that — and not one from the site's own podcaster, which is an odd place for a podcast
 * platform to draw the line. A wiki wants diagrams; a show-notes plugin wants a chapter image; neither
 * should have to send a podcaster to find an image host first.
 *
 * <p><strong>Declare it, or you do not get it.</strong> Like {@link SchemaStore}, this surface is opt-in and
 * bounded by what the manifest asks for, so a plugin's storage appetite is visible to whoever installs it:
 *
 * <pre>{@code
 * "blobs": { "maxFileBytes": 5242880, "quotaBytes": 268435456,
 *            "mimeTypes": ["image/png", "image/jpeg", "image/webp"] }
 * }</pre>
 *
 * <p>{@link PluginContext#blobs()} is {@code null} without it. The operator caps both numbers and
 * intersects the type list with the install's own allow-list, so what you get may be less than you asked
 * for — {@link #quota()} reports the truth.
 *
 * <p><strong>Namespaced, like everything else here.</strong> Files live under {@code plugin/<yourId>/} and a
 * {@code ref} is resolved only within it. Another plugin's file is not merely refused, it is unnameable —
 * the same property {@code SchemaStore} has for tables and {@code ctx.route.navigate} has for URLs.
 *
 * <p><strong>What the host checks on write</strong>, in this order: the size against your effective
 * per-file ceiling, the declared type against the effective allow-list, then the <em>actual</em> type read
 * from the leading bytes — which must also be allowed. A file whose content disagrees with its claimed type
 * is refused, and what gets stored is what the bytes say. SVG is never accepted, by the same reasoning that
 * keeps it out of branding uploads: it is a script container wearing an image's file extension.
 *
 * <pre>{@code
 * PluginBlobs blobs = ctx.blobs();
 * BlobInfo stored;
 * try (InputStream in = Files.newInputStream(diagram)) {
 *     stored = blobs.put("architecture.png", "image/png", in);
 * }
 * // Keep the ref, not the URL: the URL is derived, the ref is the identity.
 * ctx.store().put(scope, "diagram", Map.of("ref", stored.ref()));
 * String src = blobs.urlFor(stored.ref());
 * }</pre>
 *
 * <p><strong>This is the backend half.</strong> A plugin's own UI uploads through {@code ctx.blobs} in the
 * TypeScript SDK, which reaches the same store over HTTP under the manifest's {@code data} floors. Reach for
 * the Java side for what only a backend can do: fetching something on a schedule, and deleting the files a
 * document no longer refers to. Nothing collects orphans for you — a file outlives the document that named
 * it, and only your plugin knows which those are.
 *
 * @since 0.8.0
 */
public interface PluginBlobs {

    /**
     * Stores a file and returns its identity.
     *
     * <p>The stream is read to the end and <strong>not</strong> closed — close it yourself, ideally with
     * try-with-resources around the call.
     *
     * @param filename the original filename, for display only; may be {@code null}. The host never treats
     *                 it as a path or as a key, and two files may share one
     * @param mime     the content type you believe this is; the host verifies it against the bytes and
     *                 stores what it finds
     * @param data     the content
     * @return the stored file's identity, carrying the host-determined MIME type
     * @throws IllegalArgumentException if the type is not allowed for this plugin, the bytes contradict the
     *                                  declared type, the file exceeds the per-file ceiling, or storing it
     *                                  would exceed the quota — all four are conditions your plugin can see
     *                                  coming via {@link #quota()} and the manifest it wrote itself
     */
    BlobInfo put(String filename, String mime, InputStream data);

    /**
     * Looks up a file's metadata without reading it.
     *
     * @param ref the identifier from {@link #put}
     * @return the file's metadata, or empty if no such file exists in this plugin's namespace — including
     *         when the ref belongs to another plugin, which is indistinguishable on purpose
     */
    Optional<BlobInfo> stat(String ref);

    /**
     * Opens a file for reading.
     *
     * <p>Streaming, not "all the bytes": the same door serves a 5 KB diagram and a 100 MB recording, and the
     * caller closes what it opens.
     *
     * @param ref the identifier from {@link #put}
     * @return an open stream positioned at the start; the caller must close it
     * @throws IllegalArgumentException if no such file exists in this plugin's namespace
     */
    InputStream open(String ref);

    /**
     * Deletes a file. Idempotent — deleting what is not there is not an error.
     *
     * @param ref the identifier from {@link #put}
     * @return {@code true} if a file was deleted, {@code false} if there was nothing to delete
     */
    boolean delete(String ref);

    /**
     * Lists this plugin's files, newest first.
     *
     * <p>Paged from the first call rather than offering an "everything" variant: a plugin that has been
     * accepting uploads for a year has more files than a list should ever materialise at once.
     *
     * @param page zero-based page number
     * @param size page size; the host caps it
     * @return the page's files, newest first; empty past the end
     */
    List<BlobInfo> list(int page, int size);

    /**
     * The URL a browser can load this file from — host-served, same origin, under your plugin's namespace.
     *
     * <p>Derive it, never store it. A stored URL is a copy of a decision the host is entitled to change;
     * the {@code ref} is the thing that stays true.
     *
     * @param ref the identifier from {@link #put}
     * @return a root-relative URL; it is not checked that the file exists
     */
    String urlFor(String ref);

    /**
     * How much room is left, as the host currently sees it.
     *
     * @return the effective quota and current usage; never {@code null}
     */
    BlobQuota quota();
}
