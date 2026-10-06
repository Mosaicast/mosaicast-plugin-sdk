// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One file of a plugin's part of a person's data export (ARCHITECTURE §12.8), in whatever format the plugin
 * chooses — a JSON document its own import reads back, a CSV, a picture the person uploaded.
 *
 * <p>The host packs it into the export ZIP at {@code plugins/<pluginId>/<path>}. The plugin id is the host's
 * to add, so a path cannot reach another plugin's folder or core's.
 *
 * <p>Equality compares the bytes, not the array reference, so a test can {@code assertEquals} two exports.
 *
 * @param path      where the file goes, relative to the plugin's folder in the ZIP; must match
 *                  {@link #PATH_PATTERN}
 * @param mediaType the file's media type, e.g. {@code application/json} or {@code text/csv}; never blank
 * @param bytes     the file's content; never {@code null}, may be empty. Copied on the way in and out, so
 *                  neither side can change it afterwards
 * @since 0.19.0
 */
public record ExportFile(String path, String mediaType, byte[] bytes) {

    /**
     * The grammar of {@link #path()}: {@value}.
     *
     * <p>One or more {@code [A-Za-z0-9._-]} segments joined by {@code /}: no leading slash, no {@code .} or
     * {@code ..} segment, no empty segment, no backslash. The same grammar as a manifest's
     * {@code frontend.entry}, and for the same reason — the string becomes a path the plugin does not get to
     * escape. The host refuses a file outside it; this record refuses it first, so a bad path fails in your
     * tests.
     */
    public static final String PATH_PATTERN =
            "^(?!\\.{1,2}(/|$))[A-Za-z0-9._-]+(/(?!\\.{1,2}(/|$))[A-Za-z0-9._-]+)*$";

    private static final Pattern PATH = Pattern.compile(PATH_PATTERN);

    /**
     * Validates and copies.
     *
     * @throws NullPointerException     if any component is {@code null}
     * @throws IllegalArgumentException if {@code path} does not match {@link #PATH_PATTERN} or
     *                                  {@code mediaType} is blank
     */
    public ExportFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(bytes, "bytes");
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException(
                    "export path '" + path + "' does not match " + PATH_PATTERN
                            + " — a relative path of [A-Za-z0-9._-] segments, no '.' or '..'");
        }
        if (mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        bytes = bytes.clone();
    }

    /**
     * A UTF-8 text file — JSON, CSV, Markdown.
     *
     * @param path      where the file goes; must match {@link #PATH_PATTERN}
     * @param mediaType its media type, e.g. {@code application/json}; never blank
     * @param text      the content, written as UTF-8; never {@code null}
     * @return the file
     */
    public static ExportFile text(String path, String mediaType, String text) {
        Objects.requireNonNull(text, "text");
        return new ExportFile(path, mediaType, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The file's content.
     *
     * @return a copy of the bytes; never {@code null}
     */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * The content's length, without copying it.
     *
     * @return the number of bytes
     */
    public int size() {
        return bytes.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ExportFile other
                && path.equals(other.path)
                && mediaType.equals(other.mediaType)
                && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, mediaType, Arrays.hashCode(bytes));
    }

    @Override
    public String toString() {
        return "ExportFile[path=" + path + ", mediaType=" + mediaType + ", size=" + bytes.length + "]";
    }
}
