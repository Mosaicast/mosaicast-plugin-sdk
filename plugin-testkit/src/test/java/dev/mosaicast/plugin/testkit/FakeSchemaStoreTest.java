// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.Criteria;
import dev.mosaicast.plugin.api.Criteria.Direction;
import dev.mosaicast.plugin.api.Criteria.Op;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.SchemaStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Self-tests for the relational half of the contract (ARCHITECTURE §7.6/§13.5). */
class FakeSchemaStoreTest {

    /** Mirrors the entity declared below; component names match the declared field names. */
    record Page(long id, String slug, String title, String markdown, int revision) {
    }

    private static FakeSchemaStore wikiSchema() {
        return new FakeSchemaStore("plugin_wiki_")
                .withEntity("page", "slug", "title", "markdown", "revision")
                .withFulltext("page", "markdown");
    }

    private static long insertPage(SchemaStore schema, String slug, String title, String markdown, int revision) {
        return schema.insert("page", Map.of(
                "slug", slug, "title", title, "markdown", markdown, "revision", revision));
    }

    @Test
    void exposesItsNamespaceAndDeclaredEntities() {
        FakeSchemaStore schema = wikiSchema();

        assertEquals("plugin_wiki_", schema.namespace());
        assertEquals(Set.of("page"), schema.entities());
    }

    @Test
    void insertAssignsAnIdAndFindReadsItBack() {
        FakeSchemaStore schema = wikiSchema();

        long id = insertPage(schema, "getting-started", "Getting started", "# Hello", 1);

        Optional<Page> found = schema.find("page", id, Page.class);
        assertTrue(found.isPresent());
        assertEquals(new Page(id, "getting-started", "Getting started", "# Hello", 1), found.get());
        assertTrue(schema.find("page", id + 999, Page.class).isEmpty());
    }

    @Test
    void selectFiltersOrdersAndPages() {
        FakeSchemaStore schema = wikiSchema();
        insertPage(schema, "a", "A", "alpha", 3);
        insertPage(schema, "b", "B", "beta", 1);
        insertPage(schema, "c", "C", "gamma", 2);

        List<Page> byRevision = schema.select("page",
                Criteria.all().orderBy("revision", Direction.DESC), Page.class);
        assertEquals(List.of("a", "c", "b"), byRevision.stream().map(Page::slug).toList());

        List<Page> filtered = schema.select("page",
                Criteria.where("revision", Op.GTE, 2).orderBy("slug", Direction.ASC), Page.class);
        assertEquals(List.of("a", "c"), filtered.stream().map(Page::slug).toList());

        List<Page> paged = schema.select("page",
                Criteria.all().orderBy("slug", Direction.ASC).offset(1).limit(1), Page.class);
        assertEquals(List.of("b"), paged.stream().map(Page::slug).toList());
    }

    @Test
    void predicatesAreCombinedWithAnd() {
        FakeSchemaStore schema = wikiSchema();
        insertPage(schema, "a", "A", "alpha", 1);
        insertPage(schema, "b", "B", "beta", 1);

        List<Page> both = schema.select("page",
                Criteria.where("revision", Op.EQ, 1).and("slug", Op.EQ, "b"), Page.class);

        assertEquals(List.of("b"), both.stream().map(Page::slug).toList());
    }

    @Test
    void supportsInLikeAndNullChecks() {
        FakeSchemaStore schema = wikiSchema();
        insertPage(schema, "getting-started", "Getting started", "x", 1);
        insertPage(schema, "faq", "FAQ", "y", 1);
        schema.insert("page", Map.of("slug", "stub", "revision", 1));   // title/markdown left NULL

        assertEquals(2, schema.count("page", Criteria.where("slug", Op.IN, List.of("faq", "stub"))));
        assertEquals(1, schema.count("page", Criteria.where("slug", Op.LIKE, "getting-%")));
        assertEquals(1, schema.count("page", Criteria.where("title", Op.IS_NULL, null)));
        assertEquals(2, schema.count("page", Criteria.where("title", Op.IS_NOT_NULL, null)));
    }

    @Test
    void searchMatchesAFulltextField() {
        FakeSchemaStore schema = wikiSchema();
        insertPage(schema, "a", "A", "The Lighthouse episode notes", 1);
        insertPage(schema, "b", "B", "Unrelated", 1);

        List<Page> hits = schema.search("page", "markdown", "lighthouse", Criteria.all(), Page.class);
        assertEquals(List.of("a"), hits.stream().map(Page::slug).toList());

        // The contract: an empty query matches nothing, not everything.
        assertTrue(schema.search("page", "markdown", "", Criteria.all(), Page.class).isEmpty());
    }

    @Test
    void searchRejectsAFieldThatIsNotDeclaredFulltext() {
        FakeSchemaStore schema = wikiSchema();
        insertPage(schema, "a", "A", "x", 1);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> schema.search("page", "title", "A", Criteria.all(), Page.class));
        assertTrue(e.getMessage().contains(":fulltext"));
    }

    @Test
    void updateWritesOnlyTheGivenFields() {
        FakeSchemaStore schema = wikiSchema();
        long id = insertPage(schema, "a", "A", "old", 1);

        assertEquals(1, schema.update("page", id, Map.of("markdown", "new")));

        Page page = schema.find("page", id, Page.class).orElseThrow();
        assertEquals("new", page.markdown());
        assertEquals("A", page.title());   // untouched
        assertEquals(0, schema.update("page", id + 999, Map.of("markdown", "x")));
    }

    @Test
    void deleteRemovesMatchingRows() {
        FakeSchemaStore schema = wikiSchema();
        insertPage(schema, "a", "A", "x", 1);
        insertPage(schema, "b", "B", "y", 2);

        assertEquals(1, schema.delete("page", Criteria.where("revision", Op.EQ, 2)));
        assertEquals(1, schema.count("page", Criteria.all()));

        assertEquals(1, schema.delete("page", Criteria.all()));   // emptying an entity is allowed
        assertEquals(0, schema.count("page", Criteria.all()));
    }

    @Test
    void rejectsAnEntityTheManifestDoesNotDeclare() {
        FakeSchemaStore schema = wikiSchema();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> schema.select("comment", Criteria.all(), Page.class));
        assertTrue(e.getMessage().contains("not declared"));
    }

    @Test
    void rejectsAFieldTheManifestDoesNotDeclare() {
        FakeSchemaStore schema = wikiSchema();

        // A manifest that has drifted from the code fails in the test, not at plugin load.
        assertThrows(IllegalArgumentException.class,
                () -> schema.select("page", Criteria.where("author", Op.EQ, "me"), Page.class));
        assertThrows(IllegalArgumentException.class,
                () -> schema.insert("page", Map.of("slug", "a", "author", "me")));
    }

    @Test
    void refusesToLetAPluginAssignItsOwnId() {
        FakeSchemaStore schema = wikiSchema();

        assertThrows(IllegalArgumentException.class,
                () -> schema.insert("page", Map.of("id", 7L, "slug", "a")));
        assertThrows(IllegalArgumentException.class, () -> new FakeSchemaStore("x").withEntity("page", "id"));
    }

    @Test
    void contextHandsTheSchemaToThePlugin() {
        FakeSchemaStore schema = wikiSchema();
        FeedAccess feeds = new FakeFeedAccess(Map.of());
        PluginContext ctx = new FakePluginContext(new InMemoryDocStore(), new MapPluginConfig(), feeds, schema);

        assertEquals(schema, ctx.schema());
        // Still null for the majority of plugins, which declare no schema at all.
        assertFalse(new FakePluginContext().schema() != null);
    }
}
