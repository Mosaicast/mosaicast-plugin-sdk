// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A plugin's part of a person's data export: the files the host packs under {@code plugins/<pluginId>/} in
 * the export ZIP (ARCHITECTURE §12.8). Returned by {@link UserDataHandler#exportFiles(String)}.
 *
 * <p>Nothing to export is {@link java.util.Optional#empty()}, not an empty list — so this record refuses an
 * empty one, and an export that exists always says something.
 *
 * @param files the files, at distinct paths; never {@code null} or empty. Copied, so the list cannot change
 *              afterwards
 * @since 0.19.0
 */
public record UserExport(List<ExportFile> files) {

    /**
     * The most bytes one plugin's part may hold, summed over its files: {@value} (32 MiB).
     *
     * <p>The host refuses a larger part and records it as failed in the export's outcome, rather than
     * truncating it — a partial answer to a legal request is worse than one that says it is incomplete. An
     * operator may lower the cap; nothing raises it. A plugin holding more than this per person should export
     * what is theirs and link to the rest, not hand over an archive of the site.
     */
    public static final long MAX_BYTES = 32L * 1024 * 1024;

    /**
     * How long the host waits for {@link UserDataHandler#exportFiles(String)} before recording the plugin's
     * part as failed: 60 seconds.
     *
     * <p>The export runs as a background job, so this is generous; it exists so one stuck plugin cannot hold a
     * person's whole export.
     */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * Validates and copies.
     *
     * @throws NullPointerException     if {@code files} or any element is {@code null}
     * @throws IllegalArgumentException if {@code files} is empty or two files share a path
     */
    public UserExport {
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (files.isEmpty()) {
            throw new IllegalArgumentException(
                    "an export has at least one file — return Optional.empty() when there is nothing to export");
        }
        Set<String> paths = new HashSet<>();
        for (ExportFile file : files) {
            if (!paths.add(file.path())) {
                throw new IllegalArgumentException("two export files share the path '" + file.path() + "'");
            }
        }
    }

    /**
     * An export of the given files.
     *
     * @param files the files; at least one, at distinct paths
     * @return the export
     */
    public static UserExport of(ExportFile... files) {
        return new UserExport(List.of(Objects.requireNonNull(files, "files")));
    }

    /**
     * The total size of every file — what {@link #MAX_BYTES} is measured against.
     *
     * @return the summed byte count
     */
    public long totalBytes() {
        long total = 0;
        for (ExportFile file : files) {
            total += file.size();
        }
        return total;
    }
}
