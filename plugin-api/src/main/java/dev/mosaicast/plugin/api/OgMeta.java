// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Objects;

/**
 * OpenGraph/Twitter share metadata a plugin supplies for one of its deep links (ARCHITECTURE §6.4).
 *
 * <p>Link scrapers (WhatsApp/Discord/Facebook) do not run JS, so the host injects these tags
 * server-side when serving a {@code /p/<pluginId>/…} URL, asking the plugin's
 * {@link ShareMetadataProvider}. No provider or no match falls back to site-level OG.
 *
 * @param title       the share title; never {@code null}
 * @param description the share description; never {@code null}, may be empty
 * @param imageUrl    an absolute image URL, or {@code null} to let the host fall back to the podcast cover
 */
public record OgMeta(String title, String description, String imageUrl) {

    /** Canonical constructor validating the required text fields. */
    public OgMeta {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
    }
}
