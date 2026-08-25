// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.Translation;
import dev.mosaicast.plugin.api.TranslationException;
import dev.mosaicast.plugin.api.TranslationRequest;
import dev.mosaicast.plugin.api.TranslationResult;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Translation} double that records what it was asked to translate (ARCHITECTURE §12.7).
 *
 * <p>It marks the text rather than pretending to translate it — {@code "[nl] Hello"} — so an assertion pins
 * <em>that the plugin asked for Dutch</em>, which is the part worth testing. A double that returned plausible
 * Dutch would let a test pass while the plugin sent the wrong target language.
 *
 * <p>{@link #failing(TranslationException.Reason)} covers the branch that actually breaks in production: the
 * provider is down, or the admin removed it. A plugin whose only tested path is the happy one has not decided
 * what to do when somebody else's service says no.
 *
 * @since 0.10.0
 */
public final class FakeTranslation implements Translation {

    private final List<TranslationRequest> requests = new ArrayList<>();
    private final TranslationException.Reason failWith;
    private boolean available = true;

    private FakeTranslation(TranslationException.Reason failWith) {
        this.failWith = failWith;
    }

    /** A translator that always succeeds. */
    public static FakeTranslation marking() {
        return new FakeTranslation(null);
    }

    /** A translator that always refuses, for the reason given. */
    public static FakeTranslation failing(TranslationException.Reason reason) {
        return new FakeTranslation(reason);
    }

    /**
     * Makes {@link #available()} answer {@code false} while {@link #translate} still works — the race the
     * real host has, where an admin removes the provider between the check and the call.
     */
    public FakeTranslation unavailable() {
        this.available = false;
        return this;
    }

    /** Every request, in order. */
    public List<TranslationRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public TranslationResult translate(TranslationRequest request) throws TranslationException {
        requests.add(request);
        if (failWith != null) {
            throw new TranslationException(failWith, "the fake translator was told to refuse");
        }
        String detected = TranslationRequest.AUTO.equals(request.from()) ? null : request.from();
        return new TranslationResult("[%s] %s".formatted(request.to(), request.text()), detected, "fake", false);
    }

    @Override
    public boolean available() {
        return available;
    }
}
