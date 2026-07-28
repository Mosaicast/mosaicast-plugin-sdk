// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.Criteria;
import dev.mosaicast.plugin.api.SchemaStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * An in-memory {@link SchemaStore} for testing a schema-declaring plugin without Postgres
 * (ARCHITECTURE §13.5).
 *
 * <p>Declare the same entities and fields your manifest does, hand it to {@link FakePluginContext}, and
 * the plugin runs against it exactly as it would against the host:
 *
 * <pre>{@code
 * FakeSchemaStore schema = new FakeSchemaStore("plugin_wiki_")
 *         .withEntity("page", "slug", "title", "markdown", "updatedAt")
 *         .withFulltext("page", "markdown");
 *
 * FakePluginContext ctx = new FakePluginContext(
 *         new InMemoryDocStore(), new MapPluginConfig(), new FakeFeedAccess(Map.of()), schema);
 *
 * plugin.register(ctx);
 * assertEquals(1, schema.count("page", Criteria.all()));
 * }</pre>
 *
 * <p><strong>The declaration is enforced, because that is the part worth testing.</strong> Naming an
 * entity or field you did not declare throws {@link IllegalArgumentException} here just as it does on the
 * host, so a manifest that has drifted from the code fails in your test rather than at plugin load.
 *
 * <p><strong>What it does not reproduce:</strong> {@link #search(String, String, String, Criteria, Class)}
 * is a case-insensitive substring match, not Postgres full-text search. It has no stemming, no stop
 * words, and no relevance ranking — results come back in insertion order. It proves your search call is
 * wired to a full-text field and that you handle the results; it does not tell you what the host would
 * actually return. The same goes for {@link Criteria.Op#LIKE}: {@code %} works, collation subtleties do
 * not. Assert on which rows come back, not on their order or their score.
 *
 * <p>Values are round-tripped through Jackson on write, mirroring how a row makes it to the database and
 * back, so a type your target record cannot accept fails here too. Ids are assigned from a counter
 * starting at 1. Not thread-safe.
 */
public final class FakeSchemaStore implements SchemaStore {

    /** The column every provisioned entity carries, assigned by the platform. */
    private static final String ID = "id";

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String namespace;
    // Insertion-ordered throughout so query results are deterministic in tests.
    private final Map<String, Set<String>> fields = new LinkedHashMap<>();
    private final Map<String, Set<String>> fulltextFields = new LinkedHashMap<>();
    private final Map<String, Map<Long, Map<String, Object>>> rows = new LinkedHashMap<>();
    private long nextId = 1;

    /**
     * Creates an empty store for the given namespace; declare entities with
     * {@link #withEntity(String, String...)}.
     *
     * @param namespace the table-name prefix the platform would have reserved, e.g.
     *                  {@code plugin_wiki_}; never {@code null}
     */
    public FakeSchemaStore(String namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /**
     * Declares an entity and its fields, as the manifest would.
     *
     * <p>Do not list {@code id} — the platform assigns it, and so does this fake.
     *
     * @param entity the entity name; never {@code null}
     * @param fields the declared field names; never {@code null}
     * @return this instance, for chaining
     * @throws IllegalArgumentException if {@code fields} contains {@code id}
     */
    public FakeSchemaStore withEntity(String entity, String... fields) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(fields, "fields");
        Set<String> declared = new LinkedHashSet<>(List.of(fields));
        if (declared.contains(ID)) {
            throw new IllegalArgumentException("'id' is assigned by the platform — do not declare it");
        }
        this.fields.put(entity, declared);
        this.fulltextFields.putIfAbsent(entity, new LinkedHashSet<>());
        this.rows.putIfAbsent(entity, new LinkedHashMap<>());
        return this;
    }

    /**
     * Marks declared fields as {@code :fulltext}, so
     * {@link #search(String, String, String, Criteria, Class)} accepts them.
     *
     * @param entity the entity name; never {@code null} and already declared
     * @param fields the field names to mark; never {@code null} and already declared
     * @return this instance, for chaining
     * @throws IllegalArgumentException if the entity or any field is not declared
     */
    public FakeSchemaStore withFulltext(String entity, String... fields) {
        requireEntity(entity);
        Objects.requireNonNull(fields, "fields");
        for (String field : fields) {
            requireField(entity, field);
            fulltextFields.get(entity).add(field);
        }
        return this;
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public Set<String> entities() {
        return Set.copyOf(fields.keySet());
    }

    @Override
    public <T> Optional<T> find(String entity, long id, Class<T> type) {
        requireEntity(entity);
        Objects.requireNonNull(type, "type");
        Map<String, Object> row = rows.get(entity).get(id);
        return Optional.ofNullable(row).map(r -> mapper.convertValue(r, type));
    }

    @Override
    public <T> List<T> select(String entity, Criteria criteria, Class<T> type) {
        requireEntity(entity);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(criteria, "criteria");
        return matching(entity, criteria).stream()
                .map(row -> mapper.convertValue(row, type))
                .toList();
    }

    @Override
    public <T> List<T> search(String entity, String field, String text, Criteria criteria, Class<T> type) {
        requireEntity(entity);
        requireField(entity, field);
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(criteria, "criteria");
        Objects.requireNonNull(type, "type");
        if (!fulltextFields.get(entity).contains(field)) {
            throw new IllegalArgumentException(
                    "field '" + field + "' of entity '" + entity + "' is not declared :fulltext");
        }
        if (text.isEmpty()) {
            // Matches the contract: an empty query matches nothing rather than everything.
            return List.of();
        }
        String needle = text.toLowerCase(java.util.Locale.ROOT);
        return matching(entity, criteria).stream()
                .filter(row -> {
                    Object value = row.get(field);
                    return value != null && value.toString().toLowerCase(java.util.Locale.ROOT).contains(needle);
                })
                .map(row -> mapper.convertValue(row, type))
                .toList();
    }

    @Override
    public long count(String entity, Criteria criteria) {
        requireEntity(entity);
        Objects.requireNonNull(criteria, "criteria");
        // Ordering/limit/offset are ignored for a count, as the contract says.
        return filtered(entity, criteria).size();
    }

    @Override
    public long insert(String entity, Map<String, Object> values) {
        requireEntity(entity);
        requireWritableFields(entity, values);
        long id = nextId++;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(ID, id);
        // Declared-but-absent fields are stored as NULL, like a real insert.
        for (String field : fields.get(entity)) {
            row.put(field, roundTrip(values.get(field)));
        }
        rows.get(entity).put(id, row);
        return id;
    }

    @Override
    public int update(String entity, long id, Map<String, Object> values) {
        requireEntity(entity);
        requireWritableFields(entity, values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("update needs at least one field to write");
        }
        Map<String, Object> row = rows.get(entity).get(id);
        if (row == null) {
            return 0;
        }
        values.forEach((field, value) -> row.put(field, roundTrip(value)));
        return 1;
    }

    @Override
    public int delete(String entity, Criteria criteria) {
        requireEntity(entity);
        Objects.requireNonNull(criteria, "criteria");
        List<Map<String, Object>> doomed = filtered(entity, criteria);
        doomed.forEach(row -> rows.get(entity).remove((Long) row.get(ID)));
        return doomed.size();
    }

    // --- internals -------------------------------------------------------------------------------

    /** Applies predicates, then ordering, then offset/limit. */
    private List<Map<String, Object>> matching(String entity, Criteria criteria) {
        List<Map<String, Object>> out = new ArrayList<>(filtered(entity, criteria));
        for (Criteria.Order order : criteria.orders().reversed()) {
            requireField(entity, order.field());
            Comparator<Map<String, Object>> byField = Comparator.comparing(
                    row -> asComparable(row.get(order.field())),
                    Comparator.nullsLast(Comparator.naturalOrder()));
            out.sort(order.direction() == Criteria.Direction.DESC ? byField.reversed() : byField);
        }
        int from = Math.min(criteria.offset(), out.size());
        int to = criteria.limit().isPresent() ? Math.min(from + criteria.limit().getAsInt(), out.size()) : out.size();
        return out.subList(from, to);
    }

    /** Applies predicates only — no ordering, no paging. */
    private List<Map<String, Object>> filtered(String entity, Criteria criteria) {
        criteria.predicates().forEach(p -> requireField(entity, p.field()));
        return rows.get(entity).values().stream()
                .filter(row -> criteria.predicates().stream().allMatch(p -> matches(row.get(p.field()), p)))
                .toList();
    }

    private boolean matches(Object actual, Criteria.Predicate predicate) {
        return switch (predicate.op()) {
            case IS_NULL -> actual == null;
            case IS_NOT_NULL -> actual != null;
            case EQ -> Objects.equals(actual, normalized(predicate.value()));
            case NE -> !Objects.equals(actual, normalized(predicate.value()));
            case IN -> ((Collection<?>) predicate.value()).stream()
                    .map(this::normalized)
                    .anyMatch(v -> Objects.equals(actual, v));
            case LIKE -> actual != null && actual.toString().matches(likeToRegex(predicate.value().toString()));
            case LT -> compare(actual, predicate.value()) < 0;
            case LTE -> compare(actual, predicate.value()) <= 0;
            case GT -> compare(actual, predicate.value()) > 0;
            case GTE -> compare(actual, predicate.value()) >= 0;
        };
    }

    private int compare(Object actual, Object expected) {
        if (actual == null) {
            // SQL: a comparison against NULL is never true.
            return Integer.MIN_VALUE;
        }
        return asComparable(actual).compareTo(asComparable(normalized(expected)));
    }

    @SuppressWarnings("unchecked")
    private static Comparable<Object> asComparable(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            // Stored values round-trip through JSON, so 1 may come back as Integer and 1L as Long.
            return (Comparable<Object>) (Comparable<?>) Double.valueOf(n.doubleValue());
        }
        if (value instanceof Comparable<?> c) {
            return (Comparable<Object>) c;
        }
        return (Comparable<Object>) (Comparable<?>) value.toString();
    }

    /** Puts a comparison value through the same JSON round-trip stored values took. */
    private Object normalized(Object value) {
        return roundTrip(value);
    }

    private Object roundTrip(Object value) {
        return value == null ? null : mapper.convertValue(mapper.valueToTree(value), Object.class);
    }

    private static String likeToRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }

    private void requireEntity(String entity) {
        Objects.requireNonNull(entity, "entity");
        if (!fields.containsKey(entity)) {
            throw new IllegalArgumentException(
                    "entity '" + entity + "' is not declared — declared: " + fields.keySet());
        }
    }

    private void requireField(String entity, String field) {
        Objects.requireNonNull(field, "field");
        if (ID.equals(field)) {
            return;
        }
        if (!fields.get(entity).contains(field)) {
            throw new IllegalArgumentException("entity '" + entity + "' does not declare field '" + field
                    + "' — declared: " + fields.get(entity));
        }
    }

    private void requireWritableFields(String entity, Map<String, Object> values) {
        Objects.requireNonNull(values, "values");
        if (values.containsKey(ID)) {
            throw new IllegalArgumentException("'id' is assigned by the platform and must not be written");
        }
        values.keySet().forEach(field -> requireField(entity, field));
    }
}
