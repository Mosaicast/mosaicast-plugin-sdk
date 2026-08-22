// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * One result a {@link SearchProvider} contributes to the site's search.
 *
 * <p>{@code subpath} is a path below {@code /p/<pluginId>/}, exactly the coordinate
 * {@link ShareMetadataProvider#metaFor(String)} receives in the other direction — so the host can turn a
 * hit into a real, deep-linkable, crawlable URL while keeping ownership of the URL shape. It is the same
 * notion of "a plugin-owned object" that {@link Tags} keys assignments by; use one key for both rather
 * than inventing a second coordinate for the same thing.
 *
 * <p><strong>{@code score} orders your own hits and nothing else.</strong> The host does not merge it with
 * core's ranking: a plugin's number and Postgres {@code ts_rank} are not on one scale, and pretending
 * otherwise produces an order nobody can explain. Results are grouped by source, so what this value
 * decides is which of <em>your</em> hits comes first.
 *
 * @param subpath the path below {@code /p/<pluginId>/}; never {@code null}, may be empty (the plugin root)
 * @param title   what a visitor should read as the result's heading; never {@code null}
 * @param snippet a short excerpt giving the hit context; never {@code null}, may be empty
 * @param score   this plugin's own relevance, higher is better; compared only against this plugin's hits
 * @since 0.9.0
 */
public record SearchHit(String subpath, String title, String snippet, double score) {}
