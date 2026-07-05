// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import org.pf4j.ExtensionPoint;
import java.util.Optional;

/**
 * Optional extension point: a plugin supplies share metadata for its deep links (ARCHITECTURE
 * §6.4/§7.4).
 *
 * <p>A plugin MAY implement this in addition to {@link PluginBackend}; the wiki does in v1 as the
 * reference. When the host serves a {@code /p/<pluginId>/…} URL it calls {@link #metaFor(String)} with
 * the subpath so link scrapers get meaningful OpenGraph tags for plugin content (e.g. a wiki page).
 */
public interface ShareMetadataProvider extends ExtensionPoint {

    /**
     * Returns share metadata for a deep link under this plugin's {@code /p/<pluginId>/} space.
     *
     * @param subpath the path below {@code /p/<pluginId>/}; never {@code null}, may be empty (plugin root)
     * @return the metadata for that path, or {@link Optional#empty()} to fall back to site-level OG
     */
    Optional<OgMeta> metaFor(String subpath);
}
