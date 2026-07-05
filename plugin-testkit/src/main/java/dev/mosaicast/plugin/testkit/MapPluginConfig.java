// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mosaicast.plugin.api.PluginConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A map-backed {@link PluginConfig} for testing (ARCHITECTURE §13.5).
 *
 * <p>Configured values are stored as-is and coerced to the requested type on {@link #get} via Jackson,
 * matching how the real host resolves declared config fields. Not thread-safe.
 */
public final class MapPluginConfig implements PluginConfig {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object> values;

    /** Creates an empty config. */
    public MapPluginConfig() {
        this(Map.of());
    }

    /**
     * Creates a config seeded with the given values.
     *
     * @param values the initial config field values; copied defensively, never {@code null}
     */
    public MapPluginConfig(Map<String, Object> values) {
        Objects.requireNonNull(values, "values");
        this.values = new HashMap<>(values);
    }

    /**
     * Sets a config field value.
     *
     * @param key   the field name; never {@code null}
     * @param value the value; never {@code null}
     * @return this instance, for chaining
     */
    public MapPluginConfig with(String key, Object value) {
        values.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        return this;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Object raw = values.get(key);
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(mapper.convertValue(raw, type));
    }
}
