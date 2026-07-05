// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * The single source of truth for the plugin contract version.
 *
 * <p>Every plugin manifest ({@code plugin.json}) declares the {@code platformApi} version it was built
 * against. At startup the host compares that declaration against {@link #VERSION} and
 * <strong>rejects incompatible plugins</strong> (ARCHITECTURE §7.2). This is the stability anchor of the
 * whole system.
 *
 * <p><strong>SemVer is sacred.</strong> A breaking change to any contract type in this package is a
 * <em>major</em> bump of this constant. The value here MUST match the TypeScript re-export
 * {@code PLATFORM_API_VERSION} in {@code @mosaicast/plugin-sdk} byte-for-byte — they move together.
 */
public final class PlatformApi {

    private PlatformApi() {
        // Constants holder — not instantiable.
    }

    /**
     * The current plugin contract version (SemVer).
     *
     * <p>Mirror of the npm package version and the TypeScript {@code PLATFORM_API_VERSION} constant.
     */
    public static final String VERSION = "0.1.0";
}
