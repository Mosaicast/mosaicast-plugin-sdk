// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.List;

/**
 * Which languages this site has (ARCHITECTURE §12.7).
 *
 * <p><strong>Two lists, and the difference is the point.</strong> {@link #available()} is what the shell can
 * <em>render</em> in; {@link #contentLocales()} is what the admin permits text to be <em>authored</em> in.
 * They overlap on most sites and come apart on the ones that matter: an operator can require a Dutch imprint
 * without offering a Dutch UI, and a plugin that decided what to accept from {@code available()} would
 * silently refuse the language its operator actually asked for.
 *
 * <p><strong>Validate writes against {@link #isContentLocale(String)}.</strong> The browser's list is a hint;
 * a locale that arrives at your backend is input. A row stored under a language nobody offers is invisible to
 * every reader and to your own editor's tab strip, which is a page that quietly does not exist.
 *
 * <p>The answer can change while a plugin is running — an admin edits it on a page — so read it when you need
 * it rather than caching it at {@code register()} time.
 *
 * @since 0.10.0
 */
public interface Locales {

    /**
     * The languages the shell can render in, ordered by code.
     *
     * @return the UI languages; never {@code null}, never empty (English is always present)
     */
    List<LocaleInfo> available();

    /**
     * The languages content may be authored in, ordered by code.
     *
     * <p>Named {@code contentLocales} rather than {@code content} because {@code content()} on its own reads
     * like it returns the content.
     *
     * @return the authoring languages; never {@code null}, never empty
     */
    List<LocaleInfo> contentLocales();

    /**
     * The site default: the last fallback for anything stored per locale.
     *
     * @return the default locale code; never {@code null}
     */
    String defaultLocale();

    /**
     * Whether content may be authored in a language. The check to run before storing anything per locale.
     *
     * @param code a locale code; {@code null} or blank answers {@code false}
     * @return whether the language is one this site authors content in
     */
    boolean isContentLocale(String code);
}
