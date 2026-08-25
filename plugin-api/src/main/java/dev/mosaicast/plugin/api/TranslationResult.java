// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * What came back from a translation (ARCHITECTURE §12.7).
 *
 * @param text                   the translated text
 * @param detectedSourceLanguage what the provider thinks the source was, or {@code null} when it does not say
 * @param providerId             which provider produced it — the admin picks one per site, and may change it,
 *                               so store this next to anything you keep
 * @param fromCache              whether the host answered from its cache instead of calling the provider
 * @since 0.10.0
 */
public record TranslationResult(
        String text, String detectedSourceLanguage, String providerId, boolean fromCache) {
}
