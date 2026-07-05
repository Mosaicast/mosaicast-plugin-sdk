// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry a plugin contributes to the site's {@code sitemap.xml} (ARCHITECTURE §6.6).
 *
 * @param loc          an absolute-path location under {@code /p/<pluginId>/…}; never {@code null}
 * @param lastModified the last-modified timestamp for the {@code <lastmod>} hint, or {@code null} to omit it
 */
public record SitemapUrl(String loc, Instant lastModified) {

    /** Canonical constructor validating the location. */
    public SitemapUrl {
        Objects.requireNonNull(loc, "loc");
    }
}
