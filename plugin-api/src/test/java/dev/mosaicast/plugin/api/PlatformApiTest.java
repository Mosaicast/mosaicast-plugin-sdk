// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tripwire for the version anchor.
 *
 * <p>The contract version is mirrored in four places — {@code build.gradle.kts}, {@code package.json},
 * {@link PlatformApi#VERSION} and the TypeScript {@code PLATFORM_API_VERSION} — and CI's
 * {@code version-parity} job fails if they drift. This test is the Java end of that: bumping the constant
 * without running {@code scripts/set-version.sh} breaks the build here, next to the change, instead of in
 * a workflow.
 */
class PlatformApiTest {

    @Test
    void versionIsPinned() {
        assertEquals("0.5.0", PlatformApi.VERSION);
    }

    @Test
    void versionIsAThreePartSemVer() {
        // The host compares major.minor exactly, so a malformed value would reject every plugin.
        assertTrue(PlatformApi.VERSION.matches("\\d+\\.\\d+\\.\\d+(?:[-+].*)?"),
                "not a SemVer string: " + PlatformApi.VERSION);
    }
}
