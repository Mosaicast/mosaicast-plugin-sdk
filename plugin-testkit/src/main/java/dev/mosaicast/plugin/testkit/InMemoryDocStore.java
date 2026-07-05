// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.Scope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An in-memory {@link DocStore} for testing plugin backends without a database (ARCHITECTURE §13.5).
 *
 * <p>Values are serialized to JSON on {@link #put} (via Jackson) and deserialized on {@link #get},
 * mirroring the real store's round-trip semantics and last-write-wins behavior. Not thread-safe — test
 * doubles run single-threaded.
 */
public final class InMemoryDocStore implements DocStore {

    private final ObjectMapper mapper;
    // Insertion-ordered so query() results are deterministic in tests.
    private final Map<Scope, Map<String, JsonNode>> data = new LinkedHashMap<>();

    /** Creates a store with a default {@link ObjectMapper}. */
    public InMemoryDocStore() {
        this(new ObjectMapper());
    }

    /**
     * Creates a store with a caller-supplied mapper (e.g. one configured with modules).
     *
     * @param mapper the mapper used for value (de)serialization; never {@code null}
     */
    public InMemoryDocStore(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public <T> Optional<T> get(Scope scope, String key, Class<T> type) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        JsonNode node = data.getOrDefault(scope, Map.of()).get(key);
        if (node == null) {
            return Optional.empty();
        }
        return Optional.of(mapper.convertValue(node, type));
    }

    @Override
    public void put(Scope scope, String key, Object value) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        data.computeIfAbsent(scope, s -> new LinkedHashMap<>())
                .put(key, mapper.valueToTree(value));
    }

    @Override
    public List<JsonNode> query(Scope scope, String keyPrefix) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        List<JsonNode> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : data.getOrDefault(scope, Map.of()).entrySet()) {
            if (e.getKey().startsWith(keyPrefix)) {
                out.add(e.getValue());
            }
        }
        return out;
    }
}
