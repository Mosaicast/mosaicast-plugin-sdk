// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.LocaleInfo;
import dev.mosaicast.plugin.api.Locales;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A {@link Locales} double (ARCHITECTURE §12.7).
 *
 * <p>Defaults to English only, which is what a fresh install looks like — a plugin that lists languages has
 * to survive there being exactly one. {@link #withContent(String...)} adds authoring languages
 * <em>without</em> adding UI ones, because that asymmetry is the thing worth testing: a site can require a
 * Dutch imprint with an English-only shell, and a plugin that filled its editor tabs from
 * {@link #available()} would offer the wrong list.
 *
 * @since 0.10.0
 */
public final class FakeLocales implements Locales {

    private static final String SOURCE = "en";

    private final Set<String> ui = new LinkedHashSet<>(List.of(SOURCE));
    private final Set<String> content = new LinkedHashSet<>(List.of(SOURCE));
    private String defaultLocale = SOURCE;

    /** English only, English default. */
    public static FakeLocales englishOnly() {
        return new FakeLocales();
    }

    /** Adds languages the shell can render in. They become content languages too, as on a real site. */
    public FakeLocales withUi(String... codes) {
        for (String code : codes) {
            ui.add(normalize(code));
            content.add(normalize(code));
        }
        return this;
    }

    /** Adds languages content may be authored in but the shell does not offer. */
    public FakeLocales withContent(String... codes) {
        for (String code : codes) {
            content.add(normalize(code));
        }
        return this;
    }

    /**
     * Sets the site default.
     *
     * @param code the default locale; added to the content languages, since the host guarantees it is one
     */
    public FakeLocales withDefault(String code) {
        this.defaultLocale = normalize(code);
        content.add(defaultLocale);
        return this;
    }

    @Override
    public List<LocaleInfo> available() {
        return infos(ui);
    }

    @Override
    public List<LocaleInfo> contentLocales() {
        return infos(content);
    }

    @Override
    public String defaultLocale() {
        return defaultLocale;
    }

    @Override
    public boolean isContentLocale(String code) {
        return code != null && !code.isBlank() && content.contains(normalize(code));
    }

    private List<LocaleInfo> infos(Set<String> codes) {
        List<LocaleInfo> infos = new ArrayList<>();
        for (String code : codes.stream().sorted().toList()) {
            Locale locale = Locale.forLanguageTag(code);
            String name = locale.getDisplayLanguage(locale);
            infos.add(new LocaleInfo(code,
                    name == null || name.isBlank() ? code.toUpperCase(Locale.ROOT) : name,
                    code.equals(defaultLocale)));
        }
        return List.copyOf(infos);
    }

    private static String normalize(String code) {
        return code == null ? SOURCE : code.trim().toLowerCase(Locale.ROOT);
    }
}
