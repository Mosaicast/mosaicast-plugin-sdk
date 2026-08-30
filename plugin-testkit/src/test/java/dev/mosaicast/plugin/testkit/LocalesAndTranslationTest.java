// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.LocaleInfo;
import dev.mosaicast.plugin.api.TranslationException;
import dev.mosaicast.plugin.api.TranslationRequest;
import dev.mosaicast.plugin.api.TranslationResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The 0.10.0 additions: the language registry and the host-mediated translator (ARCHITECTURE §12.7). */
class LocalesAndTranslationTest {

    @Test
    void aFreshSiteHasExactlyOneLanguage() {
        // What a plugin listing languages has to survive, and the case an author never tries by hand.
        assertEquals(List.of("en"), FakeLocales.englishOnly().available().stream().map(LocaleInfo::code).toList());
        assertEquals("en", FakeLocales.englishOnly().defaultLocale());
    }

    @Test
    void contentLanguagesCanExceedShellLanguages() {
        // A Dutch imprint on an English-only site: the asymmetry the two lists exist to express.
        FakeLocales locales = FakeLocales.englishOnly().withContent("nl");

        assertEquals(List.of("en"), locales.available().stream().map(LocaleInfo::code).toList());
        assertEquals(List.of("en", "nl"), locales.contentLocales().stream().map(LocaleInfo::code).toList());
        assertTrue(locales.isContentLocale("NL"));
        assertFalse(locales.isContentLocale("sv"));
        assertFalse(locales.isContentLocale(null));
    }

    @Test
    void aLanguageNamesItselfInItsOwnLanguage() {
        LocaleInfo dutch = FakeLocales.englishOnly().withUi("nl").available().stream()
                .filter(locale -> locale.code().equals("nl"))
                .findFirst()
                .orElseThrow();

        assertEquals("Nederlands", dutch.nativeName());
        assertFalse(dutch.isDefault());
    }

    @Test
    void aRequestNormalizesItsLanguagesAndRefusesAMissingTarget() {
        TranslationRequest request = TranslationRequest.of("Hallo", "DE ", " NL");

        assertEquals("de", request.from());
        assertEquals("nl", request.to());
        assertEquals(TranslationRequest.Format.TEXT, request.format());
        assertEquals(TranslationRequest.AUTO, TranslationRequest.of("Hallo", "nl").from());
        assertThrows(IllegalArgumentException.class, () -> TranslationRequest.of("Hallo", " "));
    }

    @Test
    void theFakeTranslatorMarksTheTextAndRecordsTheRequest() throws TranslationException {
        FakeTranslation translation = FakeTranslation.marking();

        TranslationResult result = translation.translate(TranslationRequest.of("Hello", "nl"));

        assertEquals("[nl] Hello", result.text());
        // Auto means the caller did not say; the double does not invent a detection it was never given.
        assertNull(result.detectedSourceLanguage());
        assertFalse(result.fromCache());
        assertEquals(1, translation.requests().size());
    }

    @Test
    void aRefusalCarriesAReasonAndWhetherRetryingCouldHelp() {
        FakeTranslation translation = FakeTranslation.failing(TranslationException.Reason.RATE_LIMITED);

        TranslationException refused = assertThrows(TranslationException.class,
                () -> translation.translate(TranslationRequest.of("Hello", "nl")));

        assertEquals(TranslationException.Reason.RATE_LIMITED, refused.reason());
        assertTrue(refused.retryable());
        assertFalse(new TranslationException(TranslationException.Reason.NO_PROVIDER, "none").retryable());
    }

    @Test
    void aContextHasLanguagesButNoTranslatorUntilOneIsWiredIn() throws TranslationException {
        FakePluginContext ctx = new FakePluginContext();

        // Never null: every site has a language. Unlike tags/schema/blobs, absence is not expressible.
        assertEquals("en", ctx.locales().defaultLocale());
        // Null until the manifest declares the kind *and* an operator selects a provider.
        assertNull(ctx.translation());

        ctx.withTranslation(FakeTranslation.marking()).withLocales(FakeLocales.englishOnly().withUi("de"));

        assertEquals("[de] Hello", ctx.translation().translate(TranslationRequest.of("Hello", "de")).text());
        assertTrue(ctx.locales().isContentLocale("de"));
    }

    @Test
    void bothGatesProduceTheSameNull() {
        // Since 0.11.0 there are two independent reasons for a null translator: a manifest with no
        // `external.kinds: ["translation"]`, and an operator who configured no provider. The contract keeps
        // them indistinguishable, so the fake does too — a plugin that branched on which would be testing
        // something it cannot observe on a real host.
        FakePluginContext undeclared = new FakePluginContext();
        FakePluginContext unconfigured = new FakePluginContext().withTranslation(null);

        assertNull(undeclared.translation());
        assertNull(unconfigured.translation());

        // And going back to null is how a test says the operator removed the provider mid-run.
        assertNull(new FakePluginContext().withTranslation(FakeTranslation.marking())
                .withTranslation(null).translation());
    }

    @Test
    void availableCanBeFalseWhileTranslateStillWorks() {
        // The real race: an admin removes the provider between the check and the call.
        FakeTranslation translation = FakeTranslation.marking().unavailable();

        assertFalse(translation.available());
    }
}
