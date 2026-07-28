// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.Criteria.Direction;
import dev.mosaicast.plugin.api.Criteria.Op;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests the one piece of behaviour in the contract module: the {@link Criteria} builder. */
class CriteriaTest {

    @Test
    void allMatchesEverythingAndCarriesNothing() {
        Criteria all = Criteria.all();

        assertTrue(all.predicates().isEmpty());
        assertTrue(all.orders().isEmpty());
        assertTrue(all.limit().isEmpty());
        assertEquals(0, all.offset());
    }

    @Test
    void buildersReturnNewInstances() {
        Criteria base = Criteria.where("slug", Op.EQ, "a");
        Criteria refined = base.and("revision", Op.GT, 1).orderBy("slug", Direction.ASC).limit(10);

        // The base is shareable precisely because refining it cannot mutate it.
        assertEquals(1, base.predicates().size());
        assertTrue(base.orders().isEmpty());
        assertTrue(base.limit().isEmpty());
        assertEquals(2, refined.predicates().size());
    }

    @Test
    void predicatesKeepTheirOrder() {
        Criteria c = Criteria.where("a", Op.EQ, 1).and("b", Op.NE, 2).and("c", Op.GTE, 3);

        assertEquals(List.of("a", "b", "c"), c.predicates().stream().map(Criteria.Predicate::field).toList());
    }

    @Test
    void ordersApplyInTheOrderTheyWereAdded() {
        Criteria c = Criteria.all().orderBy("revision", Direction.DESC).orderBy("slug", Direction.ASC);

        assertEquals(List.of("revision", "slug"), c.orders().stream().map(Criteria.Order::field).toList());
        assertEquals(Direction.DESC, c.orders().get(0).direction());
    }

    @Test
    void inNeedsANonEmptyCollection() {
        assertThrows(IllegalArgumentException.class, () -> Criteria.where("slug", Op.IN, "a"));
        assertThrows(IllegalArgumentException.class, () -> Criteria.where("slug", Op.IN, List.of()));
        assertEquals(1, Criteria.where("slug", Op.IN, List.of("a", "b")).predicates().size());
    }

    @Test
    void limitMustBePositiveAndOffsetNonNegative() {
        assertThrows(IllegalArgumentException.class, () -> Criteria.all().limit(0));
        assertThrows(IllegalArgumentException.class, () -> Criteria.all().limit(-1));
        assertThrows(IllegalArgumentException.class, () -> Criteria.all().offset(-1));
        assertEquals(5, Criteria.all().limit(5).limit().getAsInt());
        assertEquals(3, Criteria.all().offset(3).offset());
    }

    @Test
    void rejectsNullFieldsAndOps() {
        assertThrows(NullPointerException.class, () -> Criteria.where(null, Op.EQ, "a"));
        assertThrows(NullPointerException.class, () -> Criteria.where("slug", null, "a"));
        assertThrows(NullPointerException.class, () -> Criteria.all().orderBy("slug", null));
    }

    @Test
    void equalityIsByValue() {
        assertEquals(Criteria.where("slug", Op.EQ, "a").limit(2), Criteria.where("slug", Op.EQ, "a").limit(2));
        assertEquals(
                Criteria.where("slug", Op.EQ, "a").hashCode(),
                Criteria.where("slug", Op.EQ, "a").hashCode());
    }
}
