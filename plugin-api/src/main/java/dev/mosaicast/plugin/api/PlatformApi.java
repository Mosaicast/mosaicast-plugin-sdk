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
 * <p><strong>The host matches {@code major.minor} exactly.</strong> A plugin declaring {@code 0.3.x} is
 * rejected the moment the host runs {@code 0.4.0} — there is no forward or backward tolerance. While the
 * SDK is pre-1.0 a breaking change is therefore a <em>minor</em> bump ({@code 0.3.0} → {@code 0.4.0});
 * from {@code 1.0.0} on, ordinary SemVer applies and breaking means major.
 *
 * <p>The value here MUST match the TypeScript re-export {@code PLATFORM_API_VERSION} in
 * {@code @mosaicast/plugin-sdk} byte-for-byte — they move together, and CI's {@code version-parity} job
 * fails the build if they drift. Bump them with {@code scripts/set-version.sh}, never by hand.
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
    public static final String VERSION = "0.4.0";
}
