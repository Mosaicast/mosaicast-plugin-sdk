// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.time.Instant;

/**
 * One stored file (ARCHITECTURE §11).
 *
 * <p>The {@code ref} is the file's whole identity on this surface — an opaque, host-assigned string. It is
 * what you keep in a document or a schema column to point at the file, and what {@link PluginBlobs#urlFor}
 * turns into a URL a browser can load. Do not parse it, and do not construct one: a ref you invent names
 * nothing, and the host resolves refs only inside your own plugin's namespace.
 *
 * <p>{@code mime} is what the host <em>determined</em> the file to be, not what the uploader claimed. The
 * two differ exactly when someone lied, so storing this value rather than the declared one is the point.
 *
 * @param ref       the host-assigned identifier; opaque, stable, never reused
 * @param filename  the original filename as supplied, for display; may be {@code null}, never a path the
 *                  host resolves
 * @param mime      the content type the host determined from the bytes
 * @param size      the size in bytes
 * @param updatedAt when the file was stored
 */
public record BlobInfo(String ref, String filename, String mime, long size, Instant updatedAt) {
}
