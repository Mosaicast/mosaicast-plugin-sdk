// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * One entry of the site's shared tag vocabulary, with how much carries it.
 *
 * <p><strong>{@code tag} is the canonical key, {@code label} is presentation.</strong> The host owns the
 * normalisation rule (trim, collapse internal whitespace, casefold) and applies it on every path into the
 * vocabulary — feed ingest included — so {@code Maritime}, {@code maritime} and {@code "maritime "} are one
 * tag rather than three. A plugin may pass any spelling to {@link Tags}; what it gets back, and what it
 * should store and compare on, is always the key. The label is kept from first use and is what a visitor
 * should read.
 *
 * <p>The two counts are the vocabulary's reach, and they are counted differently on purpose:
 * {@link #episodes()} is site-wide (every episode carrying the tag, whoever tagged it), while
 * {@link #subjects()} counts <strong>only the calling plugin's own</strong> subjects. A plugin cannot see
 * how much another plugin has tagged — that would leak the size of a store it cannot read.
 *
 * @param tag      the canonical key; never {@code null}
 * @param label    the display label the host kept from first use; never {@code null}
 * @param episodes how many episodes carry this tag, across every source; never negative
 * @param subjects how many of <em>this plugin's</em> subjects carry it; never negative
 * @since 0.9.0
 */
public record TagInfo(String tag, String label, int episodes, int subjects) {}
