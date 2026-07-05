// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Optional;

/**
 * Read access to a plugin's declared configuration values (ARCHITECTURE §7.2).
 *
 * <p>A plugin declares its config fields (name, type, default, {@code editableBy}) in the manifest.
 * The host renders a generic admin form from that declaration — <strong>plugins never build their own
 * config UI</strong> — and exposes the resolved values here, read-only.
 */
public interface PluginConfig {

    /**
     * Reads a configured value and coerces it to the requested type.
     *
     * @param key  the declared config field name; never {@code null}
     * @param type the target type; never {@code null}
     * @param <T>  the value type
     * @return the value, or {@link Optional#empty()} if the field is unset and has no default
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * Reads a configured value, falling back to the given default when unset.
     *
     * @param key      the declared config field name; never {@code null}
     * @param type     the target type; never {@code null}
     * @param fallback the value to return when the field is unset; may be {@code null}
     * @param <T>      the value type
     * @return the configured value, or {@code fallback} when absent
     */
    default <T> T get(String key, Class<T> type, T fallback) {
        return get(key, type).orElse(fallback);
    }
}
