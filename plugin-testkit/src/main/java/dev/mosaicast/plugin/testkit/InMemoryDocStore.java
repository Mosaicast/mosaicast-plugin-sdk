// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.CrossUserStore;
import dev.mosaicast.plugin.api.DocEntry;
import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.OwnedDocEntry;
import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * and {@link #acrossUsers()} aggregates — take a caller's view with {@link #asUser(UUID)},
 * which is the test's stand-in for the host resolving {@code me} from a session.
 *
 * <p>It enforces {@code data.backendOwned} the same way: declare patterns with
 * {@link #withBackendOwned(String...)} and a write through a client view is refused while this store's own
 * writes go through, so a test can prove the key your backend computes is not forgeable over HTTP.
 *
 * <p>And {@code data.keyFloors} (since 0.19.0): declare one with {@link #withKeyFloor(String, String, String)}
 * and a client view whose role is below it cannot see the key — a single read or write throws where the host
 * answers 403, and {@link #query(Scope, String)} leaves the key out, as the host's listing does. Take a view
 * with a role through {@link #asUser(UUID, Role)} or {@link #asAnonymous()}:
 *
 * <pre>{@code
 * store.withKeyFloor("import:*", "podcaster", null);
 * store.put(Scope.site(), "import:42", job);                         // the backend writes it
 * assertTrue(store.asAnonymous().query(Scope.site(), "import:").isEmpty());   // a visitor does not see it
 * }</pre>
 *
 * <pre>{@code
 * InMemoryDocStore store = new InMemoryDocStore();
 * UUID alice = UUID.randomUUID();
 * store.asUser(alice).put(Scope.user(), "mark:s2e04:b3", true);   // as the frontend would
 *
 * plugin.register(ctx);                                            // backend aggregates
 * assertEquals(1, store.acrossUsers().query("mark:").size());
 * }</pre>
 */
public final class InMemoryDocStore implements DocStore {

    private static final Pattern KEY = Pattern.compile(KEY_PATTERN);
    private static final Pattern KEY_SELECTOR = Pattern.compile(KEY_SELECTOR_PATTERN);
    private static final List<String> FLOOR_ROLES = List.of("anonymous", "fan", "podcaster", "admin");

    private final ObjectMapper mapper;
    // Insertion-ordered so query() results are deterministic in tests.
    private final Map<Scope, Map<String, JsonNode>> data;
    // USER data lives apart, keyed by owner: Scope.user() normalizes every user to the same sentinel, so
    // the scope alone cannot tell two people's partitions apart.
    private final Map<UUID, Map<String, JsonNode>> userData;
    // Shared with every view: the manifest's data.backendOwned patterns, which bind clients, not the backend.
    private final List<String> backendOwned;
    // Shared with every view: the manifest's data.keyFloors, which bind clients, not the backend.
    private final List<KeyFloor> keyFloors;
    // Whether this is a client view (asUser / asAnonymous) rather than the backend's store.
    private final boolean client;
    // Non-null only on an asUser(...) view: the caller a USER scope resolves to.
    private final UUID caller;
    // The client view's role; null for the backend and for an anonymous view.
    private final Role role;

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
        this.backendOwned = new ArrayList<>();
        this.keyFloors = new ArrayList<>();
        this.client = false;
        this.caller = null;
        this.role = null;
    }

    /** A client view sharing the backing store's maps by reference — a write through it is a write to both. */
    private InMemoryDocStore(InMemoryDocStore backing, UUID caller, Role role) {
        this.mapper = backing.mapper;
        this.data = backing.data;
        this.userData = backing.userData;
        this.backendOwned = backing.backendOwned;
        this.keyFloors = backing.keyFloors;
        this.client = true;
        this.caller = caller;
        this.role = role;
    }

    /**
     * A view of this store as seen by one user — the test's stand-in for the host resolving {@code me}
     * from a session.
     *
     * <p>On the returned store a {@link ScopeType#USER} scope addresses {@code userId}'s partition instead
     * of throwing; every other scope behaves exactly as on this one, and both share the same data. Use it
     * to seed what a frontend would have written, then aggregate from the backend store with
     * {@link #acrossUsers()}.
     *
     * <p>Note this is a <em>test</em> affordance with no counterpart in the contract: no production
     * {@code DocStore} can write into another user's partition.
     *
     * <p>The caller is a {@link Role#FAN} — the ordinary signed-in listener — which only matters once a
     * {@link #withKeyFloor(String, String, String) key floor} is declared; use {@link #asUser(UUID, Role)} to
     * pick another.
     *
     * @param userId the user whose partition {@link Scope#user()} resolves to on the returned view; never
     *               {@code null}
     * @return a store sharing this one's data, with a calling user
     * @since 0.5.0
     */
    public InMemoryDocStore asUser(UUID userId) {
        return asUser(userId, Role.FAN);
    }

    /**
     * A view of this store as seen by one user with a given role — {@link #asUser(UUID)}, for a test about
     * {@link #withKeyFloor(String, String, String) key floors}.
     *
     * @param userId the user whose partition {@link Scope#user()} resolves to; never {@code null}
     * @param role   the caller's role, which key floors are compared against; never {@code null}
     * @return a store sharing this one's data, with a calling user
     * @since 0.19.0
     */
    public InMemoryDocStore asUser(UUID userId, Role role) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        return new InMemoryDocStore(this, userId, role);
    }

    /**
     * A view of this store as an anonymous visitor sees it over HTTP.
     *
     * <p>Every key floor applies at {@code anonymous}, and a {@link ScopeType#USER} scope throws
     * {@link IllegalStateException}, where the host answers 401 — with no session there is no partition.
     *
     * @return a store sharing this one's data, with no calling user
     * @since 0.19.0
     */
    public InMemoryDocStore asAnonymous() {
        return new InMemoryDocStore(this, null, null);
    }

    /**
     * Declares keys this plugin's backend owns, as the manifest's {@code data.backendOwned} does.
     *
     * <p>Writes through {@link #asUser(UUID)} — the test's stand-in for a client request — are refused for
     * a matching key with an {@link IllegalStateException}, where the host answers 403. Writes through this
     * store are not: it stands in for the backend, and the whole point of the declaration is that the
     * backend keeps writing. Reads are untouched on both.
     *
     * <p>Each pattern is an exact key, a prefix ending in {@code *}, or the bare {@code *}
     * ({@link DocStore#BACKEND_OWNED_PATTERN}); a malformed one throws here rather than being discovered
     * when the host rejects your manifest at load. Patterns accumulate across calls, and the declaration is
     * ignored for {@link ScopeType#USER} scopes exactly as it is in production.
     *
     * @param patterns the key patterns the backend owns; never {@code null}, each matching
     *                 {@link DocStore#BACKEND_OWNED_PATTERN}
     * @return this instance, for chaining
     * @throws IllegalArgumentException if a pattern is malformed
     * @since 0.6.0
     */
    public InMemoryDocStore withBackendOwned(String... patterns) {
        Objects.requireNonNull(patterns, "patterns");
        for (String pattern : patterns) {
            Objects.requireNonNull(pattern, "pattern");
            requireSelector(pattern, "backendOwned");
            backendOwned.add(pattern);
        }
        return this;
    }

    /**
     * Declares a key floor, as one entry of the manifest's {@code data.keyFloors} does.
     *
     * <p>A client view — {@link #asUser(UUID, Role)}, {@link #asAnonymous()} — whose role is below the floor
     * cannot reach a matching key: {@link #get}, {@link #put} and {@link #delete} throw
     * {@link IllegalStateException} where the host answers 403, and {@link #query} leaves the key out, as the
     * host's listing does. This store, the backend's, is unaffected. Several floors matching one key combine
     * to the strictest, per direction; {@link ScopeType#USER} scopes are exempt, as in production.
     *
     * <p>One thing this cannot check: the host rejects at load a key floor <em>below</em> your
     * {@code data.readableBy} / {@code writableBy}, which this store does not know. Declare floors that
     * raise.
     *
     * @param pattern    the keys it covers — an exact key, a prefix ending in {@code *}, or the bare {@code *}
     *                   ({@link DocStore#KEY_SELECTOR_PATTERN}); never {@code null}
     * @param readableBy the lowest role that may read a matching key ({@code anonymous}, {@code fan},
     *                   {@code podcaster} or {@code admin}), or {@code null} to leave reads at the plugin floor
     * @param writableBy the lowest role that may write one — not {@code anonymous} — or {@code null}
     * @return this instance, for chaining
     * @throws IllegalArgumentException if the pattern is malformed, a role is not one of the four, the write
     *                                  floor is {@code anonymous}, or both floors are {@code null} — each of
     *                                  which the host rejects at load
     * @since 0.19.0
     */
    public InMemoryDocStore withKeyFloor(String pattern, String readableBy, String writableBy) {
        Objects.requireNonNull(pattern, "pattern");
        requireSelector(pattern, "keyFloors");
        if (readableBy == null && writableBy == null) {
            throw new IllegalArgumentException(
                    "key floor '" + pattern + "' raises neither floor — the host rejects the manifest at load");
        }
        int read = readableBy == null ? 0 : rank(readableBy);
        int write = writableBy == null ? 0 : rank(writableBy);
        if (writableBy != null && write == 0) {
            throw new IllegalArgumentException("key floor '" + pattern + "': writableBy may not be anonymous");
        }
        keyFloors.add(new KeyFloor(pattern, read, write));
        return this;
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
        refuseBelowKeyFloor(scope, key, true);
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
        refuseIfBackendOwned(scope, key, "write");
        refuseBelowKeyFloor(scope, key, false);
        documents(scope, true).put(key, mapper.valueToTree(value));
    }

    @Override
    public boolean delete(Scope scope, String key) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        refuseIfBackendOwned(scope, key, "delete");
        refuseBelowKeyFloor(scope, key, false);
        return documents(scope, false).remove(key) != null;
    }

    @Override
    public List<DocEntry> query(Scope scope, String keyPrefix) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        List<DocEntry> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : documents(scope, false).entrySet()) {
            // Below the reader's key floor: left out of the page rather than failing it, as the host lists.
            if (e.getKey().startsWith(keyPrefix) && !belowKeyFloor(scope, e.getKey(), true)) {
                out.add(new DocEntry(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    /**
     * Every user partition of this store, read the way {@link PluginContext#allUsers()} reads them — the
     * {@link CrossUserStore} the host hands a plugin that declares {@code data.readsAllUsers}.
     *
     * <p>Always available here, because a test seeding and asserting on per-user data needs it whatever
     * the plugin declares. The gate is on the context, not the store: {@link FakePluginContext#allUsers()}
     * stays {@code null} until {@link FakePluginContext#withReadsAllUsers()}, as the host's does without
     * the declaration — so a backend that forgot to declare fails in its tests.
     *
     * @return a live, read-only view over every user partition; never {@code null}
     * @since 0.16.0
     */
    public CrossUserStore acrossUsers() {
        return this::ownedEntries;
    }

    private List<OwnedDocEntry> ownedEntries(String keyPrefix) {
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
     * Refuses a client write to a backend-owned key, as the host's 403 does.
     *
     * <p>Only a client is bound: the backend store is not a client view, so its write goes
     * through. {@code USER} scopes are exempt, since the backend cannot write one at all.
     *
     * @param scope  the scope addressed
     * @param key    the key addressed
     * @param action the verb to name in the message
     */
    private void refuseIfBackendOwned(Scope scope, String key, String action) {
        if (!client || scope.type() == ScopeType.USER) {
            return;
        }
        for (String pattern : backendOwned) {
            if (covers(pattern, key)) {
                throw new IllegalStateException(
                        "a client cannot " + action + " '" + key + "': it is backend-owned by '"
                                + pattern + "' (the host answers 403)");
            }
        }
    }

    /** Refuses a client read or write below the key's floor, as the host's key-floor 403 does. */
    private void refuseBelowKeyFloor(Scope scope, String key, boolean read) {
        if (belowKeyFloor(scope, key, read)) {
            throw new IllegalStateException(
                    "a " + (role == null ? "anonymous" : role.name().toLowerCase(Locale.ROOT)) + " client cannot "
                            + (read ? "read" : "write") + " '" + key + "': it is below the key's floor in "
                            + "data.keyFloors (the host answers 403)");
        }
    }

    /**
     * Whether a client view's role is below the strictest key floor matching {@code key} in one direction.
     * Always {@code false} for the backend and for {@code USER} scopes, which key floors do not reach.
     */
    private boolean belowKeyFloor(Scope scope, String key, boolean read) {
        if (!client || scope.type() == ScopeType.USER) {
            return false;
        }
        int floor = 0;
        for (KeyFloor kf : keyFloors) {
            if (covers(kf.pattern(), key)) {
                floor = Math.max(floor, read ? kf.read() : kf.write());
            }
        }
        return viewerRank() < floor;
    }

    /** The client view's role on the manifest scale: 0 anonymous, 1 fan, 2 podcaster, 3 admin. */
    private int viewerRank() {
        return role == null ? 0 : FLOOR_ROLES.indexOf(role.name().toLowerCase(Locale.ROOT));
    }

    private static int rank(String manifestRole) {
        int rank = FLOOR_ROLES.indexOf(manifestRole);
        if (rank < 0) {
            throw new IllegalArgumentException(
                    "'" + manifestRole + "' is not a manifest role — use one of " + FLOOR_ROLES);
        }
        return rank;
    }

    private static void requireSelector(String pattern, String field) {
        if (!KEY_SELECTOR.matcher(pattern).matches()) {
            throw new IllegalArgumentException(
                    field + " pattern '" + pattern + "' does not match " + KEY_SELECTOR_PATTERN
                            + " — the host rejects the manifest at load");
        }
    }

    /** One {@code data.keyFloors} pattern with its floors on the manifest scale (0 = not raised). */
    private record KeyFloor(String pattern, int read, int write) {}

    /** Whether one {@code backendOwned} pattern — exact key, {@code prefix*}, or bare {@code *} — covers a key. */
    private static boolean covers(String pattern, String key) {
        if (pattern.endsWith("*")) {
            return key.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(key);
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
            if (client && caller == null) {
                throw new IllegalStateException(
                        "an anonymous client has no USER partition: there is no session (the host answers 401)");
            }
            if (caller == null) {
                throw new UnsupportedOperationException(
                        "USER scope has no meaning on a backend: there is no calling user. "
                                + "Use allUsers().query(...) to aggregate (declare data.readsAllUsers), or address an entity scope.");
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
