// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Locale;
import java.util.Objects;

/**
 * One thing to translate (ARCHITECTURE §12.7).
 *
 * <p>Provider-independent on purpose: LibreTranslate's {@code {q, source, target, format}}, Google's
 * {@code {q[], source, target}} and Microsoft's {@code {Text:[…]}} all reduce to this, so a plugin written
 * against it keeps working when the operator switches provider.
 *
 * @param from   a locale code, or {@link #AUTO} to let the provider detect it
 * @param to     a locale code; required
 * @param format {@link Format#TEXT} or {@link Format#HTML}
 * @since 0.10.0
 */
public record TranslationRequest(String text, String from, String to, Format format) {

    /** Ask the provider to detect the source language. */
    public static final String AUTO = "auto";

    /**
     * What the text is.
     *
     * <p>Markdown is neither. Send it as {@link #TEXT} and expect links and code fences to come back
     * mangled — a translator does not know they are markup. Splitting markdown into translatable blocks is
     * the caller's job, and a real one.
     */
    public enum Format {
        TEXT,
        HTML
    }

    public TranslationRequest {
        Objects.requireNonNull(text, "text");
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("A translation needs a target language");
        }
        from = from == null || from.isBlank() ? AUTO : from.trim().toLowerCase(Locale.ROOT);
        to = to.trim().toLowerCase(Locale.ROOT);
        format = format == null ? Format.TEXT : format;
    }

    /** Plain text, source language detected by the provider. */
    public static TranslationRequest of(String text, String to) {
        return new TranslationRequest(text, AUTO, to, Format.TEXT);
    }

    /** Plain text, source language stated — cheaper and more accurate than detection when you know it. */
    public static TranslationRequest of(String text, String from, String to) {
        return new TranslationRequest(text, from, to, Format.TEXT);
    }
}
