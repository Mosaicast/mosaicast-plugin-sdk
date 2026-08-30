// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * Machine translation, mediated by the host (ARCHITECTURE §16, §12.7).
 *
 * <p><strong>Reaching this interface takes a manifest declaration.</strong> A plugin whose
 * {@code external.kinds} does not contain {@code "translation"} sees {@code null} from
 * {@link PluginContext#translation()} whatever the operator configured — the same shape {@code blobs} and
 * {@code tags} have, and for a sharper reason: a call here spends the operator's money on somebody else's
 * metered API, so what a plugin may spend should be readable off its manifest before it is installed.
 *
 * <p><strong>The host owns the provider, the credentials and the cache.</strong> A plugin never speaks to a
 * translation service itself: the site admin selects one, or none, and this is the only way through. The
 * alternative — every plugin shipping its own API key — is a support burden for the operator and a bill they
 * never agreed to. It also means the host can cache across plugins, so two plugins translating the same
 * paragraph cost one call.
 *
 * <p><strong>Machine output is a draft.</strong> Store it marked as one and let a human confirm it. Core's own
 * legal-page prefill hands the admin an unsaved draft for exactly this reason, which is the same reason
 * §12.6 declines to ship legal texts at all: a translation nobody has read is not made safer by being
 * automatic.
 *
 * <p><strong>Calls are slow, metered and refusable.</strong> A translation crosses the network to somebody
 * else's service. Do not put one on a request path a visitor waits behind; do it on
 * {@link PluginContext#onSchedule(java.time.Duration, Runnable)} or behind an explicit editor action, and be
 * ready for {@link TranslationException}.
 *
 * @since 0.10.0
 */
public interface Translation {

    /**
     * Translates one string.
     *
     * @param request what to translate, and into what
     * @return the translation
     * @throws TranslationException when the host refuses or the provider fails — no provider configured, a
     *                              rate limit, a saturated host, a timeout. Let it surface rather than
     *                              falling back to the untranslated string: a caller that cannot tell a
     *                              translation from an original will store the original as one.
     * @throws IllegalArgumentException if the target language is blank or the text exceeds the host's limit
     */
    TranslationResult translate(TranslationRequest request) throws TranslationException;

    /**
     * Whether a call would even be attempted.
     *
     * <p>Advisory, not a guarantee: an admin can remove the provider between this call and the next
     * {@link #translate(TranslationRequest)}. Use it to skip work and to disable UI, and still handle the
     * exception.
     *
     * @return whether a provider is selected and configured
     */
    boolean available();
}
