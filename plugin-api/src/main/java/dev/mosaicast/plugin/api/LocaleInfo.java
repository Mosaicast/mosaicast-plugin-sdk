// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * One language the host knows about (ARCHITECTURE §12.7).
 *
 * @param code       the locale code, lower-cased: {@code en}, {@code de}, {@code pt-br}
 * @param nativeName the language's name in its own language — {@code Nederlands}, not {@code Dutch}
 * @param isDefault  whether this is the site default: the last fallback for anything stored per locale
 * @since 0.10.0
 */
public record LocaleInfo(String code, String nativeName, boolean isDefault) {
}
