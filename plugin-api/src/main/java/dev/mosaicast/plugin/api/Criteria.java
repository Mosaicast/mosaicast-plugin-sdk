// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * A filter over one {@link SchemaStore} entity: which rows, in what order, how many
 * (ARCHITECTURE §7.6).
 *
 * <p>Criteria name <strong>declared fields</strong>, never SQL. That is the point: the host builds the
 * statement, binds every value as a JDBC parameter, and checks each field name against the entity's
 * manifest declaration — so nothing a plugin passes can reach the database as SQL text, and a plugin
 * cannot address a table (its own or anyone else's) at all.
 *
 * <p>Instances are <strong>immutable</strong>; every builder method returns a new one, so a base criteria
 * can be shared and refined:
 *
 * <pre>{@code
 * Criteria recent = Criteria.all().orderBy("updatedAt", Criteria.Direction.DESC).limit(20);
 *
 * List<Page> published = schema.select("page",
 *         recent.and("published", Criteria.Op.EQ, true), Page.class);
 * }</pre>
 *
 * <p><strong>Predicates are combined with AND.</strong> There is no {@code or} in this version: a flat
 * list mixing both is ambiguous, and disjunction is not needed by anything the platform provisions today.
 * If you need it, model it as two queries and merge, or open an issue — it can be added compatibly.
 */
public final class Criteria {

    /** How a field is compared to a value. */
    public enum Op {

        /** Equal to the value. */
        EQ,

        /** Not equal to the value. */
        NE,

        /** Less than the value. */
        LT,

        /** Less than or equal to the value. */
        LTE,

        /** Greater than the value. */
        GT,

        /** Greater than or equal to the value. */
        GTE,

        /** Contained in the given {@link Collection} of values. */
        IN,

        /**
         * Matches a pattern, with {@code %} as the wildcard — case-sensitive.
         *
         * <p>For finding text inside a {@code text:fulltext} field, prefer
         * {@link SchemaStore#search(String, String, String, Criteria, Class)}: it uses the index the
         * platform provisioned, where a leading-wildcard {@code LIKE} cannot.
         */
        LIKE,

        /** Is SQL {@code NULL}; the value is ignored and should be {@code null}. */
        IS_NULL,

        /** Is not SQL {@code NULL}; the value is ignored and should be {@code null}. */
        IS_NOT_NULL
    }

    /** Sort direction. */
    public enum Direction {

        /** Ascending. */
        ASC,

        /** Descending. */
        DESC
    }

    /**
     * One {@code field op value} condition.
     *
     * @param field the declared field name; never {@code null}
     * @param op    the comparison; never {@code null}
     * @param value the value to compare against — a {@link Collection} for {@link Op#IN}, and
     *              {@code null} for {@link Op#IS_NULL} / {@link Op#IS_NOT_NULL}
     */
    public record Predicate(String field, Op op, Object value) {

        /** Canonical constructor validating the field name and comparison. */
        public Predicate {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(op, "op");
            if (op == Op.IN && !(value instanceof Collection<?> c && !c.isEmpty())) {
                throw new IllegalArgumentException("IN needs a non-empty Collection value, got: " + value);
            }
        }
    }

    /**
     * One sort term.
     *
     * @param field     the declared field name; never {@code null}
     * @param direction the sort direction; never {@code null}
     */
    public record Order(String field, Direction direction) {

        /** Canonical constructor validating both components. */
        public Order {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(direction, "direction");
        }
    }

    private static final Criteria ALL = new Criteria(List.of(), List.of(), -1, 0);

    private final List<Predicate> predicates;
    private final List<Order> orders;
    private final int limit;
    private final int offset;

    private Criteria(List<Predicate> predicates, List<Order> orders, int limit, int offset) {
        this.predicates = predicates;
        this.orders = orders;
        this.limit = limit;
        this.offset = offset;
    }

    /**
     * Matches every row of the entity — unfiltered, unordered, unlimited.
     *
     * @return the empty criteria
     */
    public static Criteria all() {
        return ALL;
    }

    /**
     * Starts a criteria with one condition.
     *
     * @param field the declared field name; never {@code null}
     * @param op    the comparison; never {@code null}
     * @param value the value to compare against
     * @return a new criteria carrying that one predicate
     * @throws IllegalArgumentException if {@code op} is {@link Op#IN} and {@code value} is not a
     *                                  non-empty {@link Collection}
     */
    public static Criteria where(String field, Op op, Object value) {
        return ALL.and(field, op, value);
    }

    /**
     * Adds a condition, combined with the existing ones using AND.
     *
     * @param field the declared field name; never {@code null}
     * @param op    the comparison; never {@code null}
     * @param value the value to compare against
     * @return a new criteria; this one is unchanged
     * @throws IllegalArgumentException if {@code op} is {@link Op#IN} and {@code value} is not a
     *                                  non-empty {@link Collection}
     */
    public Criteria and(String field, Op op, Object value) {
        List<Predicate> next = new ArrayList<>(predicates);
        next.add(new Predicate(field, op, value));
        return new Criteria(List.copyOf(next), orders, limit, offset);
    }

    /**
     * Appends a sort term. Terms apply in the order they were added.
     *
     * @param field     the declared field name; never {@code null}
     * @param direction the sort direction; never {@code null}
     * @return a new criteria; this one is unchanged
     */
    public Criteria orderBy(String field, Direction direction) {
        List<Order> next = new ArrayList<>(orders);
        next.add(new Order(field, direction));
        return new Criteria(predicates, List.copyOf(next), limit, offset);
    }

    /**
     * Caps how many rows come back.
     *
     * <p>Worth setting on anything user-facing: the platform provisions real tables and does not cap
     * result sets for you.
     *
     * @param max the maximum row count; must be positive
     * @return a new criteria; this one is unchanged
     * @throws IllegalArgumentException if {@code max} is not positive
     */
    public Criteria limit(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + max);
        }
        return new Criteria(predicates, orders, max, offset);
    }

    /**
     * Skips the first {@code n} rows — pair it with {@link #limit(int)} and a stable
     * {@link #orderBy(String, Direction)} for paging.
     *
     * @param n how many rows to skip; must not be negative
     * @return a new criteria; this one is unchanged
     * @throws IllegalArgumentException if {@code n} is negative
     */
    public Criteria offset(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + n);
        }
        return new Criteria(predicates, orders, limit, n);
    }

    /**
     * The conditions, in the order they were added, to be combined with AND.
     *
     * <p>Read by the host when it builds the statement; plugins use the builder methods instead.
     *
     * @return the predicates; never {@code null}, empty when unfiltered
     */
    public List<Predicate> predicates() {
        return predicates;
    }

    /**
     * The sort terms, in application order.
     *
     * @return the orders; never {@code null}, empty when unordered
     */
    public List<Order> orders() {
        return orders;
    }

    /**
     * The row cap, if one was set.
     *
     * @return the limit, or {@link OptionalInt#empty()} when unlimited
     */
    public OptionalInt limit() {
        return limit > 0 ? OptionalInt.of(limit) : OptionalInt.empty();
    }

    /**
     * How many rows to skip.
     *
     * @return the offset; {@code 0} when none was set
     */
    public int offset() {
        return offset;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Criteria c
                && limit == c.limit
                && offset == c.offset
                && predicates.equals(c.predicates)
                && orders.equals(c.orders);
    }

    @Override
    public int hashCode() {
        return Objects.hash(predicates, orders, limit, offset);
    }

    @Override
    public String toString() {
        return "Criteria[predicates=%s, orders=%s, limit=%s, offset=%d]"
                .formatted(predicates, orders, limit > 0 ? limit : "none", offset);
    }
}
