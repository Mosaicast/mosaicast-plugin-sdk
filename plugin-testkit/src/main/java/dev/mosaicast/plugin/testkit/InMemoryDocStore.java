// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.DocEntry;
import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.OwnedDocEntry;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * An in-memory {@link DocStore} for testing plugin backends without a database (ARCHITECTURE §13.5).
 *
 * <p>Values are serialized to JSON on {@link #put} (via Jackson) and deserialized on {@link #get},
 * mirroring the real store's round-trip semantics and last-write-wins behavior. Not thread-safe — test
 * doubles run single-threaded.
 *
 * <p>Like the host, it rejects a {@link #put} whose key does not match {@link DocStore#KEY_PATTERN}, so a
 * key the frontend could never address (e.g. one containing {@code /}) fails in your tests rather than in
 * production.
 *
 * <p><strong>The {@link ScopeType#USER} scope behaves as it does in production</strong>: this store stands
 * in for a backend, which has no calling user, so every method throws
 * {@link UnsupportedOperationException} for it. To set up per-user data — the thing the frontend writes
 * and {@link #queryAcrossUsers(String)} aggregates — take a caller's view with {@link #asUser(UUID)},
 * which is the test's stand-in for the host resolving {@code me} from a session.
 *
 * <pre>{@code
 * InMemoryDocStore store = new InMemoryDocStore();
 * UUID alice = UUID.randomUUID();
 * store.asUser(alice).put(Scope.user(), "mark:s2e04:b3", true);   // as the frontend would
 *
 * plugin.register(ctx);                                            // backend aggregates
 * assertEquals(1, store.queryAcrossUsers("mark:").size());
 * }</pre>
 */
public final class InMemoryDocStore implements DocStore {

    private static final Pattern KEY = Pattern.compile(KEY_PATTERN);

    private final ObjectMapper mapper;
    // Insertion-ordered so query() results are deterministic in tests.
    private final Map<Scope, Map<String, JsonNode>> data;
    // USER data lives apart, keyed by owner: Scope.user() normalizes every user to the same sentinel, so
    // the scope alone cannot tell two people's partitions apart.
    private final Map<UUID, Map<String, JsonNode>> userData;
    // Non-null only on the view returned by asUser(...): the caller a USER scope resolves to.
    private final UUID caller;

    /** Creates a store with a default {@link ObjectMapper}. */
    public InMemoryDocStore() {
        this(JsonMapper.builder().build());
    }

    /**
     * Creates a store with a caller-supplied mapper (e.g. one configured with modules).
     *
     * @param mapper the mapper used for value (de)serialization; never {@code null}
     */
    public InMemoryDocStore(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.data = new LinkedHashMap<>();
        this.userData = new LinkedHashMap<>();
        this.caller = null;
    }

    /** A view sharing the backing store's maps by reference — a write through it is a write to both. */
    private InMemoryDocStore(InMemoryDocStore backing, UUID caller) {
        this.mapper = backing.mapper;
        this.data = backing.data;
        this.userData = backing.userData;
        this.caller = caller;
    }

    /**
     * A view of this store as seen by one user — the test's stand-in for the host resolving {@code me}
     * from a session.
     *
     * <p>On the returned store a {@link ScopeType#USER} scope addresses {@code userId}'s partition instead
     * of throwing; every other scope behaves exactly as on this one, and both share the same data. Use it
     * to seed what a frontend would have written, then aggregate from the backend store with
     * {@link #queryAcrossUsers(String)}.
     *
     * <p>Note this is a <em>test</em> affordance with no counterpart in the contract: no production
     * {@code DocStore} can write into another user's partition.
     *
     * @param userId the user whose partition {@link Scope#user()} resolves to on the returned view; never
     *               {@code null}
     * @return a store sharing this one's data, with a calling user
     * @since 0.5.0
     */
    public InMemoryDocStore asUser(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return new InMemoryDocStore(this, userId);
    }

    /**
     * The documents in a user's partition, as {@link #asUser(UUID)} would see them.
     *
     * @param userId the partition owner; never {@code null}
     * @return that user's documents, keyed; never {@code null}, empty when the user has written nothing
     * @since 0.5.0
     */
    public List<DocEntry> docsOf(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        List<DocEntry> out = new ArrayList<>();
        userData.getOrDefault(userId, Map.of())
                .forEach((key, value) -> out.add(new DocEntry(key, value)));
        return out;
    }

    @Override
    public <T> Optional<T> get(Scope scope, String key, Class<T> type) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        JsonNode node = documents(scope, false).get(key);
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
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "key '" + key + "' does not match " + KEY_PATTERN
                            + " — it would not be addressable from the frontend");
        }
        documents(scope, true).put(key, mapper.valueToTree(value));
    }

    @Override
    public boolean delete(Scope scope, String key) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        return documents(scope, false).remove(key) != null;
    }

    @Override
    public List<DocEntry> query(Scope scope, String keyPrefix) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        List<DocEntry> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : documents(scope, false).entrySet()) {
            if (e.getKey().startsWith(keyPrefix)) {
                out.add(new DocEntry(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    @Override
    public List<OwnedDocEntry> queryAcrossUsers(String keyPrefix) {
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        List<OwnedDocEntry> out = new ArrayList<>();
        userData.forEach((userId, docs) -> docs.forEach((key, value) -> {
            if (key.startsWith(keyPrefix)) {
                out.add(new OwnedDocEntry(userId, key, value));
            }
        }));
        return out;
    }

    /**
     * The document map a call addresses, refusing the {@code USER} scope exactly as a backend does unless
     * this is an {@link #asUser(UUID)} view.
     *
     * @param scope  the scope addressed
     * @param create whether a missing map should be created (writes) or an empty one returned (reads)
     */
    private Map<String, JsonNode> documents(Scope scope, boolean create) {
        if (scope.type() == ScopeType.USER) {
            if (caller == null) {
                throw new UnsupportedOperationException(
                        "USER scope has no meaning on a backend: there is no calling user. "
                                + "Use store().queryAcrossUsers(...) to aggregate, or address an entity scope.");
            }
            return create
                    ? userData.computeIfAbsent(caller, u -> new LinkedHashMap<>())
                    : userData.getOrDefault(caller, mutableEmpty());
        }
        return create
                ? data.computeIfAbsent(scope, s -> new LinkedHashMap<>())
                : data.getOrDefault(scope, mutableEmpty());
    }

    /** A throwaway map for reads against a scope nothing was ever written to — mutable, so remove() works. */
    private static Map<String, JsonNode> mutableEmpty() {
        return new LinkedHashMap<>();
    }
}
