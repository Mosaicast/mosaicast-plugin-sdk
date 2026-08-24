// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import org.pf4j.ExtensionPoint;

/**
 * Optional extension point: a plugin says whether it renders anything at a subpath under
 * {@code /p/<pluginId>/}, so the host can answer <strong>404</strong> where there is nothing.
 *
 * <p>Without this, every subpath below a plugin that declares a {@code page} slot answers {@code 200}. A
 * wiki page that was never written, a mistyped slug and the URL of a page deleted last year all render the
 * plugin's not-found view inside a {@code 200 OK}. That is a soft-404, which ARCHITECTURE §6.6 rules out
 * for core's own routes ("real HTTP 404s for unknown episodes/routes, no soft-404"), and it means a crawler
 * indexes a plugin's typos and its deleted pages. The host cannot fix it alone: <strong>only the plugin
 * knows whether a subpath is a thing.</strong>
 *
 * <p>Same shape as {@link SitemapProvider}, {@link ShareMetadataProvider} and {@link SearchProvider}: a
 * plugin MAY implement it in addition to {@link PluginBackend}, and there is no manifest declaration beyond
 * naming the class in {@code backend.extensions}.
 *
 * <h2>Absent means today's behaviour</h2>
 *
 * <p>This is optional in the full sense — a plugin that does not implement it keeps answering {@code 200}
 * for everything under its subtree, exactly as before. Nothing starts 404-ing because the interface exists.
 * The host only asks plugins that declare a {@code page} slot; a plugin with no page has no subtree to be
 * asked about.
 *
 * <h2>Do not use {@link ShareMetadataProvider} for this</h2>
 *
 * <p>The two look interchangeable and are not, which is the whole reason this is a separate interface. A
 * plugin's subtree legitimately contains views with nothing to describe: the wiki's {@code _search/<term>}
 * and {@code _admin} return an empty {@link ShareMetadataProvider#metaFor(String)} <em>on purpose</em>,
 * because a search result page is not worth an OpenGraph card. Reading "no share metadata" as "no page"
 * would 404 those working routes. {@code metaFor} answers <em>how to describe this</em>;
 * {@link #hasRoute(String)} answers <em>whether this exists</em>.
 *
 * <h2>It runs on a request</h2>
 *
 * <p>Like {@link SearchProvider}, this is called while a visitor waits for their page — keep it cheap and
 * bounded, a lookup rather than a scan. <strong>A provider that throws is logged and skipped, and the route
 * answers {@code 200}</strong>: the same failure posture the other extension points have, and deliberate
 * here, because a broken plugin must not turn a working page into a 404.
 *
 * <h2>This decides the status line, not the body</h2>
 *
 * <p>The response body is unchanged either way. The shell renders its own not-found view, so a plugin does
 * not need to produce one, and a plugin that already renders its own keeps doing so — the answer here only
 * changes whether that arrives as {@code 200} or {@code 404}.
 *
 * @since 0.9.1
 */
public interface PageRouteProvider extends ExtensionPoint {

    /**
     * Whether this plugin renders something at a subpath under {@code /p/<pluginId>/}.
     *
     * <p>Answer for the path alone. Access is not the question — a page that exists but is not for this
     * visitor is the host's to gate, and reporting it missing here would leak nothing but would also 404 a
     * page an author can legitimately open.
     *
     * @param subpath the path below {@code /p/<pluginId>/}, received exactly as
     *                {@link ShareMetadataProvider#metaFor(String)} receives it: never {@code null},
     *                <strong>empty at the plugin root</strong> — and the root is a route, so answering
     *                {@code false} for {@code ""} 404s the plugin's own landing page
     * @return {@code true} to serve the page as before, {@code false} for a real {@code 404}
     */
    boolean hasRoute(String subpath);
}
