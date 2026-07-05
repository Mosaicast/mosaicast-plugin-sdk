// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import org.pf4j.ExtensionPoint;
import java.util.List;

/**
 * Optional extension point: a plugin contributes its pages to the site's {@code sitemap.xml}
 * (ARCHITECTURE §6.6/§7.4).
 *
 * <p>A plugin MAY implement this in addition to {@link PluginBackend}; the wiki does in v1. The host
 * calls {@link #urls()} when generating the dynamic sitemap and merges the results with core URLs.
 */
public interface SitemapProvider extends ExtensionPoint {

    /**
     * Returns the plugin's public URLs for inclusion in {@code sitemap.xml}.
     *
     * @return the plugin's sitemap entries; never {@code null}, possibly empty
     */
    List<SitemapUrl> urls();
}
