// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Objects;

/**
 * The access requirement of an episode: publicly listenable, or gated behind a tier (ARCHITECTURE
 * §4.1/§10).
 *
 * <p><strong>The host makes the access decision, not the plugin.</strong> This type is exposed only so
 * a plugin can read an episode's requirement; whether a given user satisfies it is resolved by the host
 * before any list reaches the plugin (§10). In v1 (RSS only) everything is {@link #PUBLIC}.
 *
 * <p>Modelled as a sealed interface permitting exactly two shapes: the {@link #PUBLIC} singleton and a
 * {@link Tier} carrying the required tier reference.
 */
public sealed interface Access permits Access.Public, Access.Tier {

    /** The shared, publicly-accessible marker. */
    Public PUBLIC = new Public();

    /** Public access — no gating. Use the {@link Access#PUBLIC} singleton. */
    record Public() implements Access {
    }

    /**
     * Tier-gated access.
     *
     * @param ref the reference of the tier required to unlock the episode; never {@code null}
     */
    record Tier(String ref) implements Access {
        /** Canonical constructor validating the tier reference. */
        public Tier {
            Objects.requireNonNull(ref, "ref");
        }
    }

    /**
     * Convenience factory for {@link Tier}.
     *
     * @param ref the required tier reference; never {@code null}
     * @return a tier-gated access requirement
     */
    static Access tier(String ref) {
        return new Tier(ref);
    }
}
